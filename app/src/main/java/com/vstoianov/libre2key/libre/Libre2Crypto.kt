package com.vstoianov.libre2key.libre

/**
 * Libre 2 (security generation 1) obfuscation used by the NFC `A1` sub-commands,
 * by the FRAM contents and by the BLE streaming unlock.
 *
 * Ported from the community reverse engineering work:
 *   https://github.com/ivalkou/LibreTools  (Sources/LibreTools/Sensor/Libre2.swift)
 *   https://github.com/gui-dos/DiaBLE      (DiaBLE/Libre2.swift)
 *
 * All values are kept in Int and masked to 16 bits, mirroring the UInt16 arithmetic
 * of the reference implementation.
 *
 * Byte order note: [uid] is the raw `Tag.getId()` from Android, which is
 * least-significant byte first (uid[7] == 0xE0). That is the same order the
 * reference implementations use, so no reversing is needed here.
 */
object Libre2Crypto {

    private val KEY = intArrayOf(0xA0C5, 0x6860, 0x0000, 0x14C6)

    /** Default `y` argument of usefulFunction() for every sub-command except enable-streaming. */
    const val SECRET = 0x1B6A

    const val SUBCMD_UNLOCK = 0x1A
    const val SUBCMD_ACTIVATE = 0x1B
    const val SUBCMD_ENABLE_STREAMING = 0x1E

    private fun u16(high: Byte, low: Byte): Int =
        ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)

    private fun prepareVariables(uid: ByteArray, x: Int, y: Int): IntArray {
        val s1 = (u16(uid[5], uid[4]) + x + y) and 0xFFFF
        val s2 = (u16(uid[3], uid[2]) + KEY[2]) and 0xFFFF
        val s3 = (u16(uid[1], uid[0]) + x * 2) and 0xFFFF
        val s4 = 0x241A xor KEY[3]
        return intArrayOf(s1, s2, s3, s4)
    }

    private fun prepareVariables2(uid: ByteArray, i1: Int, i2: Int, i3: Int, i4: Int): IntArray {
        val s1 = (u16(uid[5], uid[4]) + i1) and 0xFFFF
        val s2 = (u16(uid[3], uid[2]) + i2) and 0xFFFF
        val s3 = (u16(uid[1], uid[0]) + i3 + KEY[2]) and 0xFFFF
        val s4 = (i4 + KEY[3]) and 0xFFFF
        return intArrayOf(s1, s2, s3, s4)
    }

    private fun op(value: Int): Int {
        var res = value shr 2 // result does not include the last 2 bits
        if (value and 1 != 0) res = res xor KEY[1]
        if (value and 2 != 0) res = res xor KEY[0]
        return res and 0xFFFF
    }

    fun processCrypto(input: IntArray): IntArray {
        val r0 = op(input[0]) xor input[3]
        val r1 = op(r0) xor input[2]
        val r2 = op(r1) xor input[1]
        val r3 = op(r2) xor input[0]
        val r4 = op(r3)
        val r5 = op(r4 xor r0)
        val r6 = op(r5 xor r1)
        val r7 = op(r6 xor r2)

        return intArrayOf(r3 xor r7, r2 xor r6, r1 xor r5, r0 xor r4)
    }

    /** The 4 obfuscated bytes appended to every `A1` sub-command. */
    fun usefulFunction(uid: ByteArray, x: Int, y: Int): ByteArray {
        val blockKey = processCrypto(prepareVariables(uid, x, y))
        val r1 = blockKey[0] xor 0x4163
        val r2 = blockKey[1] xor 0x4344
        return byteArrayOf(
            r1.toByte(),
            (r1 shr 8).toByte(),
            r2.toByte(),
            (r2 shr 8).toByte()
        )
    }

    /**
     * Parameters of the `A1 1E` (enable BLE streaming) command.
     * [unlockCode] is a freely chosen 32-bit value that must be remembered: it is the
     * `enableTime` of every later [streamingUnlockPayload].
     */
    fun enableStreamingParameters(uid: ByteArray, patchInfo: ByteArray, unlockCode: Long): ByteArray {
        val code = unlockCode and 0xFFFFFFFFL
        val b = byteArrayOf(
            (code and 0xFF).toByte(),
            ((code shr 8) and 0xFF).toByte(),
            ((code shr 16) and 0xFF).toByte(),
            ((code shr 24) and 0xFF).toByte()
        )
        val secret = u16(patchInfo[5], patchInfo[4]) xor u16(b[1], b[0])
        return byteArrayOf(SUBCMD_ENABLE_STREAMING.toByte()) + b +
            usefulFunction(uid, SUBCMD_ENABLE_STREAMING, secret)
    }

    /**
     * The 12-byte payload a BLE client writes to characteristic F001 to unlock a
     * streaming session. [enableTime] is the unlock code that was sent over NFC and
     * [unlockCount] must be incremented on every connection.
     */
    fun streamingUnlockPayload(
        uid: ByteArray,
        patchInfo: ByteArray,
        enableTime: Long,
        unlockCount: Int
    ): ByteArray {
        val time = (enableTime + unlockCount) and 0xFFFFFFFFL
        val b = byteArrayOf(
            (time and 0xFF).toByte(),
            ((time shr 8) and 0xFF).toByte(),
            ((time shr 16) and 0xFF).toByte(),
            ((time shr 24) and 0xFF).toByte()
        )

        // Obfuscated bytes of the activate and enable-streaming commands the sensor saw.
        val ad = usefulFunction(uid, SUBCMD_ACTIVATE, SECRET)
        val ed = usefulFunction(
            uid,
            SUBCMD_ENABLE_STREAMING,
            (enableTime.toInt() and 0xFFFF) xor u16(patchInfo[5], patchInfo[4])
        )

        val t11 = u16(ed[1], ed[0]) xor u16(b[3], b[2])
        val t12 = u16(ad[1], ad[0])
        val t13 = u16(ed[3], ed[2]) xor u16(b[1], b[0])
        val t14 = u16(ad[3], ad[2])

        val t2 = processCrypto(prepareVariables2(uid, t11, t12, t13, t14))

        val t31 = crc16(
            byteArrayOf(
                0xC1.toByte(), 0xC4.toByte(), 0xC3.toByte(), 0xC0.toByte(),
                0xD4.toByte(), 0xE1.toByte(), 0xE7.toByte(), 0xBA.toByte(),
                t2[0].toByte(), (t2[0] shr 8).toByte()
            )
        )
        val t32 = crc16(
            byteArrayOf(
                t2[1].toByte(), (t2[1] shr 8).toByte(),
                t2[2].toByte(), (t2[2] shr 8).toByte(),
                t2[3].toByte(), (t2[3] shr 8).toByte()
            )
        )
        val t33 = crc16(byteArrayOf(ad[0], ad[1], ad[2], ad[3], ed[0], ed[1]))
        val t34 = crc16(byteArrayOf(ed[2], ed[3], b[0], b[1], b[2], b[3]))

        val t4 = processCrypto(prepareVariables2(uid, t31, t32, t33, t34))

        return byteArrayOf(
            b[0], b[1], b[2], b[3],
            t4[0].toByte(), (t4[0] shr 8).toByte(),
            t4[1].toByte(), (t4[1] shr 8).toByte(),
            t4[2].toByte(), (t4[2] shr 8).toByte(),
            t4[3].toByte(), (t4[3] shr 8).toByte()
        )
    }

    /** Decrypts the 43 blocks (344 bytes) of Libre 2 FRAM read over NFC. */
    fun decryptFRAM(uid: ByteArray, patchInfo: ByteArray, data: ByteArray): ByteArray {
        require(data.size >= 344) { "FRAM must be at least 344 bytes, got ${data.size}" }
        val arg = u16(patchInfo[5], patchInfo[4]) xor 0x44
        val result = ByteArray(344)
        for (i in 0 until 43) {
            val blockKey = processCrypto(prepareVariables(uid, i, arg))
            for (j in 0 until 4) {
                result[i * 8 + j * 2] = (data[i * 8 + j * 2].toInt() xor blockKey[j]).toByte()
                result[i * 8 + j * 2 + 1] =
                    (data[i * 8 + j * 2 + 1].toInt() xor (blockKey[j] shr 8)).toByte()
            }
        }
        return result
    }

    /** Decrypts a 46-byte Libre 2 BLE notification (kept for completeness / verification). */
    fun decryptBLE(uid: ByteArray, data: ByteArray): ByteArray {
        val d = usefulFunction(uid, SUBCMD_ACTIVATE, SECRET)
        val x = (u16(d[1], d[0]) xor u16(d[3], d[2])) or 0x63
        val y = u16(data[1], data[0]) xor 0x63

        val key = ByteArray(64)
        var initialKey = processCrypto(prepareVariables(uid, x, y))
        for (i in 0 until 8) {
            for (j in 0 until 4) {
                key[i * 8 + j * 2] = initialKey[j].toByte()
                key[i * 8 + j * 2 + 1] = (initialKey[j] shr 8).toByte()
            }
            initialKey = processCrypto(initialKey)
        }

        val result = ByteArray(data.size - 2)
        for (i in result.indices) {
            result[i] = (data[i + 2].toInt() xor key[i].toInt()).toByte()
        }
        return result
    }

    // CRC-16/CCITT with reflected polynomial 0x8408 and a bit-reversed result,
    // as used throughout the Libre FRAM and the unlock payload.
    private val crc16Table = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if (crc and 1 != 0) (crc shr 1) xor 0x8408 else crc shr 1
        }
        crc and 0xFFFF
    }

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = (crc shr 8) xor crc16Table[(crc xor data[i].toInt()) and 0xFF]
        }
        var reversed = 0
        repeat(16) {
            reversed = (reversed shl 1) or (crc and 1)
            crc = crc shr 1
        }
        return reversed and 0xFFFF
    }
}
