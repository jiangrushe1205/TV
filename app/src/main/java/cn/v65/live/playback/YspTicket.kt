package cn.v65.live.playback

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import okio.ByteString.Companion.toByteString
import java.util.UUID

/** Anonymous request fingerprint. Adapted from akiralereal/iptv (GPL-3.0); see THIRD-PARTY.md. */
object YspTicket {
    const val PLATFORM = 4330403
    const val APP_VERSION = "V8.22.1035.3031"
    data class Ticket(val cKey: String, val guid: String, val timestamp: Long, val flowId: String)
    private val secure = SecureRandom()
    private fun random(size: Int) = ByteArray(size).also { secure.nextBytes(it) }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val ticketKey = hex("59b2f7cf725ef43c34fdd7c123411ed3")
    private val guardKey = hex("110DBEC10C23E7D2E56A1CAD6914EF1B")
    private val ticketMask = hex("842eed08f066e6ea48b4caa991ed6ff3")
    private val guardMask = hex("b3c953a06913ad4d")
    private fun checksum(data: ByteArray): Int {
        var result = 0
        data.forEach { result = (result * 0x83 + (it.toInt() and 255)) and 0x7fffffff }
        return result
    }
    private fun packet(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()
    private fun DataOutputStream.field(value: String) = field(value.toByteArray(Charsets.UTF_8))
    private fun DataOutputStream.field(data: ByteArray) { writeShort(data.size); write(data) }
    private fun tea(block: ByteArray, key: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(block)
        var y = input.int; var z = input.int; var sum = 0
        val keys = ByteBuffer.wrap(key).let { k -> IntArray(4) { k.int } }
        repeat(16) {
            sum += 0x9e3779b9L.toInt()
            y += ((z shl 4) + keys[0]) xor (z + sum) xor ((z ushr 5) + keys[1])
            z += ((y shl 4) + keys[2]) xor (y + sum) xor ((y ushr 5) + keys[3])
        }
        return ByteBuffer.allocate(8).putInt(y).putInt(z).array()
    }
    private fun encrypt(input: ByteArray, key: ByteArray, randomBytes: (Int) -> ByteArray): ByteArray {
        val padding = (8 - ((input.size + 10) % 8)) % 8
        val plain = byteArrayOf(((randomBytes(1)[0].toInt() and 0xf8) or padding).toByte()) +
            randomBytes(padding) + randomBytes(2) + input + ByteArray(7)
        var previousPlain = ByteArray(8); var previousCipher = ByteArray(8)
        val result = ByteArray(plain.size)
        for (offset in plain.indices step 8) {
            val mixed = ByteArray(8) { (plain[offset + it].toInt() xor previousCipher[it].toInt()).toByte() }
            val encrypted = tea(mixed, key)
            val cipher = ByteArray(8) { (encrypted[it].toInt() xor previousPlain[it].toInt()).toByte() }
            cipher.copyInto(result, offset)
            previousPlain = mixed; previousCipher = cipher
        }
        return result
    }
    private fun masked(input: ByteArray, key: ByteArray, mask: ByteArray, randomBytes: (Int) -> ByteArray): ByteArray {
        val bytes = encrypt(input, key, randomBytes) + packet { writeInt(checksum(input)) }
        return ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it % mask.size].toInt()).toByte() }
    }
    fun create(channelId: String, now: Long = System.currentTimeMillis(),
               guid: String = random(16).joinToString("") { "%02x".format(it) },
               uid: String = random(4).joinToString("") { "%02X".format(it) },
               randomBytes: (Int) -> ByteArray = ::random): Ticket {
        require(channelId.matches(Regex("[0-9]{5,20}")))
        val seconds = now / 1000
        val guardBody = packet {
            writeInt(seconds.toInt()); field(guid.takeLast(5)); field(""); field(""); field("-1")
        }
        val guardPlain = packet { field(guardBody) }
        val guard = masked(guardPlain, guardKey, guardMask, randomBytes).joinToString("") { "%02X".format(it) }
        val body = packet {
            write(hex("0000004200000004000004d2")); writeInt(PLATFORM); writeInt(0); writeInt(seconds.toInt())
            field("dcgh"); field("_zj1A5Gh6QYcxWjIUGos2w=="); field(APP_VERSION); field(channelId); field(guid)
            writeInt(1); writeInt(1); field(uid); field("nil"); field("57eab0c4-2c58-44c6-8ae9-dd2757525dc5")
            field("nil"); field("v0.1.000"); field("com.cctv.yangshipin.app.iphone"); field(PLATFORM.toString())
            field("ex_json_bus"); field("ex_json_vs"); field(guard)
        }
        val requestPacket = packet { field(body) }
        ByteBuffer.wrap(requestPacket).putInt(18, checksum(requestPacket))
        val encoded = masked(requestPacket, ticketKey, ticketMask, randomBytes).toByteString().base64()
            .trimEnd('=').replace('+', '_').replace('/', '-')
        return Ticket("--01$encoded", guid, seconds, "${UUID.randomUUID().toString().uppercase()}_$PLATFORM")
    }
}
