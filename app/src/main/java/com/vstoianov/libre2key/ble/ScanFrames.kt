package com.vstoianov.libre2key.ble

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32

/**
 * Wire format for handing the scan JSON to AmazCGM over Bluetooth LE.
 *
 * The watch has no MTU control, so every notification must fit the default
 * ATT MTU of 23 bytes: 20 bytes of value. Each frame is
 * `[seq u8][total u8][payload]` with seq 0..total-1.
 *
 * The advert carries manufacturer data `AMZCGM` + version + CRC32 (little
 * endian) of the JSON, so the watch can tell "up to date" without connecting.
 */
object ScanFrames {
    /** Unassigned 16-bit UUIDs in the Bluetooth base; Zepp handles these best. */
    val SERVICE_UUID: UUID = base16(0xAC10)
    val CONTROL_UUID: UUID = base16(0xAC11)
    val DATA_UUID: UUID = base16(0xAC12)
    val CCCD_UUID: UUID = base16(0x2902)

    /** Bluetooth SIG "reserved for testing" company ID. */
    const val COMPANY_ID = 0xFFFF
    const val VERSION: Byte = 1
    val MAGIC: ByteArray = "AMZCGM".toByteArray(StandardCharsets.US_ASCII)

    /** Value bytes per notification at the default MTU, minus the 2-byte header. */
    const val DEFAULT_PAYLOAD = 18
    const val MAX_FRAMES = 255

    const val CMD_GET = "GET"
    const val CMD_ACK = "ACK"

    fun base16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short and 0xFFFF))

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** Manufacturer-data payload (after the company ID): magic, version, CRC32 LE. */
    fun advertPayload(json: ByteArray): ByteArray {
        val crc = crc32(json)
        return MAGIC + byteArrayOf(
            VERSION,
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
            ((crc shr 16) and 0xFF).toByte(),
            ((crc shr 24) and 0xFF).toByte()
        )
    }

    /** Service data under [SERVICE_UUID]: version + CRC32 LE. Fits the primary advert. */
    fun serviceData(json: ByteArray): ByteArray {
        val crc = crc32(json)
        return byteArrayOf(
            VERSION,
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
            ((crc shr 16) and 0xFF).toByte(),
            ((crc shr 24) and 0xFF).toByte()
        )
    }

    /** Splits [json] into notification frames of at most [payload] + 2 bytes. */
    fun frames(json: ByteArray, payload: Int = DEFAULT_PAYLOAD): List<ByteArray> {
        require(payload in 1..253) { "payload size $payload" }
        val total = maxOf(1, (json.size + payload - 1) / payload)
        require(total <= MAX_FRAMES) { "scan too large: ${json.size} bytes" }
        return List(total) { seq ->
            val start = seq * payload
            val end = minOf(json.size, start + payload)
            byteArrayOf(seq.toByte(), total.toByte()) + json.copyOfRange(start, end)
        }
    }

    /**
     * Rebuilds the JSON from frames in any order, ignoring duplicates.
     * Returns null while frames are missing. Mirrors the watch decoder.
     */
    fun reassemble(received: Iterable<ByteArray>): ByteArray? {
        val parts = HashMap<Int, ByteArray>()
        var total = -1
        for (frame in received) {
            if (frame.size < 2) continue
            val seq = frame[0].toInt() and 0xFF
            val count = frame[1].toInt() and 0xFF
            if (count == 0 || seq >= count) continue
            if (total < 0) total = count
            if (count != total) return null
            parts.putIfAbsent(seq, frame.copyOfRange(2, frame.size))
        }
        if (total < 0 || parts.size != total) return null
        var out = ByteArray(0)
        for (i in 0 until total) out += parts[i] ?: return null
        return out
    }
}
