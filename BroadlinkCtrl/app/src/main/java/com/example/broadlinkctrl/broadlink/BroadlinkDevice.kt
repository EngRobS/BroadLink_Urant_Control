package com.example.broadlinkctrl.broadlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Represents one discovered Broadlink-protocol smart plug (SP1/SP2/SP3, or an
 * OEM/rebrand unit like URANT that reports device id 0x2728 — Broadlink's own
 * device table lists 0x2728 as "SP2-compatible, URANT").
 *
 * All SP-family plugs are controlled identically regardless of brand sticker,
 * because they all implement the same command set (0x6a with packet[0]=1 to
 * read power, packet[0]=2 + packet[4]=1/0 to set power).
 */
class BroadlinkDevice(
    val ip: InetAddress,
    val mac: ByteArray,       // raw 6 bytes as received in the discovery response
    val devType: Int
) {
    var id: ByteArray = byteArrayOf(0, 0, 0, 0)
        private set
    var key: ByteArray = BroadlinkCrypto.INIT_KEY
        private set
    private val iv: ByteArray = BroadlinkCrypto.INIT_IV
    private var count: Int = Random.nextInt(0xFFFF)

    val macString: String
        get() = mac.reversed().joinToString(":") { String.format("%02X", it) }

    /** Builds and sends one command packet, returns the raw UDP response. */
    private fun sendPacket(command: Int, payload: ByteArray, timeoutMs: Int = 2000): ByteArray {
        count = (count + 1) and 0xFFFF

        val header = ByteArray(0x38)
        header[0x00] = 0x5a; header[0x01] = 0xa5.toByte(); header[0x02] = 0xaa.toByte(); header[0x03] = 0x55
        header[0x04] = 0x5a; header[0x05] = 0xa5.toByte(); header[0x06] = 0xaa.toByte(); header[0x07] = 0x55

        val devTypeLe = BroadlinkCrypto.le16(devType)
        header[0x24] = devTypeLe[0]; header[0x25] = devTypeLe[1]

        val cmdLe = BroadlinkCrypto.le16(command)
        header[0x26] = cmdLe[0]; header[0x27] = cmdLe[1]

        val countLe = BroadlinkCrypto.le16(count)
        header[0x28] = countLe[0]; header[0x29] = countLe[1]

        for (i in 0..5) header[0x2a + i] = mac[i]
        for (i in 0..3) header[0x30 + i] = id[i]

        // Checksum of the *unencrypted* payload goes at 0x34-0x35, start 0xbeaf.
        val payloadChecksum = BroadlinkCrypto.checksum(payload)
        val payloadCsLe = BroadlinkCrypto.le16(payloadChecksum)
        header[0x34] = payloadCsLe[0]; header[0x35] = payloadCsLe[1]

        val encryptedPayload = BroadlinkCrypto.encrypt(key, iv, payload)
        val fullPacket = header + encryptedPayload

        // Checksum of the ENTIRE packet (header incl. previous checksum + encrypted payload)
        // goes at 0x20-0x21, start 0xbeaf. Must zero those two bytes first.
        fullPacket[0x20] = 0; fullPacket[0x21] = 0
        val fullChecksum = BroadlinkCrypto.checksum(fullPacket)
        val fullCsLe = BroadlinkCrypto.le16(fullChecksum)
        fullPacket[0x20] = fullCsLe[0]; fullPacket[0x21] = fullCsLe[1]

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.broadcast = true
            val sendPkt = DatagramPacket(fullPacket, fullPacket.size, ip, 80)
            socket.send(sendPkt)

            val buf = ByteArray(2048)
            val recvPkt = DatagramPacket(buf, buf.size)
            socket.receive(recvPkt)
            return recvPkt.data.copyOf(recvPkt.length)
        }
    }

    /** Step 1 of using any device: negotiate a per-device AES key. Must succeed before power commands. */
    suspend fun auth(): Boolean = withContext(Dispatchers.IO) {
        // Reset to the well-known initial key/id before authenticating.
        key = BroadlinkCrypto.INIT_KEY
        id = byteArrayOf(0, 0, 0, 0)

        val payload = ByteArray(0x50)
        for (i in 0x04..0x13) payload[i] = 0x31 // "1111111111111111" placeholder client id
        payload[0x1E] = 0x01
        payload[0x2D] = 0x01
        val name = "AndroidCtrl".toByteArray(Charsets.US_ASCII)
        for (i in name.indices) if (0x30 + i < 0x7f) payload[0x30 + i] = name[i]

        val response = sendPacket(0x0065, payload)
        val err = (response[0x22].toInt() and 0xFF) or ((response[0x23].toInt() and 0xFF) shl 8)
        if (err != 0) return@withContext false

        val encPayload = response.copyOfRange(0x38, response.size)
        val decrypted = BroadlinkCrypto.decrypt(BroadlinkCrypto.INIT_KEY, BroadlinkCrypto.INIT_IV, encPayload)

        id = decrypted.copyOfRange(0x00, 0x04)
        key = decrypted.copyOfRange(0x04, 0x14)
        true
    }

    /** Turns the plug on/off. Call auth() first. */
    suspend fun setPower(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = ByteArray(16)
        payload[0] = 2
        payload[4] = if (on) 1 else 0
        val response = sendPacket(0x6a, payload)
        val err = (response[0x22].toInt() and 0xFF) or ((response[0x23].toInt() and 0xFF) shl 8)
        err == 0
    }

    /** Reads current on/off state. Call auth() first. */
    suspend fun checkPower(): Boolean? = withContext(Dispatchers.IO) {
        val payload = ByteArray(16)
        payload[0] = 1
        val response = sendPacket(0x6a, payload)
        val err = (response[0x22].toInt() and 0xFF) or ((response[0x23].toInt() and 0xFF) shl 8)
        if (err != 0) return@withContext null
        val decrypted = BroadlinkCrypto.decrypt(key, iv, response.copyOfRange(0x38, response.size))
        decrypted[0x4].toInt() != 0
    }

    companion object {
        /** Human-friendly name for a subset of known device-type codes. */
        fun describeType(devType: Int): String = when (devType) {
            0x0000 -> "SP1"
            0x2711 -> "SP2"
            0x2719, 0x7919, 0x271a, 0x791a -> "SP2 (Honeywell)"
            0x2720 -> "SP mini"
            0x753e -> "SP3"
            0x2728 -> "SP2-compatible (URANT)"
            0x2736 -> "SP mini+"
            else -> "Broadlink-compatible (0x${devType.toString(16)})"
        }
    }
}
