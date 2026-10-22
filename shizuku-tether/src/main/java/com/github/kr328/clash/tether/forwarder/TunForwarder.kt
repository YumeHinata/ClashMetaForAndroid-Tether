package com.github.kr328.clash.tether.forwarder

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Legacy Kotlin-based TUN forwarder.
 *
 * This class parses IP packets from the TUN interface and forwards TCP connections
 * through a SOCKS5 proxy. It only supports IPv4.
 *
 * Note: The primary data path now uses gVisor via [TetherPathBridge].
 * This class is retained for reference and potential fallback use.
 */
class TunForwarder(
    private val tunFd: ParcelFileDescriptor,
    private val proxyHost: String = "127.0.0.1",
    private val proxyPort: Int = 7890,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false
    private val connections = mutableMapOf<String, Connection>()

    data class Connection(
        val id: String,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val channel: SocketChannel?,
    )

    fun start() {
        if (running) return
        running = true

        scope.launch {
            try {
                readTunPackets()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading TUN packets", e)
            }
        }
    }

    fun stop() {
        running = false
        scope.cancel()
        connections.values.forEach { conn ->
            runCatching { conn.channel?.close() }
        }
        connections.clear()
        runCatching { tunFd.close() }
    }

    private suspend fun readTunPackets() = withContext(Dispatchers.IO) {
        val inputStream = FileInputStream(tunFd.fileDescriptor)
        val buffer = ByteBuffer.allocate(65536)

        while (running) {
            buffer.clear()
            val bytesRead = inputStream.channel.read(buffer)
            if (bytesRead <= 0) {
                delay(10)
                continue
            }

            buffer.flip()
            val packet = ByteArray(bytesRead)
            buffer.get(packet)

            try {
                processIpPacket(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Error processing packet", e)
            }
        }
    }

    private fun processIpPacket(packet: ByteArray) {
        if (packet.isEmpty()) return

        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) {
            return
        }

        val headerLength = (packet[0].toInt() and 0xF) * 4
        if (packet.size < headerLength) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = parseIpv4(packet, 12)
        val dstIp = parseIpv4(packet, 16)

        when (protocol) {
            6 -> processTcpPacket(packet, headerLength, srcIp, dstIp)
            17 -> processUdpPacket(packet, headerLength, srcIp, dstIp)
        }
    }

    private fun processTcpPacket(packet: ByteArray, headerLength: Int, srcIp: String, dstIp: String) {
        if (packet.size < headerLength + 20) return

        val srcPort = ((packet[headerLength].toInt() and 0xFF) shl 8) or (packet[headerLength + 1].toInt() and 0xFF)
        val dstPort = ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or (packet[headerLength + 3].toInt() and 0xFF)

        val connId = "$srcIp:$srcPort->$dstIp:$dstPort"

        if (!connections.containsKey(connId)) {
            scope.launch {
                establishProxyConnection(connId, srcIp, srcPort, dstIp, dstPort)
            }
        }
    }

    private fun processUdpPacket(packet: ByteArray, headerLength: Int, srcIp: String, dstIp: String) {
        if (packet.size < headerLength + 8) return
        // UDP forwarding is not supported in this legacy forwarder.
        // Use TetherPathBridge (gVisor) for UDP support.
    }

    private suspend fun establishProxyConnection(
        connId: String,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
    ) = withContext(Dispatchers.IO) {
        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(true)
            channel.connect(InetSocketAddress(proxyHost, proxyPort))

            val conn = Connection(connId, srcIp, srcPort, dstIp, dstPort, channel)
            connections[connId] = conn

            scope.launch {
                try {
                    val handshake = buildSocks5Handshake(dstIp, dstPort)
                    val socket = channel.socket()
                    val outputStream = socket.getOutputStream()
                    outputStream.write(handshake)
                    outputStream.flush()

                    val inputStream = socket.getInputStream()
                    val response = ByteArray(1024)
                    inputStream.read(response)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in SOCKS5 handshake for $connId", e)
                    connections.remove(connId)
                    runCatching { channel.close() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to proxy for $connId", e)
        }
    }

    private fun buildSocks5Handshake(host: String, port: Int): ByteArray {
        val buffer = ByteBuffer.allocate(256)

        buffer.put(0x05.toByte()) // SOCKS5 version
        buffer.put(0x01.toByte()) // Number of methods
        buffer.put(0x00.toByte()) // No authentication

        buffer.put(0x05.toByte()) // SOCKS5 version
        buffer.put(0x01.toByte()) // CONNECT command
        buffer.put(0x00.toByte()) // Reserved

        buffer.put(0x03.toByte()) // Domain name address type
        val hostBytes = host.toByteArray()
        buffer.put(hostBytes.size.toByte()) // Domain length
        buffer.put(hostBytes) // Domain

        buffer.put((port shr 8).and(0xFF).toByte()) // Port high byte
        buffer.put(port.and(0xFF).toByte()) // Port low byte

        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    private fun parseIpv4(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}." +
               "${packet[offset + 1].toInt() and 0xFF}." +
               "${packet[offset + 2].toInt() and 0xFF}." +
               "${packet[offset + 3].toInt() and 0xFF}"
    }

    companion object {
        private const val TAG = "TunForwarder"
    }
}
