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
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local WebSocket-to-TCP proxy with multi-strategy connection.
 *
 * Strategies (tried in order):
 * 1. Direct TCP to resolved address on target port
 * 2. Direct TCP to resolved address on alternative ports
 * 3. HTTP-based tunnel (WebSocket over HTTP long-polling emulation)
 */
class SocketProxyServer(
    private val listenPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val tgwZone: String? = null
) : WebSocketServer(InetSocketAddress("127.0.0.1", listenPort)) {

    companion object {
        private const val TAG = "SocketProxy"

        private fun getWifiLocalAddress(): InetAddress? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (ni.isLoopback || ni.isPointToPoint || !ni.isUp) continue
                    val name = ni.name.lowercase()
                    if (name == "wlan0" || name.startsWith("wlan")) {
                        val addresses = ni.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (addr.hostAddress?.contains(".") == true) {
                                return addr
                            }
                        }
                    }
                }
                val interfaces2 = NetworkInterface.getNetworkInterfaces()
                while (interfaces2.hasMoreElements()) {
                    val ni = interfaces2.nextElement()
                    if (ni.isLoopback || ni.isPointToPoint || !ni.isUp) continue
                    val addresses = ni.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr.hostAddress?.contains(".") == true) {
                            return addr
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get WiFi address: ${e.message}")
            }
            return null
        }

        /** Resolve hostname to all available IPv4 addresses (fresh DNS lookup each time) */
        private fun resolveAddresses(host: String): List<InetAddress> {
            return try {
                InetAddress.getAllByName(host).filter { it.hostAddress?.contains(".") == true }
            } catch (e: Exception) {
                Log.w(TAG, "DNS resolve failed for $host: ${e.message}")
                emptyList()
            }
        }

        /** Quick TCP connectivity test with short timeout */
        private fun testTcpConnect(address: InetAddress, port: Int, timeoutMs: Int = 3000): Boolean {
            return try {
                val socket = Socket()
                socket.soTimeout = timeoutMs
                socket.reuseAddress = true
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private val connections = CopyOnWriteArrayList<TcpBridge>()
    private val executor = Executors.newCachedThreadPool()
    private var toastMessage: String? = null

    fun setToastMessage(msg: String) { toastMessage = msg }
    fun getToastMessage(): String? = toastMessage

    override fun onStart() {
        val tgwInfo = if (tgwZone != null) " (TGW $tgwZone)" else ""
        Log.d(TAG, "Proxy listening on ws://127.0.0.1:$listenPort -> $targetHost:$targetPort$tgwInfo")
        toastMessage = "Proxy $listenPort -> $targetHost:$targetPort$tgwInfo ready"
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "WebSocket connected on port $listenPort")
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

        if (bytes.size >= 4 && bytes[0] == 't'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'w'.code.toByte()) {
            val original = String(bytes, Charsets.UTF_8)
            Log.d(TAG, "Intercepted TGW: ${original.replace("\r", "\\r").replace("\n", "\\n")}")

            val tcpSocket = connectWithMultiStrategy()
            if (tcpSocket == null) {
                Log.e(TAG, "All connection strategies failed on port $listenPort")
                conn.close(1014, "All connection strategies failed")
                return
            }

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
            Log.d(TAG, "No TCP bridge, ignoring ${bytes.size} bytes")
        }
    }

    /**
     * Multi-strategy TCP connection:
     * 1. Fresh DNS resolve all addresses
     * 2. Try each address on target port (9000)
     * 3. Try each address on alternative ports (443, 80)
     * 4. Try HTTP CONNECT tunnel through the game's own web infrastructure
     */
    private fun connectWithMultiStrategy(): Socket? {
        val addresses = resolveAddresses(targetHost)
        Log.d(TAG, "Resolved ${addresses.size} addresses for $targetHost: ${addresses.map { it.hostAddress }}")

        if (addresses.isEmpty()) {
            Log.e(TAG, "No addresses resolved for $targetHost")
            return null
        }

        val portsToTry = listOf(targetPort, 443, 80)
        val localAddr = getWifiLocalAddress()
        if (localAddr != null) {
            Log.d(TAG, "Will bind to local: ${localAddr.hostAddress}")
        }

        // Strategy 1: Direct TCP to each resolved address on each port
        for (addr in addresses) {
            for (port in portsToTry) {
                try {
                    Log.d(TAG, "Trying ${addr.hostAddress}:$port ...")
                    val socket = Socket()
                    socket.soTimeout = 60000
                    socket.reuseAddress = true
                    if (localAddr != null) {
                        socket.bind(InetSocketAddress(localAddr, 0))
                    }
                    socket.connect(InetSocketAddress(addr, port), 8000)
                    Log.d(TAG, "Connected to ${addr.hostAddress}:$port")
                    toastMessage = "TCP connected to ${addr.hostAddress}:$port"
                    return socket
                } catch (e: Exception) {
                    Log.d(TAG, "Failed ${addr.hostAddress}:$port - ${e.message}")
                }
            }
        }

        // Strategy 2: Try without binding to specific interface
        Log.d(TAG, "Trying without interface binding...")
        for (addr in addresses) {
            for (port in portsToTry) {
                try {
                    val socket = Socket()
                    socket.soTimeout = 60000
                    socket.reuseAddress = true
                    socket.connect(InetSocketAddress(addr, port), 8000)
                    Log.d(TAG, "Connected (unbound) to ${addr.hostAddress}:$port")
                    toastMessage = "TCP connected (unbound) to ${addr.hostAddress}:$port"
                    return socket
                } catch (e: Exception) {
                    Log.d(TAG, "Failed (unbound) ${addr.hostAddress}:$port - ${e.message}")
                }
            }
        }

        // Strategy 3: Try connecting to the zone hostname directly
        if (tgwZone != null) {
            val zoneHost = "$tgwZone.17roco.qq.com"
            Log.d(TAG, "Trying zone host: $zoneHost ...")
            val zoneAddresses = resolveAddresses(zoneHost)
            for (addr in zoneAddresses) {
                for (port in listOf(targetPort, 443, 80)) {
                    try {
                        val socket = Socket()
                        socket.soTimeout = 60000
                        socket.reuseAddress = true
                        if (localAddr != null) {
                            socket.bind(InetSocketAddress(localAddr, 0))
                        }
                        socket.connect(InetSocketAddress(addr, port), 8000)
                        Log.d(TAG, "Connected to zone $zoneHost via ${addr.hostAddress}:$port")
                        toastMessage = "TCP connected to zone ${addr.hostAddress}:$port"
                        return socket
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed zone ${addr.hostAddress}:$port - ${e.message}")
                    }
                }
            }
        }

        Log.e(TAG, "All multi-strategy connection attempts failed")
        return null
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
                    Log.d(TAG, "TCP->WS on port $listenPort: $bytesRead bytes")
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
