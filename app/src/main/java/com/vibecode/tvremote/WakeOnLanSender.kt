package com.vibecode.tvremote

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

class WakeOnLanSender {
    companion object {
        private const val TAG = "WakeOnLanSender"

        // Standard WOL ports.
        private val STATIC_PORTS = intArrayOf(7, 9)

        private fun sendPacketsForAddress(
            address: InetAddress,
            sock: DatagramSocket,
            payload: ByteArray
        ) {
            STATIC_PORTS.forEach { port ->
                try {
                    val packet = DatagramPacket(payload, payload.size, address, port)
                    sock.send(packet)
                    Log.d(TAG, "WOL packet sent to $address:$port")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send WOL packet to $address:$port", e)
                }
            }
        }

        private fun macStringToBytes(macAddress: String): ByteArray {
            val normalized = macAddress
                .trim()
                .replace(":", "")
                .replace("-", "")
                .lowercase(Locale.US)

            require(normalized.length == 12) {
                "MAC address must contain 12 hex characters"
            }

            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                val from = i * 2
                val to = from + 2
                macBytes[i] = normalized.substring(from, to).toInt(16).toByte()
            }
            return macBytes
        }

        private fun createWolPayload(macAddress: String): ByteArray {
            val payload = ByteArray(102)
            val macBytes = macStringToBytes(macAddress)
            for (i in 0 until 6) {
                payload[i] = 0xFF.toByte()
            }

            var i = 6
            repeat(16) {
                System.arraycopy(macBytes, 0, payload, i, macBytes.size)
                i += macBytes.size
            }
            return payload
        }

        private fun getBroadcastAddress(ipAddress: String): InetAddress? {
            val parts = ipAddress.split(".")
            if (parts.size != 4) return null
            val prefix = parts.take(3).joinToString(".")
            return InetAddress.getByName("$prefix.255")
        }

        fun sendWolPacket(macAddress: String, targetIp: String) {
            val payload = try {
                createWolPayload(macAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid MAC address: $macAddress", e)
                return
            }

            DatagramSocket().use { sock ->
                sock.broadcast = true

                // Try broadcast address
                sendPacketsForAddress(InetAddress.getByName("255.255.255.255"), sock, payload)

                // Try the subnet broadcast derived from the configured TV IP.
                getBroadcastAddress(targetIp)?.let { broadcastAddr ->
                    sendPacketsForAddress(broadcastAddr, sock, payload)
                } ?: Log.w(TAG, "Skipping directed broadcast; invalid IPv4 address: $targetIp")
            }
            Log.d(TAG, "WOL packet sent for MAC: $macAddress")
        }
    }
}
