/**
 * CarCast Mirror — Windows PC Receiver Server
 * 
 * Provides:
 * 1. WebSocket Streaming Endpoint on port 8080 (receives H.264 video & PCM audio from Android Sender)
 * 2. HTTP Web Receiver Portal on port 8088 (serves low-latency WebCodecs hardware player)
 * 3. Local mDNS Service Advertising (_carcast._tcp) for automatic discovery from Android phones
 * 4. Reverse Mouse/Touch Control and Keyboard forwarding back to Android phone
 * 5. Clean lifecycle management and safe EADDRINUSE handling
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const dgram = require('dgram');
const WebSocket = require('ws');

const HTTP_PORT = 8088;
const WS_STREAM_PORT = 8080;
const HOSTNAME = os.hostname();
const RECEIVER_NAME = `CarCast-PC (${HOSTNAME})`;
const STABLE_ID = `cc-win-${Buffer.from(HOSTNAME).toString('hex').slice(0, 10)}`;

let httpServer = null;
let streamHttpServer = null;
let senderWss = null;
let viewerWss = null;
let mdnsSocket = null;
let mdnsInterval = null;

let activeSenderSocket = null;
const connectedViewers = new Set();
let latestMetadata = null;

function getLocalIp() {
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                return iface.address;
            }
        }
    }
    return '127.0.0.1';
}

const LOCAL_IP = getLocalIp();

function broadcastToViewers(obj) {
    const jsonStr = typeof obj === 'string' ? obj : JSON.stringify(obj);
    for (const viewer of connectedViewers) {
        if (viewer.readyState === WebSocket.OPEN) {
            try {
                viewer.send(jsonStr);
            } catch (_) {}
        }
    }
}

function startServer(callback) {
    console.log('====================================================');
    console.log('   CarCast Mirror — Windows PC Screen Receiver       ');
    console.log('====================================================');
    console.log(`[INFO] Computer Hostname: ${HOSTNAME}`);
    console.log(`[INFO] Receiver Name    : ${RECEIVER_NAME}`);
    console.log(`[INFO] Stable Device ID : ${STABLE_ID}`);
    console.log(`[INFO] Local IP Address : ${LOCAL_IP}`);

    let httpReady = false;
    let streamReady = false;

    function checkReady() {
        if (httpReady && streamReady && typeof callback === 'function') {
            callback();
        }
    }

    // 1. HTTP Server on port 8088 (serves public portal and WebCodecs player)
    httpServer = http.createServer((req, res) => {
        let filePath = path.join(__dirname, 'public', req.url === '/' ? 'index.html' : req.url);

        fs.readFile(filePath, (err, data) => {
            if (err) {
                res.writeHead(404, { 'Content-Type': 'text/plain' });
                res.end('404 Not Found');
                return;
            }

            let ext = path.extname(filePath);
            let contentType = 'text/html';
            if (ext === '.js') contentType = 'application/javascript';
            if (ext === '.css') contentType = 'text/css';
            if (ext === '.json') contentType = 'application/json';
            if (ext === '.png') contentType = 'image/png';
            if (ext === '.svg') contentType = 'image/svg+xml';

            res.writeHead(200, {
                'Content-Type': contentType,
                'Access-Control-Allow-Origin': '*'
            });
            res.end(data);
        });
    });

    httpServer.on('error', (err) => {
        if (err.code === 'EADDRINUSE') {
            console.log(`[HTTP:INFO] Port ${HTTP_PORT} is already in use by CarCast. Reusing active instance.`);
            httpReady = true;
            checkReady();
        } else {
            console.error('[HTTP:ERROR]', err.message);
        }
    });

    httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
        console.log(`[HTTP] Receiver Portal running at: http://localhost:${HTTP_PORT} (LAN: http://${LOCAL_IP}:${HTTP_PORT})`);
        httpReady = true;
        checkReady();
    });

    // 2. Viewer WebSocket Server mounted on HTTP server at /viewer
    viewerWss = new WebSocket.Server({ server: httpServer, path: '/viewer' });
    viewerWss.on('error', (err) => {
        console.warn('[VIEWER:WS:WARN]', err.message);
    });

    viewerWss.on('connection', (ws) => {
        connectedViewers.add(ws);
        console.log(`[VIEWER] Local PC Display Player connected (Total viewers: ${connectedViewers.size})`);

        ws.send(JSON.stringify({
            type: 'status',
            connected: activeSenderSocket !== null,
            message: activeSenderSocket ? 'Connected to Phone' : 'Waiting for CarCast Phone to connect...'
        }));

        if (latestMetadata) {
            ws.send(JSON.stringify(latestMetadata));
        }

        if (activeSenderSocket && activeSenderSocket.readyState === WebSocket.OPEN) {
            try {
                activeSenderSocket.send(JSON.stringify({ type: 'request_keyframe' }));
                console.log('[VIEWER] Triggered keyframe sync for new viewer');
            } catch (_) {}
        }

        ws.on('message', (message) => {
            try {
                const data = JSON.parse(message.toString());
                if (activeSenderSocket && activeSenderSocket.readyState === WebSocket.OPEN) {
                    if (data.type === 'touch' || data.type === 'key' || data.type === 'request_keyframe') {
                        activeSenderSocket.send(JSON.stringify(data));
                        if (data.type === 'touch' && (data.action === 'down' || data.action === 'up')) {
                            console.log(`[TOUCH:PC] Injected ${data.action} at (${data.x.toFixed(3)}, ${data.y.toFixed(3)}) -> Phone`);
                        } else if (data.type === 'key') {
                            console.log(`[KEY:PC] Injected action=${data.action} text='${data.text || ''}' -> Phone`);
                        }
                    }
                }
            } catch (_) {}
        });

        ws.on('close', () => {
            connectedViewers.delete(ws);
            console.log(`[VIEWER] Viewer disconnected (Remaining: ${connectedViewers.size})`);
        });
    });

    // 3. Sender Streaming WebSocket Endpoint on port 8080 (explicit HTTP server wrapper for safe EADDRINUSE)
    streamHttpServer = http.createServer((req, res) => {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('CarCast Mirror Streaming Server');
    });

    streamHttpServer.on('error', (err) => {
        if (err.code === 'EADDRINUSE') {
            console.log(`[STREAM:INFO] Port ${WS_STREAM_PORT} is already in use by CarCast. Reusing active instance.`);
            streamReady = true;
            checkReady();
        } else {
            console.error('[STREAM:ERROR]', err.message);
        }
    });

    streamHttpServer.listen(WS_STREAM_PORT, '0.0.0.0', () => {
        console.log(`[STREAM] Streaming WebSocket Server listening on ws://0.0.0.0:${WS_STREAM_PORT}`);
        streamReady = true;
        checkReady();
    });

    senderWss = new WebSocket.Server({ server: streamHttpServer });
    senderWss.on('error', (err) => {
        console.warn('[STREAM:WS:WARN]', err.message);
    });

    senderWss.on('connection', (ws, req) => {
        const remoteIp = req.socket.remoteAddress;
        console.log(`[STREAM:SENDER] Android Phone connected from ${remoteIp}`);
        activeSenderSocket = ws;

        try {
            ws.send(JSON.stringify({ type: 'request_keyframe' }));
            console.log('[STREAM:SENDER] Sent initial request_keyframe to Phone');
        } catch (_) {}

        broadcastToViewers({
            type: 'status',
            connected: true,
            senderIp: remoteIp,
            message: 'Phone Connected. Receiving Stream...'
        });

        let frameCount = 0;
        ws.on('message', (data, isBinary) => {
            if (isBinary) {
                frameCount++;
                if (frameCount === 1 || frameCount % 300 === 0) {
                    console.log(`[STREAM:FRAME] Forwarded frame #${frameCount} (${data.length} bytes) to ${connectedViewers.size} viewer(s)`);
                }
                for (const viewer of connectedViewers) {
                    if (viewer.readyState === WebSocket.OPEN) {
                        try {
                            viewer.send(data, { binary: true });
                        } catch (_) {}
                    }
                }
            } else {
                try {
                    const text = data.toString();
                    const json = JSON.parse(text);
                    if (json.type === 'meta') {
                        latestMetadata = json;
                        console.log(`[STREAM:META] Format: ${json.width}x${json.height} (profile: ${json.profile || 'auto'})`);
                    }
                    broadcastToViewers(json);
                } catch (e) {
                    console.warn('[STREAM:WARN] Non-json text message received:', data.toString().slice(0, 50));
                }
            }
        });

        ws.on('close', () => {
            console.log('[STREAM:SENDER] Android Phone disconnected.');
            if (activeSenderSocket === ws) activeSenderSocket = null;
            latestMetadata = null;
            broadcastToViewers({
                type: 'status',
                connected: false,
                message: 'Waiting for CarCast Phone to connect...'
            });
        });

        ws.on('error', (err) => {
            console.error('[STREAM:ERROR] Sender socket error:', err.message);
        });
    });

    // 4. Start mDNS Service Advertiser
    startMdnsAdvertiser();
}

function stopServer(callback) {
    console.log('[SERVER] Shutting down CarCast Receiver services...');

    if (mdnsInterval) {
        clearInterval(mdnsInterval);
        mdnsInterval = null;
    }

    if (mdnsSocket) {
        try { mdnsSocket.close(); } catch (_) {}
        mdnsSocket = null;
    }

    if (viewerWss) {
        try { viewerWss.close(); } catch (_) {}
        viewerWss = null;
    }

    if (senderWss) {
        try { senderWss.close(); } catch (_) {}
        senderWss = null;
    }

    let closedHttp = false;
    let closedStream = false;

    function finish() {
        if (closedHttp && closedStream) {
            console.log('[SERVER] All ports and services cleanly released.');
            if (typeof callback === 'function') callback();
        }
    }

    if (httpServer) {
        try {
            httpServer.close(() => {
                closedHttp = true;
                finish();
            });
        } catch (_) {
            closedHttp = true;
            finish();
        }
    } else {
        closedHttp = true;
    }

    if (streamHttpServer) {
        try {
            streamHttpServer.close(() => {
                closedStream = true;
                finish();
            });
        } catch (_) {
            closedStream = true;
            finish();
        }
    } else {
        closedStream = true;
    }

    finish();
}

// 5. Lightweight mDNS DNS-SD Advertiser for _carcast._tcp
function startMdnsAdvertiser() {
    try {
        mdnsSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

        mdnsSocket.on('error', (err) => {
            console.warn('[mDNS:WARN] mDNS socket error:', err.message);
        });

        mdnsSocket.bind(5353, () => {
            try {
                mdnsSocket.addMembership('224.0.0.251');
                console.log(`[mDNS] Multicast DNS-SD broadcasting active for service '_carcast._tcp.local.'`);
            } catch (e) {
                console.warn('[mDNS:WARN] Could not join multicast group:', e.message);
            }
        });

        mdnsInterval = setInterval(() => {
            if (mdnsSocket) sendMdnsAnnouncement(mdnsSocket);
        }, 5000);

        setTimeout(() => {
            if (mdnsSocket) sendMdnsAnnouncement(mdnsSocket);
        }, 1000);

    } catch (e) {
        console.warn('[mDNS:WARN] Failed to start mDNS advertiser:', e.message);
    }
}

function sendMdnsAnnouncement(socket) {
    try {
        const packet = createDnsSdResponsePacket(RECEIVER_NAME, LOCAL_IP, WS_STREAM_PORT, STABLE_ID);
        socket.send(packet, 0, packet.length, 5353, '224.0.0.251');
    } catch (_) {}
}

function createDnsSdResponsePacket(serviceName, ip, port, stableId) {
    const parts = [];

    const header = Buffer.alloc(12);
    header.writeUInt16BE(0, 0);
    header.writeUInt16BE(0x8400, 2);
    header.writeUInt16BE(0, 4);
    header.writeUInt16BE(4, 6);
    header.writeUInt16BE(0, 8);
    header.writeUInt16BE(0, 10);
    parts.push(header);

    // 1. PTR record
    parts.push(encodeDnsName('_carcast._tcp.local'));
    const ptrHeader = Buffer.alloc(10);
    ptrHeader.writeUInt16BE(12, 0);
    ptrHeader.writeUInt16BE(0x8001, 2);
    ptrHeader.writeUInt32BE(120, 4);
    const targetName = encodeDnsName(`${serviceName}._carcast._tcp.local`);
    ptrHeader.writeUInt16BE(targetName.length, 8);
    parts.push(ptrHeader, targetName);

    // 2. SRV record
    parts.push(encodeDnsName(`${serviceName}._carcast._tcp.local`));
    const srvHeader = Buffer.alloc(10);
    srvHeader.writeUInt16BE(33, 0);
    srvHeader.writeUInt16BE(0x8001, 2);
    srvHeader.writeUInt32BE(120, 4);
    const hostTarget = encodeDnsName(`${HOSTNAME}.local`);
    srvHeader.writeUInt16BE(6 + hostTarget.length, 8);
    const srvData = Buffer.alloc(6);
    srvData.writeUInt16BE(0, 0);
    srvData.writeUInt16BE(0, 2);
    srvData.writeUInt16BE(port, 4);
    parts.push(srvHeader, srvData, hostTarget);

    // 3. TXT record
    parts.push(encodeDnsName(`${serviceName}._carcast._tcp.local`));
    const txtEntries = [
        `id=${stableId}`,
        `name=${serviceName}`,
        `platform=WINDOWS_PC`,
        `type=receiver`,
        `wsPort=${port}`,
        `httpPort=${HTTP_PORT}`,
        `model=CarCast-Windows`
    ];
    let txtLength = 0;
    const txtBufs = txtEntries.map(entry => {
        const b = Buffer.from(entry);
        txtLength += b.length + 1;
        return Buffer.concat([Buffer.from([b.length]), b]);
    });
    const txtHeader = Buffer.alloc(10);
    txtHeader.writeUInt16BE(16, 0);
    txtHeader.writeUInt16BE(0x8001, 2);
    txtHeader.writeUInt32BE(120, 4);
    txtHeader.writeUInt16BE(txtLength, 8);
    parts.push(txtHeader, Buffer.concat(txtBufs));

    // 4. A record
    parts.push(encodeDnsName(`${HOSTNAME}.local`));
    const aHeader = Buffer.alloc(10);
    aHeader.writeUInt16BE(1, 0);
    aHeader.writeUInt16BE(0x8001, 2);
    aHeader.writeUInt32BE(120, 4);
    aHeader.writeUInt16BE(4, 8);
    const ipBuf = Buffer.from(ip.split('.').map(n => parseInt(n, 10)));
    parts.push(aHeader, ipBuf);

    return Buffer.concat(parts);
}

function encodeDnsName(name) {
    const parts = name.split('.');
    const bufs = [];
    for (const part of parts) {
        if (!part) continue;
        const b = Buffer.from(part);
        bufs.push(Buffer.from([b.length]), b);
    }
    bufs.push(Buffer.from([0]));
    return Buffer.concat(bufs);
}

if (require.main === module) {
    startServer();
}

module.exports = {
    startServer,
    stopServer,
    HTTP_PORT,
    WS_STREAM_PORT
};
