package com.carcast.mirror.engine.airplay

import android.util.Log
import com.carcast.mirror.model.ConnectedClient
import com.carcast.mirror.model.DeviceType
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

class AirPlayRtspServer(
    private val port: Int = 7000,
    private val deviceMac: String = "A4:C1:38:E1:B9:20",
    private val listener: AirPlayStreamListener
) {

    companion object {
        private const val TAG = "CarCast_AirPlayRTSP"
        private const val VIDEO_RTP_PORT = 7100
        private const val AUDIO_RTP_PORT = 7200
        private const val PORT_RAOP_5000 = 5000
    }

    private var serverSocket7000: ServerSocket? = null
    private var serverSocket5000: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    private var videoUdpSocket: DatagramSocket? = null
    private var audioUdpSocket: DatagramSocket? = null
    private val isStreaming = AtomicBoolean(false)
    @Volatile
    private var activeStreamingSocket: Socket? = null

    @Volatile private var listening7000 = false
    @Volatile private var listening5000 = false

    private fun reportListeningState() {
        AirPlayDebugLogger.setServerListening(
            listening7000 && listening5000,
            "7000=${if (listening7000) "OK" else "OFF"}, 5000=${if (listening5000) "OK" else "OFF"}"
        )
    }

    private class ClientSession {
        val pairVerify = AirPlayCrypto.instance.newPairVerifySession()
        var authenticated = false
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        // 1. Primary AirPlay RTSP Server on Port 7000
        Thread {
            try {
                serverSocket7000 = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                listening7000 = true
                reportListeningState()
                AirPlayDebugLogger.log("[SERVER:7000] Listening on TCP port $port (SO_REUSEADDR=true)")
                Log.i(TAG, "AirPlay RTSP Server active on port $port (DeviceID: $deviceMac)")

                while (isRunning.get()) {
                    val clientSocket = serverSocket7000?.accept() ?: break
                    Thread {
                        handleClient(clientSocket, port)
                    }.start()
                }
            } catch (e: Exception) {
                listening7000 = false
                reportListeningState()
                AirPlayDebugLogger.log("[ERROR:7000] Socket bind/accept failure on port $port: ${e.javaClass.simpleName} - ${e.message}")
                if (isRunning.get()) Log.e(TAG, "AirPlay RTSP Server error on port $port", e)
            }
        }.start()

        // 2. Persistent RTSP Session Handler on Port 5000 (AirPlay/RAOP Control Channel)
        Thread {
            try {
                serverSocket5000 = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_RAOP_5000))
                }
                listening5000 = true
                reportListeningState()
                AirPlayDebugLogger.log("[SERVER:5000] Listening on TCP port $PORT_RAOP_5000 (SO_REUSEADDR=true)")
                Log.i(TAG, "RAOP RTSP Server active on port $PORT_RAOP_5000")

                while (isRunning.get()) {
                    val clientSocket = serverSocket5000?.accept() ?: break
                    Thread {
                        handleClient(clientSocket, PORT_RAOP_5000)
                    }.start()
                }
            } catch (e: Exception) {
                listening5000 = false
                reportListeningState()
                AirPlayDebugLogger.log("[ERROR:5000] Failed to bind Port $PORT_RAOP_5000: ${e.message}")
            }
        }.start()
    }

    private fun handleClient(socket: Socket, serverPort: Int) {
        socket.soTimeout = 30_000
        val session = ClientSession()
        val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
        val clientPort = socket.port
        AirPlayDebugLogger.log("[TCP ACCEPT:$serverPort] Connected from $clientIp:$clientPort")
        Log.i(TAG, "Incoming iOS AirPlay handshake from $clientIp:$clientPort on port $serverPort")

        val client = ConnectedClient(
            deviceName = "iPhone",
            deviceType = DeviceType.IOS_AIRPLAY,
            ipAddress = clientIp
        )

        try {
            val input: InputStream = socket.getInputStream()
            val output: OutputStream = socket.getOutputStream()

            while (isRunning.get() && !socket.isClosed) {
                // Read HTTP/RTSP request line
                val requestLine = readLine(input)
                if (requestLine == null) {
                    AirPlayDebugLogger.log("[TCP DROP:$serverPort] $clientIp disconnected (EOF)")
                    break
                }
                if (requestLine.isEmpty()) continue

                val parts = requestLine.split(" ")
                val method = parts[0]
                val uri = if (parts.size > 1) parts[1] else "*"

                var cseq = "1"
                var contentLength = 0
                val headers = mutableMapOf<String, String>()

                // Read headers
                while (true) {
                    val line = readLine(input) ?: throw EOFException("EOF inside RTSP headers")
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(":")
                    if (colonIdx > 0) {
                        val key = line.substring(0, colonIdx).trim()
                        val value = line.substring(colonIdx + 1).trim()
                        headers[key.lowercase(Locale.ROOT)] = value
                        if (key.equals("CSeq", ignoreCase = true)) cseq = value
                        if (key.equals("Content-Length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull()
                                ?: throw IOException("Invalid Content-Length header")
                        }
                    }
                }

                if (contentLength < 0 || contentLength > 2_097_152) {
                    AirPlayDebugLogger.log("[ERROR:$serverPort] Invalid Content-Length: $contentLength")
                    sendResponse(output, cseq, "413 Payload Too Large")
                    break
                }
                AirPlayDebugLogger.log("[REQ:$serverPort] $clientIp -> $method $uri (CSeq: $cseq, Len: $contentLength)")

                // Read binary body without logging sensitive payload content
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = input.read(buf, read, contentLength - read)
                        if (r == -1) throw EOFException("Request body ended at $read of $contentLength bytes")
                        read += r
                    }
                    buf
                } else ByteArray(0)

                // Dispatch method
                handleRequest(method, uri, cseq, headers, body, output, client, socket, serverPort, session)
            }
        } catch (e: Exception) {
            AirPlayDebugLogger.log("[DISCONNECT:$serverPort] $clientIp: ${e.message}")
            Log.w(TAG, "AirPlay socket session closed: ${e.message}")
        } finally {
            AirPlayDebugLogger.log("[TCP CLOSE:$serverPort] Closed for $clientIp")
            if (activeStreamingSocket == socket) {
                activeStreamingSocket = null
                stopRtpReceivers()
                listener.onClientDisconnected()
            }
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    private fun handleRequest(
        method: String,
        uri: String,
        cseq: String,
        headers: Map<String, String>,
        body: ByteArray,
        output: OutputStream,
        client: ConnectedClient,
        socket: Socket,
        serverPort: Int,
        session: ClientSession
    ) {
        when {
            method == "OPTIONS" -> {
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Public: ANNOUNCE, SETUP, RECORD, PAUSE, TEARDOWN, FLUSH, GET_PARAMETER, SET_PARAMETER, POST, GET\r\n" +
                        "Server: AirTunes/220.68\r\n\r\n"
                output.write(resp.toByteArray())
                output.flush()
                AirPlayDebugLogger.log("[STAGE:OPTIONS:$serverPort] 200 OK (CSeq: $cseq)")
            }

            method == "GET" && (uri.contains("/info") || uri.contains("/stream.xml") || uri.contains("/server-info")) -> {
                val crypto = AirPlayCrypto.instance

                // Generate Apple Binary Property List (bplist00)
                val dict = com.dd.plist.NSDictionary().apply {
                    put("deviceid", deviceMac)
                    put("deviceID", deviceMac)
                    put("features", com.dd.plist.NSNumber(1384054502L)) // 0x527FFEE6
                    put("model", "AppleTV3,2")
                    put("protovers", "1.0")
                    put("srcvers", "220.68")
                    put("statusFlags", com.dd.plist.NSNumber(68))
                    put("pi", "b08ab08a-02c0-4820-9426-0e1371800a72")
                    put("pk", com.dd.plist.NSData(crypto.publicKeyBytes))
                    put("vv", com.dd.plist.NSNumber(2))
                }

                val bplistBytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)
                val resp = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cseq\r\n" +
                        "Server: AirTunes/220.68\r\n" +
                        "Content-Type: application/x-apple-binary-plist\r\n" +
                        "Content-Length: ${bplistBytes.size}\r\n\r\n"
                output.write(resp.toByteArray(StandardCharsets.UTF_8))
                output.write(bplistBytes)
                output.flush()
                AirPlayDebugLogger.log("[STAGE:INFO:$serverPort] Sent bplist00 (${bplistBytes.size}b, CSeq: $cseq)")
            }

            method == "POST" && uri.contains("pair-setup") -> {
                // Legacy transient pairing replies with the server's 32-byte Ed25519 public key.
                // The old empty 200 response was not a valid pair-setup implementation.
                if (body.size == 32) {
                    sendResponse(output, cseq, body = AirPlayCrypto.instance.publicKeyBytes,
                        contentType = "application/octet-stream")
                    AirPlayDebugLogger.log("[STAGE:PAIR-SETUP:$serverPort] Sent server public key (32b)")
                } else {
                    sendResponse(output, cseq, "400 Bad Request")
                    AirPlayDebugLogger.log("[STAGE:PAIR-SETUP:$serverPort] Unsupported/malformed setup (${body.size}b)")
                }
            }

            method == "POST" && uri.contains("pair-verify") -> {
                if (body.size == 68 && body[0] == 1.toByte()) {
                    val response = session.pairVerify.handleStage1(body)
                    if (response == null) {
                        sendResponse(output, cseq, "470 Connection Authorization Required")
                        AirPlayDebugLogger.log("[STAGE:PAIR-VERIFY-1:$serverPort] FAILED")
                    } else {
                        sendResponse(output, cseq, body = response, contentType = "application/octet-stream")
                        AirPlayDebugLogger.log("[STAGE:PAIR-VERIFY-1:$serverPort] Encrypted signature sent (96b)")
                    }
                } else {
                    session.authenticated = session.pairVerify.handleStage2(body)
                    if (session.authenticated) {
                        sendResponse(output, cseq)
                        AirPlayDebugLogger.log("[STAGE:PAIR-VERIFY-2:$serverPort] Client signature VERIFIED")
                        listener.onClientConnected(client)
                    } else {
                        sendResponse(output, cseq, "470 Connection Authorization Required")
                        AirPlayDebugLogger.log("[STAGE:PAIR-VERIFY-2:$serverPort] Verification FAILED")
                    }
                }
            }

            method == "POST" && uri.contains("fp-setup") -> {
                // CRITICAL: The old implementation fabricated a 142/32-byte FPLY response
                // (mostly zeros). It never performed FairPlay and made iOS disconnect.
                // Do not claim 200 OK until a legitimate native FairPlay backend is integrated.
                AirPlayDebugLogger.log("[UNSUPPORTED:FP-SETUP:$serverPort] FairPlay engine missing; ${body.size}b received")
                sendResponse(output, cseq, "501 Not Implemented")
            }

            method == "SETUP" -> {
                if (!session.authenticated) {
                    sendResponse(output, cseq, "470 Connection Authorization Required")
                    AirPlayDebugLogger.log("[STAGE:SETUP:$serverPort] Blocked: pair-verify incomplete")
                    return
                }
                // The packet receiver is an RTP placeholder, NOT an AirPlay mirroring decoder.
                // Do not claim a successful stream before valid negotiated transport and crypto exist.
                sendResponse(output, cseq, "501 Not Implemented")
                AirPlayDebugLogger.log("[UNSUPPORTED:SETUP:$serverPort] Media transport/crypto backend missing")
                return
            }

            method == "RECORD" -> {
                sendResponse(output, cseq, "455 Method Not Valid in This State")
                AirPlayDebugLogger.log("[UNSUPPORTED:RECORD:$serverPort] No negotiated media session")
            }

            method == "TEARDOWN" -> {
                val resp = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n"
                output.write(resp.toByteArray())
                output.flush()
                AirPlayDebugLogger.log("[STAGE:TEARDOWN:$serverPort] Stream teardown (CSeq: $cseq)")
                activeStreamingSocket = null
                stopRtpReceivers()
                listener.onClientDisconnected()
            }

            method == "SET_PARAMETER" || method == "GET_PARAMETER" || method == "ANNOUNCE" || method == "FLUSH" || method == "PAUSE" -> {
                sendResponse(output, cseq, "455 Method Not Valid in This State")
                AirPlayDebugLogger.log("[UNSUPPORTED:$serverPort] $method requires a negotiated media session")
            }

            else -> {
                sendResponse(output, cseq, "501 Not Implemented")
                AirPlayDebugLogger.log("[UNSUPPORTED:$serverPort] $method $uri -> 501")
            }
        }
    }

    private fun sendResponse(
        output: OutputStream,
        cseq: String,
        status: String = "200 OK",
        body: ByteArray = ByteArray(0),
        contentType: String? = null
    ) {
        val headers = buildString {
            append("RTSP/1.0 $status\r\nCSeq: $cseq\r\nServer: CarCastMirror\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (sb.length > 8192) throw IOException("RTSP line too long")
            if (c == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code || next == -1) break
                sb.append(c.toChar())
                if (next != -1) sb.append(next.toChar())
            } else if (c == '\n'.code) {
                break
            } else {
                sb.append(c.toChar())
            }
        }
        return if (sb.isEmpty() && c == -1) null else sb.toString()
    }

    private fun startRtpReceivers() {
        if (isStreaming.getAndSet(true)) return

        // 1. Video RTP Receiver on port 7100
        Thread {
            try {
                videoUdpSocket = DatagramSocket(VIDEO_RTP_PORT).apply { reuseAddress = true }
                val buffer = ByteArray(65536)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isStreaming.get()) {
                    videoUdpSocket?.receive(packet)
                    val len = packet.length
                    if (len > 12) {
                        listener.onVideoFrame(buffer, 12, len - 12, System.nanoTime() / 1000)
                    }
                }
            } catch (e: Exception) {
                if (isStreaming.get()) Log.w(TAG, "Video RTP error: ${e.message}")
            }
        }.start()

        // 2. Audio RTP Receiver on port 7200
        Thread {
            try {
                audioUdpSocket = DatagramSocket(AUDIO_RTP_PORT).apply { reuseAddress = true }
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isStreaming.get()) {
                    audioUdpSocket?.receive(packet)
                    val len = packet.length
                    if (len > 12) {
                        listener.onAudioFrame(buffer, 12, len - 12)
                    }
                }
            } catch (e: Exception) {
                if (isStreaming.get()) Log.w(TAG, "Audio RTP error: ${e.message}")
            }
        }.start()
    }

    private fun stopRtpReceivers() {
        isStreaming.set(false)
        try { videoUdpSocket?.close() } catch (ignored: Exception) {}
        try { audioUdpSocket?.close() } catch (ignored: Exception) {}
        videoUdpSocket = null
        audioUdpSocket = null
    }

    fun stop() {
        isRunning.set(false)
        listening7000 = false
        listening5000 = false
        stopRtpReceivers()
        try { serverSocket7000?.close() } catch (e: Exception) { Log.w(TAG, "Error closing 7000", e) }
        try { serverSocket5000?.close() } catch (e: Exception) { Log.w(TAG, "Error closing 5000", e) }
        serverSocket7000 = null
        serverSocket5000 = null
        AirPlayDebugLogger.setServerListening(false, "OFFLINE")
        AirPlayDebugLogger.log("[SERVER] Ports 7000 & 5000 closed")
    }
}
