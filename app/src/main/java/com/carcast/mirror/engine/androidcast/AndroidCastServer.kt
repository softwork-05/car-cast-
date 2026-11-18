package com.carcast.mirror.engine.androidcast

import android.content.Context
import android.util.Log
import com.carcast.mirror.model.ConnectedClient
import com.carcast.mirror.model.DeviceType
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

interface AndroidCastListener {
    fun onClientConnected(client: ConnectedClient)
    fun onStreamStarted(client: ConnectedClient)
    fun onStreamMeta(width: Int, height: Int)
    fun onVideoData(data: ByteArray, offset: Int, length: Int, ptsUs: Long)
    fun onAudioData(data: ByteArray, offset: Int, length: Int)
    fun onClientDisconnected()
}

class AndroidCastServer(
    private val context: Context,
    private val port: Int = 8080,
    private val listener: AndroidCastListener
) {

    companion object {
        private const val TAG = "CarCast_AndroidServer"
    }

    private var wsServer: WsInternalServer? = null
    private var httpServerThread: Thread? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. Start WebSocket Receiver for streaming binary video frames & bidirectional touch
        try {
            wsServer = WsInternalServer(InetSocketAddress(port), listener).apply {
                isReuseAddr = true
                start()
            }
            Log.i(TAG, "Android WebSocket Cast Receiver started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server on port $port", e)
        }

        // 2. Start Lightweight HTTP Server on port 8088 to serve instant APK download & pair portal
        startHttpPairingServer(8088)
    }

    fun requestKeyframe() {
        wsServer?.requestKeyframe()
    }

    fun sendTouchEvent(action: String, xRatio: Float, yRatio: Float) {
        val jsonPayload = """{"type":"touch","action":"$action","x":$xRatio,"y":$yRatio,"time":${System.currentTimeMillis()}}"""
        wsServer?.broadcastTouch(jsonPayload)
    }

    private fun startHttpPairingServer(httpPort: Int) {
        httpServerThread = Thread({
            try {
                val serverSocket = ServerSocket(httpPort).apply { reuseAddress = true }
                Log.i(TAG, "Android Web Fast-Pair portal active at http://*:$httpPort")

                while (isRunning) {
                    val client = serverSocket.accept()
                    Thread({
                        handleHttpRequest(client)
                    }, "CarCast_HttpWorker").start()
                }
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "HTTP pairing portal closed: ${e.message}")
            }
        }, "CarCast_HttpPairServer").apply { start() }
    }

    private fun handleHttpRequest(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            val output: OutputStream = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val uri = if (parts.size > 1) parts[1] else "/"

            // Serve APK download directly from Car LCD storage!
            if (uri.startsWith("/download") || uri.endsWith(".apk")) {
                val apkPath = context.packageCodePath
                val apkFile = File(apkPath)
                if (apkFile.exists()) {
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.android.package-archive\r\n" +
                            "Content-Length: ${apkFile.length()}\r\n" +
                            "Content-Disposition: attachment; filename=\"CarCastMirror.apk\"\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    FileInputStream(apkFile).use { fis ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                    output.flush()
                    return
                }
            }

            // Otherwise, serve Fast-Pair web page
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
                    <title>CarCast Mirror - Android Wireless Fast-Pair</title>
                    <style>
                        body { background: #0D1117; color: #F0F6FC; font-family: -apple-system, sans-serif; text-align: center; padding: 20px; margin: 0; }
                        .card { background: #161B22; border: 1px solid #30363D; border-radius: 16px; padding: 24px; max-width: 440px; margin: 15px auto; box-shadow: 0 4px 20px rgba(0,0,0,0.5); }
                        .badge { display: inline-block; background: rgba(57,211,83,0.15); color: #39D353; border: 1px solid #39D353; border-radius: 20px; padding: 4px 12px; font-size: 11px; font-weight: bold; margin-bottom: 12px; }
                        h1 { color: #00E5FF; font-size: 22px; margin: 0 0 10px 0; }
                        p { color: #8B949E; font-size: 13px; line-height: 1.5; margin-bottom: 16px; }
                        .alert-box { background: rgba(0,229,255,0.08); border-left: 3px solid #00E5FF; padding: 12px 14px; text-align: left; border-radius: 6px; margin: 14px 0; font-size: 12px; color: #C9D1D9; line-height: 1.4; }
                        .btn-dl { display: block; text-decoration: none; background: #39D353; color: #0D1117; font-size: 16px; font-weight: bold; padding: 16px 24px; border-radius: 30px; margin-top: 15px; box-shadow: 0 0 20px rgba(57,211,83,0.4); }
                        .btn-cast { background: #00E5FF; color: #0D1117; border: none; font-size: 15px; font-weight: bold; padding: 14px 28px; border-radius: 30px; cursor: pointer; margin-top: 10px; width: 100%; }
                        .status { margin-top: 15px; font-size: 13px; color: #8B949E; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="badge">HOTSPOT READY (NO SMART VIEW RESTRICTIONS)</div>
                        <h1>CarCast Mirror</h1>
                        <p>Mirror your Android screen to your Car LCD without turning off your Mobile Hotspot!</p>
                        
                        <div class="alert-box">
                            📱 <b>Chrome Notice:</b> Mobile Chrome restricts screen capture on plain IP addresses.<br><br>
                            👉 <b>Recommended:</b> Download the 1-click Mobile Mirror app directly from your Car LCD below. It runs at 60 FPS with <b>Hotspot ON</b> and enables <b>Car Screen Touch Control</b>!
                        </div>

                        <a href="/download" class="btn-dl">📥 Download Android Mirror App</a>
                        <div style="font-size: 11px; color: #8B949E; margin-top: 8px;">Direct download from Car LCD (No Internet Required)</div>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: ${html.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    html

            output.write(response.toByteArray())
            output.flush()
        } catch (ignored: Exception) {
        } finally {
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try {
            wsServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WebSocket server", e)
        }
        wsServer = null
        httpServerThread?.interrupt()
        httpServerThread = null
    }

    private class WsInternalServer(
        address: InetSocketAddress,
        private val listener: AndroidCastListener
    ) : WebSocketServer(address) {

        private var activeClient: ConnectedClient? = null
        private var activeConn: WebSocket? = null
        private var hasStreamStarted = false

        fun broadcastTouch(json: String) {
            try {
                activeConn?.send(json)
            } catch (ignored: Exception) {}
        }

        fun requestKeyframe() {
            try {
                activeConn?.send("""{"type":"request_keyframe"}""")
                Log.i(TAG, "Sent sync keyframe request to sender")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send keyframe request: ${e.message}")
            }
        }

        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            val ip = conn?.remoteSocketAddress?.address?.hostAddress ?: "Android Device"
            Log.i(TAG, "Android Cast WebSocket connected from $ip")

            activeConn = conn
            hasStreamStarted = false
            activeClient = ConnectedClient(
                deviceName = "Android ($ip)",
                deviceType = DeviceType.ANDROID_CAST,
                ipAddress = ip
            )
            // Notify client connected, but DO NOT declare stream started until video frames actually flow!
            activeClient?.let {
                listener.onClientConnected(it)
            }
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Android Cast WebSocket disconnected: $reason")
            activeConn = null
            activeClient = null
            hasStreamStarted = false
            listener.onClientDisconnected()
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            if (message == null) return
            Log.d(TAG, "Control message from sender: $message")
            try {
                if (message.contains("meta")) {
                    val widthMatch = Regex(""""width"\s*:\s*(\d+)""").find(message)
                    val heightMatch = Regex(""""height"\s*:\s*(\d+)""").find(message)
                    val w = widthMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val h = heightMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    if (w > 0 && h > 0) {
                        Log.i(TAG, "Received stream metadata: ${w}x${h}")
                        listener.onStreamMeta(w, h)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse control message: ${e.message}")
            }
        }

        override fun onMessage(conn: WebSocket?, bytes: ByteBuffer?) {
            if (bytes == null) return
            val length = bytes.remaining()
            if (length == 0) return

            val data = ByteArray(length)
            bytes.get(data)

            // Demux audio packets: Tagged with "AU" prefix (ASCII 'A' and 'U')
            if (length > 2 && data[0] == 'A'.code.toByte() && data[1] == 'U'.code.toByte()) {
                listener.onAudioData(data, 2, length - 2)
                return
            }

            // Only transition to STREAMING once frames are actually received!
            if (!hasStreamStarted) {
                hasStreamStarted = true
                activeClient?.let {
                    Log.i(TAG, "First video frame received (${length} bytes). Marking stream started!")
                    listener.onStreamStarted(it)
                }
            }

            val ptsUs = System.nanoTime() / 1000L
            listener.onVideoData(data, 0, length, ptsUs)
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            Log.e(TAG, "WebSocket error", ex)
        }

        override fun onStart() {
            Log.i(TAG, "WebSocket server is active on $port")
        }
    }
}
