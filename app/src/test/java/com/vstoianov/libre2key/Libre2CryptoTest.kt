package com.vstoianov.libre2key

import com.vstoianov.libre2key.libre.Libre2Crypto
import com.vstoianov.libre2key.libre.Sensor
import com.vstoianov.libre2key.libre.SensorFamily
import com.vstoianov.libre2key.libre.SensorRegion
import com.vstoianov.libre2key.libre.SensorType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Libre2CryptoTest {

    private fun bytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // A real Libre 2 EU sensor, from the DiaBLE preview fixtures.
    private val uid = bytes("2fe7b10000a407e0")
    private val patchInfo = bytes("9d083001712b")

    @Test
    fun `serial number matches the printed one`() {
        assertEquals("3MH001DG75W", Sensor.serialNumber(uid, SensorFamily.LIBRE2))
    }

    @Test
    fun `patch info identifies a generation 1 european libre 2`() {
        val sensor = Sensor(uid, patchInfo)
        assertEquals(SensorType.LIBRE2, sensor.type)
        assertEquals(SensorFamily.LIBRE2, sensor.family)
        assertEquals(SensorRegion.EUROPEAN, sensor.region)
        assertEquals(1, sensor.securityGeneration)
        assertTrue(sensor.supportsOpenStreaming)
    }

    /**
     * The Libre CRC is CRC-16/X-25 with the result read back bit-reversed, so the standard
     * "123456789" check value 0x906E maps to 0x89F6 here.
     */
    @Test
    fun `crc16 matches the x25 check value`() {
        assertEquals(0x89F6, Libre2Crypto.crc16("123456789".toByteArray()))
    }

    @Test
    fun `enable streaming command carries the unlock code and 4 obfuscated bytes`() {
        val parameters = Libre2Crypto.enableStreamingParameters(uid, patchInfo, 42L)
        assertEquals(9, parameters.size)
        assertEquals(0x1E.toByte(), parameters[0])
        assertArrayEquals(byteArrayOf(42, 0, 0, 0), parameters.copyOfRange(1, 5))
    }

    @Test
    fun `unlock payload starts with the enable time plus the unlock count`() {
        val payload = Libre2Crypto.streamingUnlockPayload(uid, patchInfo, 42L, 3)
        assertEquals(12, payload.size)
        assertArrayEquals(byteArrayOf(45, 0, 0, 0), payload.copyOfRange(0, 4))
        assertNotEquals(
            Libre2Crypto.streamingUnlockPayload(uid, patchInfo, 42L, 4).toList(),
            payload.toList()
        )
    }

    @Test
    fun `fram decryption is reversible`() {
        // XOR keystream: decrypting twice returns the original bytes.
        val encrypted = ByteArray(344) { (it * 7).toByte() }
        val once = Libre2Crypto.decryptFRAM(uid, patchInfo, encrypted)
        assertArrayEquals(encrypted, Libre2Crypto.decryptFRAM(uid, patchInfo, once))
    }
}
