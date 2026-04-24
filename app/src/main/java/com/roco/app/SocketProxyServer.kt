package com.roco.app

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Local WebSocket-to-TCP proxy server with TGW (Tencent Gateway) support.
 *
 * Flow: Ruffle (WASM) --[WebSocket]--> this proxy --[TCP]--> TGW gateway --[tunnel]--> game server
 *
 * When connecting to TGW, the proxy automatically injects the TGW L7 forward command
 * after TCP connection is established, before forwarding game data.
 */
class SocketProxyServer(
    private val listenPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val tgwZone: String? = null  // e.g. "zone5", "zone6", null = no TGW
) : WebSocketServer(InetSocketAddress("127.0.0.1", listenPort)) {

    companion object {
        private const val TAG = "SocketProxy"
    }

    private val connections = CopyOnWriteArrayList<TcpBridge>()
    private val executor = Executors.newCachedThreadPool()
    private var toastMessage: String? = null
    private var tcpRetryCount = 3
    private var tcpConnectTimeout = 10000

    fun setToastMessage(msg: String) {
        toastMessage = msg
    }

    fun getToastMessage(): String? = toastMessage

    override fun onStart() {
        val tgwInfo = if (tgwZone != null) " (TGW $tgwZone)" else ""
        Log.d(TAG, "Proxy listening on ws://127.0.0.1:$listenPort -> $targetHost:$targetPort$tgwInfo")
        toastMessage = "Proxy $listenPort -> $targetHost:$targetPort$tgwInfo ready"
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "WebSocket connected on port $listenPort")
        // Don't connect TCP yet - wait for first message to determine if stats or game
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "WebSocket closed on port $listenPort: code=$code reason=$reason")
        connections.removeAll { it.ws == conn }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        Log.d(TAG, "WS text on port $listenPort: ${message?.take(80)}")
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        if (message == null || conn == null) return

        var bytes = ByteArray(message.remaining())
        message.get(bytes)
        Log.d(TAG, "WS->TCP on port $listenPort: ${bytes.size} bytes, first 20: ${bytes.take(20).map { String.format("%02x", it) }.joinToString(" ")}")

        // Check if this is a TGW handshake (first message)
        if (bytes.size >= 4 && bytes[0] == 't'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'w'.code.toByte()) {
            val original = String(bytes, Charsets.UTF_8)
            Log.d(TAG, "Intercepted TGW: ${original.replace("\r", "\\r").replace("\n", "\\n")}")

            // All connections: establish TCP to TGW with retry
            // (Both stats and game connections go through TGW)
            var tcpSocket: Socket? = null
            var lastError: Exception? = null
            for (attempt in 1..tcpRetryCount) {
                try {
                    Log.d(TAG, "TCP connect attempt $attempt/$tcpRetryCount to $targetHost:$targetPort")
                    val socket = Socket()
                    socket.soTimeout = 60000
                    socket.reuseAddress = true
                    socket.connect(InetSocketAddress(targetHost, targetPort), tcpConnectTimeout)
                    tcpSocket = socket
                    Log.d(TAG, "TCP connected to $targetHost:$targetPort (attempt $attempt)")
                    toastMessage = "TCP connected to $targetHost:$targetPort"
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "TCP connect attempt $attempt/$tcpRetryCount failed: ${e.message}")
                    if (attempt < tcpRetryCount) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                    }
                }
            }

            if (tcpSocket == null) {
                Log.e(TAG, "TCP failed after $tcpRetryCount attempts: ${lastError?.message}")
                conn.close(1014, "TCP connect failed: ${lastError?.message}")
                return
            }

            // Rewrite Host header
            val rewritten = original.replaceFirst(Regex("Host:[^\r\n]*"), "Host: $tgwZone.17roco.qq.com:$targetPort")
            val rewrittenBytes = rewritten.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "Rewritten TGW Host to: $tgwZone.17roco.qq.com:$targetPort")

            tcpSocket.getOutputStream().write(rewrittenBytes)
            tcpSocket.getOutputStream().flush()

            val bridge = TcpBridge(conn, tcpSocket)
            connections.add(bridge)
            executor.submit { bridge.readTcpToWs() }
            Log.d(TAG, "Game bridge established on port $listenPort")
            return
        }

        // Subsequent messages: forward to existing TCP bridge
        val bridge = connections.find { it.ws == conn }
        if (bridge != null) {
            try {
                bridge.tcpOutputStream.write(bytes)
                bridge.tcpOutputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "TCP write error on port $listenPort: ${e.message}")
                conn.close(1011, "TCP write error")
            }
        } else {
            // No TCP bridge (stats connection) - just ignore data
            Log.d(TAG, "No TCP bridge, ignoring ${bytes.size} bytes (stats data)")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WS error on port $listenPort: ${ex?.message}", ex)
        toastMessage = "WS error: ${ex?.message}"
    }

    fun shutdown() {
        connections.forEach { it.close() }
        connections.clear()
        stop()
        executor.shutdown()
        Log.d(TAG, "Proxy on port $listenPort shut down")
    }

    inner class TcpBridge(
        val ws: WebSocket,
        val tcpSocket: Socket
    ) {
        val tcpOutputStream: OutputStream = tcpSocket.getOutputStream()
        val tcpInputStream: InputStream = tcpSocket.getInputStream()

        fun readTcpToWs() {
            try {
                val buffer = ByteArray(8192)
                while (!tcpSocket.isClosed && ws.isOpen) {
                    val bytesRead = tcpInputStream.read(buffer)
                    if (bytesRead == -1) break
                    val data = buffer.copyOf(bytesRead)
                    Log.d(TAG, "TCP->WS on port $listenPort: $bytesRead bytes, first 20: ${data.take(20).map { String.format("%02x", it) }.joinToString(" ")}")
                    ws.send(data)
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "TCP read timeout on port $listenPort (idle)")
            } catch (e: Exception) {
                Log.e(TAG, "TCP read error on port $listenPort: ${e.message}")
            } finally {
                Log.d(TAG, "TCP stream ended on port $listenPort")
                close()
            }
        }

        fun close() {
            try { tcpSocket.close() } catch (_: Exception) {}
            try { ws.close(1000, "Bridge closed") } catch (_: Exception) {}
            connections.remove(this)
        }
    }
}
