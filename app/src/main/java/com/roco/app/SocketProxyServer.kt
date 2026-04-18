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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local WebSocket-to-TCP proxy server.
 * Runs on the Android device, bridges Ruffle's WebSocket connections to real TCP game servers.
 *
 * Flow: Ruffle (WASM) --[WebSocket]--> this proxy --[TCP Socket]--> game server (172.25.*:9000)
 */
class SocketProxyServer(private val port: Int) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "SocketProxyServer"
    }

    private val connections = CopyOnWriteArrayList<TcpBridge>()
    private val executor = Executors.newCachedThreadPool()

    override fun onStart() {
        Log.d(TAG, "WebSocket proxy server started on port $port")
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "WebSocket connected from ${conn?.remoteSocketAddress}")

        // Parse target from query string: ws://127.0.0.1:8765/?host=172.25.40.120&port=9000
        val resourceDescriptor = conn?.resourceDescriptor ?: return
        val query = resourceDescriptor.substringAfter("?", "")
        val params = query.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()

        val targetHost = params["host"] ?: "172.25.40.120"
        val targetPort = (params["port"] ?: "9000").toInt()

        Log.d(TAG, "Bridging to TCP $targetHost:$targetPort")

        try {
            val tcpSocket = Socket()
            tcpSocket.soTimeout = 35000 // 35 second timeout (matches game protocol)
            tcpSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)
            Log.d(TAG, "TCP connected to $targetHost:$targetPort")

            val bridge = TcpBridge(conn!!, tcpSocket)
            connections.add(bridge)

            executor.submit { bridge.readTcpToWs() }
            executor.submit { bridge.readWsToTcp() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect TCP $targetHost:$targetPort: ${e.message}")
            conn.close(1014, "TCP connection failed: ${e.message}")
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "WebSocket closed: code=$code reason=$reason remote=$remote")
        connections.removeAll { it.ws == conn }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        // Text messages not expected, but log them
        Log.d(TAG, "WebSocket text message (ignored): ${message?.take(100)}")
    }

    override fun onMessage(conn: WebSocket?, message: ByteArray?) {
        if (message == null) return
        val bridge = connections.find { it.ws == conn }
        if (bridge != null) {
            try {
                bridge.tcpOutputStream.write(message)
                bridge.tcpOutputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to TCP: ${e.message}")
                conn.close(1011, "TCP write error")
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error: ${ex?.message}", ex)
    }

    fun shutdown() {
        connections.forEach { it.close() }
        connections.clear()
        stop()
        executor.shutdown()
        Log.d(TAG, "Proxy server shut down")
    }

    /**
     * Bridges a single WebSocket connection to a single TCP socket.
     */
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
                Log.w(TAG, "TCP read timeout (normal for idle connection)")
            } catch (e: Exception) {
                Log.e(TAG, "TCP read error: ${e.message}")
            } finally {
                Log.d(TAG, "TCP read stream ended, closing bridge")
                close()
            }
        }

        fun readWsToTcp() {
            // Handled by onMessage callback above
        }

        fun close() {
            try { tcpSocket.close() } catch (_: Exception) {}
            try { ws.close(1000, "Bridge closed") } catch (_: Exception) {}
            connections.remove(this)
        }
    }
}
