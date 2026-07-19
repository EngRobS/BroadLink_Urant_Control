package com.example.broadlinkctrl.broadlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import kotlin.random.Random

object BroadlinkManager {

    /**
     * Broadcasts a discovery packet on the LAN and collects replies for
     * [timeoutMs]. Returns every Broadlink-protocol device that answers,
     * regardless of brand (URANT devices answer exactly like Broadlink SP2/3).
     */
    suspend fun discover(localIp: InetAddress, timeoutMs: Int = 4000): List<BroadlinkDevice> =
        withContext(Dispatchers.IO) {
            val found = mutableListOf<BroadlinkDevice>()
            val socket = DatagramSocket(0, localIp)
            socket.broadcast = true
            socket.soTimeout = 500

            val packet = buildDiscoveryPacket(localIp, socket.localPort)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(packet, packet.size, broadcastAddr, 80))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val recvPkt = DatagramPacket(buf, buf.size)
                    socket.receive(recvPkt)
                    val data = recvPkt.data.copyOf(recvPkt.length)
                    if (data.size < 0x40) continue

                    val devType = (data[0x34].toInt() and 0xFF) or ((data[0x35].toInt() and 0xFF) shl 8)
                    val mac = data.copyOfRange(0x3a, 0x40)
                    val already = found.any { it.mac.contentEquals(mac) }
                    if (!already) {
                        found.add(BroadlinkDevice(recvPkt.address, mac, devType))
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // keep looping until deadline
                }
            }
            socket.close()
            found
        }

    private fun buildDiscoveryPacket(localIp: InetAddress, sourcePort: Int): ByteArray {
        val packet = ByteArray(48)
        val cal = Calendar.getInstance()
        val tzOffsetSeconds = cal.timeZone.getOffset(cal.timeInMillis) / 1000

        val tzLe = BroadlinkCrypto.le32(tzOffsetSeconds)
        for (i in 0..3) packet[0x08 + i] = tzLe[i]

        val yearLe = BroadlinkCrypto.le16(cal.get(Calendar.YEAR))
        packet[0x0c] = yearLe[0]; packet[0x0d] = yearLe[1]

        packet[0x0e] = cal.get(Calendar.SECOND).toByte()
        packet[0x0f] = cal.get(Calendar.MINUTE).toByte()
        packet[0x10] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        // Calendar.DAY_OF_WEEK: Sunday=1..Saturday=7. Protocol wants Monday=1..Sunday=7.
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        packet[0x11] = (if (dow == 1) 7 else dow - 1).toByte()
        packet[0x12] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        packet[0x13] = (cal.get(Calendar.MONTH) + 1).toByte()

        val ipBytes = localIp.address
        for (i in 0..3) packet[0x18 + i] = ipBytes[i]

        val portLe = BroadlinkCrypto.le16(sourcePort)
        packet[0x1c] = portLe[0]; packet[0x1d] = portLe[1]

        packet[0x26] = 0x06

        val cs = BroadlinkCrypto.checksum(packet)
        val csLe = BroadlinkCrypto.le16(cs)
        packet[0x20] = csLe[0]; packet[0x21] = csLe[1]

        return packet
    }

    /**
     * AP-mode WiFi provisioning. Your phone must already be connected to the
     * plug's own temporary WiFi network before calling this. Broadcasts the
     * home network credentials so the plug can join it. securityMode:
     * 0 = none, 1 = WEP, 2 = WPA1, 3 = WPA2, 4 = WPA1/2.
     */
    suspend fun provision(ssid: String, password: String, securityMode: Int): Boolean =
        withContext(Dispatchers.IO) {
            val payload = ByteArray(0x88)
            payload[0x26] = 0x14

            val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
            val ssidStart = 0x44
            for (i in ssidBytes.indices) {
                if (ssidStart + i < 0x64) payload[ssidStart + i] = ssidBytes[i]
            }

            val passBytes = password.toByteArray(Charsets.UTF_8)
            val passStart = 0x64
            for (i in passBytes.indices) {
                if (passStart + i < 0x84) payload[passStart + i] = passBytes[i]
            }

            payload[0x84] = ssidBytes.size.toByte()
            payload[0x85] = passBytes.size.toByte()
            payload[0x86] = securityMode.toByte()

            val cs = BroadlinkCrypto.checksum(payload)
            val csLe = BroadlinkCrypto.le16(cs)
            payload[0x20] = csLe[0]; payload[0x21] = csLe[1]

            return@withContext try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    // Send a few times — this is fire-and-forget UDP with no ack.
                    repeat(3) {
                        socket.send(DatagramPacket(payload, payload.size, broadcastAddr, 80))
                        Thread.sleep(300)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
