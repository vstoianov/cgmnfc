package com.vstoianov.libre2key

import com.vstoianov.libre2key.libre.FactoryCalibration
import com.vstoianov.libre2key.libre.readFramBits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {

    private fun writeBits(buffer: ByteArray, byteOffset: Int, bitOffset: Int, bitCount: Int, value: Int) {
        for (i in 0 until bitCount) {
            val total = bitOffset + i
            val byte = Math.floorDiv(total, 8)
            val bit = Math.floorMod(total, 8)
            val idx = byteOffset + byte
            val bitValue = (value shr i) and 1
            buffer[idx] = if (bitValue == 1) {
                (buffer[idx].toInt() or (1 shl bit)).toByte()
            } else {
                (buffer[idx].toInt() and (1 shl bit).inv()).toByte()
            }
        }
    }

    @Test
    fun `extracts i1 to i6 from FRAM footer and header`() {
        val fram = ByteArray(344)
        writeBits(fram, 2, 0, 3, 2)
        writeBits(fram, 2, 3, 10, 17)
        writeBits(fram, 0x150, 0, 8, 40)
        writeBits(fram, 0x150, 8, 14, 1600)
        writeBits(fram, 0x150, 0x21, 1, 1)
        writeBits(fram, 0x150, 0x28, 12, 80)
        writeBits(fram, 0x150, 0x34, 12, 200)

        val cal = FactoryCalibration.extract(fram)
        assertNotNull(cal)
        assertEquals(2, cal!!.i1)
        assertEquals(17, cal.i2)
        assertEquals(-40, cal.i3)
        assertEquals(1600, cal.i4)
        assertEquals(80 shl 2, cal.i5)
        assertEquals(200 shl 2, cal.i6)
        assertEquals(2, readFramBits(fram, 2, 0, 3))
    }

    @Test
    fun `factory conversion returns a plausible glucose`() {
        val cal = FactoryCalibration(i1 = 1, i2 = 8, i3 = 0, i4 = 6000, i5 = 320, i6 = 5000)
        val mgdl = cal.glucoseMgdl(
            rawGlucose = 900,
            rawTemperature = 3800,
            temperatureAdjustment = 0
        )
        assertNotNull(mgdl)
        assertTrue("mg/dL was $mgdl", mgdl!! in 20..500)
    }
}
