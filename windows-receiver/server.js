/**
 * CarCast Mirror — Windows PC Receiver Server
 * 
 * Provides:
 * 1. WebSocket Streaming Endpoint on port 8080 (receives H.264 video & PCM audio from Android Sender)
 * 2. HTTP Web Receiver Portal on port 8088 (serves low-latency WebCodecs hardware player)
 * 3. Local mDNS Service Advertising (_carcast._tcp) for automatic discovery from Android phones
 * 4. Reverse Mouse/Touch Control forwarding back to Android phone
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

console.log('====================================================');
console.log('   CarCast Mirror — Windows PC Screen Receiver       ');
console.log('====================================================');
console.log(`[INFO] Computer Hostname: ${HOSTNAME}`);
console.log(`[INFO] Receiver Name    : ${RECEIVER_NAME}`);
console.log(`[INFO] Stable Device ID : ${STABLE_ID}`);

// 1. Get primary local IPv4 address
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
console.log(`[INFO] Local IP Address : ${LOCAL_IP}`);

// 2. HTTP Server serving the WebCodecs Video & Audio Player
const server = http.createServer((req, res) => {
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

server.listen(HTTP_PORT, '0.0.0.0', () => {
    console.log(`[HTTP] Receiver Portal running at: http://localhost:${HTTP_PORT} (LAN: http://${LOCAL_IP}:${HTTP_PORT})`);
});

// 3. WebSocket Servers
// Phone Sender connects to ws://<PC_IP>:8080
const senderWss = new WebSocket.Server({ port: WS_STREAM_PORT });
// Local Web Player connects to ws://localhost:8088/viewer
const viewerWss = new WebSocket.Server({ server: server, path: '/viewer' });

let activeSenderSocket = null;
const connectedViewers = new Set();
let latestMetadata = null;

senderWss.on('connection', (ws, req) => {
    const remoteIp = req.socket.remoteAddress;
    console.log(`[STREAM:SENDER] Android Phone connected from ${remoteIp}`);
    activeSenderSocket = ws;

    // Immediately request an IDR keyframe with SPS/PPS from Android Phone
    try {
        ws.send(JSON.stringify({ type: 'request_keyframe' }));
        console.log('[STREAM:SENDER] Sent initial request_keyframe to Phone');
    } catch (_) {}

    // Notify viewers
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
            // Forward binary video/audio frames to all connected PC viewer windows
            for (const viewer of connectedViewers) {
                if (viewer.readyState === WebSocket.OPEN) {
                    viewer.send(data, { binary: true });
                }
            }
        } else {
            // Text JSON (metadata, heartbeat, etc.)
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

viewerWss.on('connection', (ws) => {
    connectedViewers.add(ws);
    console.log(`[VIEWER] Local PC Display Player connected (Total viewers: ${connectedViewers.size})`);

    // Send current status
    ws.send(JSON.stringify({
        type: 'status',
        connected: activeSenderSocket !== null,
        message: activeSenderSocket ? 'Connected to Phone' : 'Waiting for CarCast Phone to connect...'
    }));

    if (latestMetadata) {
        ws.send(JSON.stringify(latestMetadata));
    }

    // If phone is already connected when viewer opens, request keyframe immediately
    if (activeSenderSocket && activeSenderSocket.readyState === WebSocket.OPEN) {
        try {
            activeSenderSocket.send(JSON.stringify({ type: 'request_keyframe' }));
            console.log('[VIEWER] Triggered keyframe sync for new viewer');
        } catch (_) {}
    }

    // Handle reverse touch/mouse events, keyboard events and keyframe requests from PC screen and forward to phone
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

function broadcastToViewers(obj) {
    const jsonStr = typeof obj === 'string' ? obj : JSON.stringify(obj);
    for (const viewer of connectedViewers) {
        if (viewer.readyState === WebSocket.OPEN) {
            viewer.send(jsonStr);
        }
    }
}

// 4. Lightweight mDNS DNS-SD Advertiser for _carcast._tcp
function startMdnsAdvertiser() {
    try {
        const mdnsSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
        
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

        // Periodic response beacon every 5 seconds
        setInterval(() => {
            sendMdnsAnnouncement(mdnsSocket);
        }, 5000);

        // Initial announcement
        setTimeout(() => sendMdnsAnnouncement(mdnsSocket), 1000);

    } catch (e) {
        console.warn('[mDNS:WARN] Failed to start mDNS advertiser:', e.message);
    }
}

function sendMdnsAnnouncement(socket) {
    try {
        // Construct standard DNS response packet for _carcast._tcp.local
        const packet = createDnsSdResponsePacket(RECEIVER_NAME, LOCAL_IP, WS_STREAM_PORT, STABLE_ID);
        socket.send(packet, 0, packet.length, 5353, '224.0.0.251');
    } catch (_) {}
}

function createDnsSdResponsePacket(serviceName, ip, port, stableId) {
    const parts = [];
    
    // Header: ID=0, Flags=0x8400 (Standard response, Authoritative), QDCOUNT=0, ANCOUNT=4
    const header = Buffer.alloc(12);
    header.writeUInt16BE(0, 0);
    header.writeUInt16BE(0x8400, 2);
    header.writeUInt16BE(0, 4);
    header.writeUInt16BE(4, 6); // 4 answers (PTR, SRV, TXT, A)
    header.writeUInt16BE(0, 8);
    header.writeUInt16BE(0, 10);
    parts.push(header);

    // 1. PTR record: _carcast._tcp.local -> <serviceName>._carcast._tcp.local
    parts.push(encodeDnsName('_carcast._tcp.local'));
    const ptrHeader = Buffer.alloc(10);
    ptrHeader.writeUInt16BE(12, 0); // Type PTR
    ptrHeader.writeUInt16BE(0x8001, 2); // Class IN (cache flush)
    ptrHeader.writeUInt32BE(120, 4); // TTL
    const targetName = encodeDnsName(`${serviceName}._carcast._tcp.local`);
    ptrHeader.writeUInt16BE(targetName.length, 8);
    parts.push(ptrHeader, targetName);

    // 2. SRV record: <serviceName>._carcast._tcp.local -> target host & port
    parts.push(encodeDnsName(`${serviceName}._carcast._tcp.local`));
    const srvHeader = Buffer.alloc(10);
    srvHeader.writeUInt16BE(33, 0); // Type SRV
    srvHeader.writeUInt16BE(0x8001, 2);
    srvHeader.writeUInt32BE(120, 4);
    const hostTarget = encodeDnsName(`${HOSTNAME}.local`);
    srvHeader.writeUInt16BE(6 + hostTarget.length, 8);
    const srvData = Buffer.alloc(6);
    srvData.writeUInt16BE(0, 0); // Priority
    srvData.writeUInt16BE(0, 2); // Weight
    srvData.writeUInt16BE(port, 4); // Port
    parts.push(srvHeader, srvData, hostTarget);

    // 3. TXT record: id, name, platform=WINDOWS_PC, wsPort, httpPort
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
    txtHeader.writeUInt16BE(16, 0); // Type TXT
    txtHeader.writeUInt16BE(0x8001, 2);
    txtHeader.writeUInt32BE(120, 4);
    txtHeader.writeUInt16BE(txtLength, 8);
    parts.push(txtHeader, Buffer.concat(txtBufs));

    // 4. A record: hostname.local -> IPv4 address
    parts.push(encodeDnsName(`${HOSTNAME}.local`));
    const aHeader = Buffer.alloc(10);
    aHeader.writeUInt16BE(1, 0); // Type A
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
    bufs.push(Buffer.from([0])); // null terminator
    return Buffer.concat(bufs);
}

startMdnsAdvertiser();

console.log('----------------------------------------------------');
console.log('✓ CarCast Windows Receiver is READY');
console.log('  1. On your Android phone, select Sender Mode.');
console.log(`  2. Tap "${RECEIVER_NAME}" in "Nearby CarCast Receivers"`);
console.log('  3. Enjoy high-definition 60 FPS mirroring on PC!');
console.log('----------------------------------------------------');
