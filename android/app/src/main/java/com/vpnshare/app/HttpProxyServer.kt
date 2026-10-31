package com.vpnshare.app

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HttpProxyServer(
    private val port: Int = 8080,
    private val onStatsUpdated: ((activeConns: Int, bytesTx: Long, bytesRx: Long) -> Unit)? = null
) {
    private val tag = "HttpProxyServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()

    val totalBytesSent = AtomicLong(0)
    val totalBytesReceived = AtomicLong(0)
    val activeConnections = AtomicLong(0)

    fun start() {
        if (isRunning.getAndSet(true)) return

        threadPool.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                Log.i(tag, "HTTP Proxy listening on 0.0.0.0:$port")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        clientSocket.tcpNoDelay = true
                        threadPool.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(tag, "Accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Server socket error on port $port: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        val currentConns = activeConnections.incrementAndGet()
        notifyStats()

        var remoteSocket: Socket? = null
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // Read first line of HTTP request (e.g., "CONNECT example.com:443 HTTP/1.1")
            val requestLine = readLine(clientIn) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                // HTTPS Tunneling
                val hostPort = target.split(":")
                val host = hostPort[0]
                val remotePort = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

                // Skip headers up to empty line
                while (true) {
                    val line = readLine(clientIn) ?: break
                    if (line.isEmpty() || line == "\r") break
                }

                // Connect to remote host (outgoing socket goes through Android's active VPN tunnel)
                remoteSocket = Socket()
                remoteSocket.tcpNoDelay = true
                remoteSocket.connect(InetSocketAddress(host, remotePort), 10000)

                // Send 200 Connection Established to client
                val resp = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: VpnShare/1.0\r\n\r\n"
                clientOut.write(resp.toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                // Bidirectional relay
                relayBidirectional(clientSocket, remoteSocket)
            } else {
                // Plain HTTP request (GET, POST, etc.)
                var host = ""
                var remotePort = 80

                // Parse Host header or target URL
                if (target.startsWith("http://", ignoreCase = true)) {
                    val uriWithoutScheme = target.substring(7)
                    val slashIdx = uriWithoutScheme.indexOf('/')
                    val hostPort = if (slashIdx != -1) uriWithoutScheme.substring(0, slashIdx) else uriWithoutScheme
                    val hpParts = hostPort.split(":")
                    host = hpParts[0]
                    if (hpParts.size > 1) remotePort = hpParts[1].toIntOrNull() ?: 80
                }

                val headerLines = mutableListOf<String>()
                while (true) {
                    val line = readLine(clientIn) ?: break
                    if (line.isEmpty() || line == "\r") break
                    headerLines.add(line)
                    if (host.isEmpty() && line.startsWith("Host:", ignoreCase = true)) {
                        val hostVal = line.substring(5).trim()
                        val hpParts = hostVal.split(":")
                        host = hpParts[0]
                        if (hpParts.size > 1) remotePort = hpParts[1].toIntOrNull() ?: 80
                    }
                }

                if (host.isEmpty()) return

                remoteSocket = Socket()
                remoteSocket.tcpNoDelay = true
                remoteSocket.connect(InetSocketAddress(host, remotePort), 10000)
                val remoteOut = remoteSocket.getOutputStream()

                // Rewrite request line to relative path if absolute
                var relativeTarget = target
                if (target.startsWith("http://", ignoreCase = true)) {
                    val uriWithoutScheme = target.substring(7)
                    val slashIdx = uriWithoutScheme.indexOf('/')
                    relativeTarget = if (slashIdx != -1) uriWithoutScheme.substring(slashIdx) else "/"
                }

                val newReqLine = "$method $relativeTarget ${parts.getOrElse(2) { "HTTP/1.1" }}\r\n"
                remoteOut.write(newReqLine.toByteArray(Charsets.ISO_8859_1))
                for (h in headerLines) {
                    remoteOut.write("$h\r\n".toByteArray(Charsets.ISO_8859_1))
                }
                remoteOut.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                remoteOut.flush()

                relayBidirectional(clientSocket, remoteSocket)
            }
        } catch (e: Exception) {
            // Socket closed or connection failed
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket?.close() } catch (_: Exception) {}
            activeConnections.decrementAndGet()
            notifyStats()
        }
    }

    private fun relayBidirectional(s1: Socket, s2: Socket) {
        val in1 = s1.getInputStream()
        val out1 = s1.getOutputStream()
        val in2 = s2.getInputStream()
        val out2 = s2.getOutputStream()

        val thread1 = Thread {
            pipeStream(in1, out2, isUpload = true)
            try { s2.shutdownOutput() } catch (_: Exception) {}
        }
        val thread2 = Thread {
            pipeStream(in2, out1, isUpload = false)
            try { s1.shutdownOutput() } catch (_: Exception) {}
        }

        thread1.start()
        thread2.start()

        try { thread1.join() } catch (_: Exception) {}
        try { thread2.join() } catch (_: Exception) {}
    }

    private fun pipeStream(input: InputStream, output: OutputStream, isUpload: Boolean) {
        val buffer = ByteArray(16384)
        try {
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
                output.flush()
                if (isUpload) {
                    totalBytesSent.addAndGet(len.toLong())
                } else {
                    totalBytesReceived.addAndGet(len.toLong())
                }
                notifyStats()
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(inputStream: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (inputStream.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                sb.append(c.toChar())
            }
        }
        if (sb.isEmpty() && c == -1) return null
        return sb.toString()
    }

    private fun notifyStats() {
        onStatsUpdated?.invoke(
            activeConnections.get().toInt(),
            totalBytesSent.get(),
            totalBytesReceived.get()
        )
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        notifyStats()
    }
}
