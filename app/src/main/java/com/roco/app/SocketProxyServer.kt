package com.roco.app

import android.util.Log
import android.widget.Toast
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

/**
 * Local WebSocket-to-TCP proxy server.
 * Each instance bridges one specific host:port pair.
 *
 * Flow: Ruffle (WASM) --[WebSocket]--> this proxy --[TCP Socket]--> game server
 */
class SocketProxyServer(
    private val listenPort: Int,
    private val targetHost: String,
    private val targetPort: Int
) : WebSocketServer(InetSocketAddress("127.0.0.1", listenPort)) {

    companion object {
        private const val TAG = "SocketProxy"
    }

    private val connections = CopyOnWriteArrayList<TcpBridge>()
    private val executor = Executors.newCachedThreadPool()
    private var toastMessage: String? = null

    fun setToastMessage(msg: String) {
        toastMessage = msg
    }

    fun getToastMessage(): String? = toastMessage

    override fun onStart() {
        Log.d(TAG, "Proxy listening on ws://127.0.0.1:$listenPort -> $targetHost:$targetPort")
        toastMessage = "Proxy $listenPort -> $targetHost:$targetPort ready"
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "WebSocket connected on port $listenPort, bridging to $targetHost:$targetPort")
        toastMessage = "🔌 Connecting $targetHost:$targetPort..."

        try {
            val tcpSocket = Socket()
            tcpSocket.soTimeout = 60000 // 60 second timeout
            tcpSocket.connect(InetSocketAddress(targetHost, targetPort), 15000)
            Log.d(TAG, "TCP connected to $targetHost:$targetPort")
            toastMessage = "✅ TCP connected to $targetHost:$targetPort"

            val bridge = TcpBridge(conn!!, tcpSocket)
            connections.add(bridge)

            executor.submit { bridge.readTcpToWs() }
        } catch (e: Exception) {
            val errMsg = "❌ TCP failed $targetHost:$targetPort: ${e.message}"
            Log.e(TAG, errMsg)
            toastMessage = errMsg
            conn?.close(1014, errMsg)
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "WebSocket closed on port $listenPort: code=$code reason=$reason")
        connections.removeAll { it.ws == conn }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        Log.d(TAG, "WS text on port $listenPort (ignored): ${message?.take(50)}")
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        if (message == null || conn == null) return
        val bridge = connections.find { it.ws == conn }
        if (bridge != null) {
            try {
                val bytes = ByteArray(message.remaining())
                message.get(bytes)
                bridge.tcpOutputStream.write(bytes)
                bridge.tcpOutputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "TCP write error on port $listenPort: ${e.message}")
                conn.close(1011, "TCP write error")
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WS error on port $listenPort: ${ex?.message}", ex)
        toastMessage = "❌ WS error: ${ex?.message}"
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
