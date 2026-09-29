package com.vstoianov.libre2key

import com.vstoianov.libre2key.libre.Libre2Crypto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shared vectors with the watch app (bip-max-libre shared/crypto.test.mjs), so a payload
 * computed on the watch for a later unlock count matches what this app would have sent.
 */
class UnlockVectorsTest {

    private fun bytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val uid = bytes("73382A0D78007AE0")
    private val patchInfo = bytes("7F0E31017C0C")

    @Test
    fun `unlock payloads match the watch implementation`() {
        val expected = mapOf(
            1 to "2B0000001725B78CB287171F",
            2 to "2C000000D083FEC70DF8C0C0",
            3 to "2D000000CA7AE9DFE21B919F",
            4 to "2E00000091E4E7300AC9EE76",
            5 to "2F000000330DD10AE02DC770",
            6 to "30000000B48DC56E29646C57"
        )
        for ((count, hex) in expected) {
            val payload = Libre2Crypto.streamingUnlockPayload(uid, patchInfo, 42L, count)
            assertEquals("unlock count $count", hex, payload.hex())
        }
    }
}
