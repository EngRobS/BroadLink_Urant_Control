package com.example.broadlinkctrl.broadlink

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CBC + checksum helpers for the Broadlink local protocol.
 *
 * Every Broadlink-family device (Broadlink SP1/SP2/SP3, and OEM/rebrand units
 * such as URANT — device id 0x2728 in Broadlink's own device table) speaks
 * this exact protocol. Values below match the maintained protocol spec at
 * https://github.com/mjg59/python-broadlink/blob/master/protocol.md
 */
object BroadlinkCrypto {

    // Fixed key/IV used only until a device-specific key is negotiated via auth().
    val INIT_KEY: ByteArray = byteArrayOf(
        0x09, 0x76, 0x28, 0x34, 0x3f, 0xe9.toByte(), 0x9e.toByte(), 0x23,
        0x76, 0x5c, 0x15, 0x13, 0xac.toByte(), 0xcf.toByte(), 0x8b.toByte(), 0x02
    )
    val INIT_IV: ByteArray = byteArrayOf(
        0x56, 0x2e, 0x17, 0x99.toByte(), 0x6d, 0x09, 0x3d, 0x28,
        0xdd.toByte(), 0xb3.toByte(), 0xba.toByte(), 0x69, 0x5a, 0x2e, 0x6f, 0x58
    )

    fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Sum every byte starting from 0xbeaf, wrapping at 0xffff (per protocol.md). */
    fun checksum(data: ByteArray, start: Int = 0xbeaf): Int {
        var sum = start
        for (b in data) {
            sum = (sum + (b.toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
}
