package com.vpnshare.app

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class Socks5ProxyServer(
    private val port: Int = 1080,
    private val onStatsUpdated: ((activeConns: Int, bytesTx: Long, bytesRx: Long) -> Unit)? = null
) {
    private val tag = "Socks5ProxyServer"
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
                Log.i(tag, "SOCKS5 Proxy listening on 0.0.0.0:$port")

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
                Log.e(tag, "Server error on port $port: ${e.message}")
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
            val dis = DataInputStream(clientSocket.getInputStream())
            val dos = DataOutputStream(clientSocket.getOutputStream())

            // 1. Handshake
            val version = dis.readUnsignedByte()
            if (version != 5) {
                return
            }

            val nMethods = dis.readUnsignedByte()
            val methods = ByteArray(nMethods)
            dis.readFully(methods)

            // Select 0x00 (NO AUTHENTICATION REQUIRED)
            dos.writeByte(5) // SOCKS5
            dos.writeByte(0) // No auth
            dos.flush()

            // 2. Client Request
            val reqVersion = dis.readUnsignedByte()
            val cmd = dis.readUnsignedByte()
            val rsv = dis.readUnsignedByte()
            val atyp = dis.readUnsignedByte()

            if (reqVersion != 5 || cmd != 1) { // 1 = CONNECT
                // Command not supported
                dos.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                dos.flush()
                return
            }

            val targetHost: String = when (atyp) {
                1 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    dis.readFully(ipBytes)
                    InetAddress.getByAddress(ipBytes).hostAddress ?: return
                }
                3 -> { // Domain name
                    val len = dis.readUnsignedByte()
                    val domainBytes = ByteArray(len)
                    dis.readFully(domainBytes)
                    String(domainBytes, Charsets.UTF_8)
                }
                4 -> { // IPv6
                    val ipBytes = ByteArray(16)
                    dis.readFully(ipBytes)
                    InetAddress.getByAddress(ipBytes).hostAddress ?: return
                }
                else -> {
                    // Address type not supported
                    dos.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0))
                    dos.flush()
                    return
                }
            }

            val targetPort = dis.readUnsignedShort()

            // Connect to target host through phone's VPN network
            remoteSocket = Socket()
            remoteSocket.tcpNoDelay = true
            remoteSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

            // Success reply: 05 00 00 01 (BND.ADDR = 0.0.0.0, BND.PORT = 0)
            dos.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            dos.flush()

            // Relay data
            relayBidirectional(clientSocket, remoteSocket)
        } catch (_: Exception) {
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
