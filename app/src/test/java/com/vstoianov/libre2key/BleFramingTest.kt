package com.vstoianov.libre2key

import com.vstoianov.libre2key.ble.ScanFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFramingTest {

    private val json = (
        """{"serial":"3MH000TEST0","uid":"73382A0D78007AE0","patchInfo":"7F0E31017C0C",""" +
            """"bleAddress":"F8:05:84:00:00:01","unlockCode":"42","nextUnlockCount":"1",""" +
            """"streamingEnabled":"1","calibration":{"i1":"1","i2":"8","i3":"0","i4":"6000","i5":"320","i6":"5000"}}"""
        ).toByteArray(Charsets.UTF_8)

    @Test
    fun `crc32 matches the standard check value`() {
        // Same vector the watch test uses.
        assertEquals(0xCBF43926L, ScanFrames.crc32("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `frames fit a default-MTU notification`() {
        val frames = ScanFrames.frames(json)
        assertTrue(frames.all { it.size <= 20 })
        assertEquals((json.size + 17) / 18, frames.size)
        frames.forEachIndexed { i, f ->
            assertEquals(i, f[0].toInt() and 0xFF)
            assertEquals(frames.size, f[1].toInt() and 0xFF)
        }
    }

    @Test
    fun `reassembly handles reorder and duplicates`() {
        val frames = ScanFrames.frames(json)
        val shuffled = frames.reversed() + frames[1] + frames[0]
        assertArrayEquals(json, ScanFrames.reassemble(shuffled))
    }

    @Test
    fun `reassembly waits for missing frames`() {
        val frames = ScanFrames.frames(json)
        assertNull(ScanFrames.reassemble(frames.drop(1)))
    }

    @Test
    fun `advert payload is magic, version and crc little endian`() {
        val adv = ScanFrames.advertPayload(json)
        assertEquals(11, adv.size)
        assertArrayEquals("AMZCGM".toByteArray(Charsets.US_ASCII), adv.copyOfRange(0, 6))
        assertEquals(1, adv[6].toInt())
        val crc = ScanFrames.crc32(json)
        val le = (adv[7].toLong() and 0xFF) or ((adv[8].toLong() and 0xFF) shl 8) or
            ((adv[9].toLong() and 0xFF) shl 16) or ((adv[10].toLong() and 0xFF) shl 24)
        assertEquals(crc, le)
    }

    @Test
    fun `service data is version and crc little endian`() {
        val sd = ScanFrames.serviceData(json)
        assertEquals(5, sd.size)
        assertEquals(1, sd[0].toInt())
        // Same CRC bytes as the manufacturer payload carries after its magic.
        assertArrayEquals(ScanFrames.advertPayload(json).copyOfRange(7, 11), sd.copyOfRange(1, 5))
    }

    @Test
    fun `uuids use the bluetooth base`() {
        assertEquals("0000ac10-0000-1000-8000-00805f9b34fb", ScanFrames.SERVICE_UUID.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", ScanFrames.CCCD_UUID.toString())
    }
}
