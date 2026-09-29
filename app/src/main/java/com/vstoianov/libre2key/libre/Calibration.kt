package com.vstoianov.libre2key.libre

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Factory calibration parameters stored in Libre 1/2 FRAM, and the LibreLink 2.3
 * temperature-corrected conversion used by DiaBLE / LibreTools / xdrip.
 *
 * i1–i6 extraction: DiaBLE `Libre.parseFRAM`
 * Conversion: DiaBLE `factoryGlucose` (Glucose.swift)
 */
data class FactoryCalibration(
    val i1: Int,
    val i2: Int,
    val i3: Int,
    val i4: Int,
    val i5: Int,
    val i6: Int
) {
    fun glucoseMgdl(rawGlucose: Int, rawTemperature: Int, temperatureAdjustment: Int): Int? {
        if (rawGlucose <= 0 || i2 == 0 || i4 == i3) return null
        val idx = i2 - 1
        if (idx !in t1.indices || idx !in t2.indices) return null
        val denom = (temperatureAdjustment + i6).toDouble()
        if (denom == 0.0) return null
        val r = (rawTemperature.toDouble() * X / denom) - Y
        if (r <= 0.0) return null
        val logR = ln(r)
        val d = logR.pow(3) * CD + logR.pow(2) * CC + logR * CB + CA
        if (d == 0.0) return null
        val temperature = 1.0 / d - 273.15
        val g1 = 65.0 * (rawGlucose - i3).toDouble() / (i4 - i3).toDouble()
        val g3 = g1 * 1.045.pow(32.5 - temperature)
        return ((g3 - t1[idx]) / t2[idx]).roundToInt()
    }

    companion object {
        private const val X = 1000.0 + 71500.0
        private const val Y = 1000.0
        private const val CA = 0.0009180023
        private const val CB = 0.0001964561
        private const val CC = 0.0000007061775
        private const val CD = 0.00000005283566

        fun extract(fram: ByteArray): FactoryCalibration? {
            if (fram.size < 344) return null
            val i1 = readFramBits(fram, 2, 0, 3)
            val i2 = readFramBits(fram, 2, 3, 0xA)
            val i3raw = readFramBits(fram, 0x150, 0, 8)
            val i4 = readFramBits(fram, 0x150, 8, 0xE)
            val negativeI3 = readFramBits(fram, 0x150, 0x21, 1) != 0
            val i5 = readFramBits(fram, 0x150, 0x28, 0xC) shl 2
            val i6 = readFramBits(fram, 0x150, 0x34, 0xC) shl 2
            return FactoryCalibration(
                i1 = i1,
                i2 = i2,
                i3 = if (negativeI3) -i3raw else i3raw,
                i4 = i4,
                i5 = i5,
                i6 = i6
            )
        }
    }
}

internal fun readFramBits(buffer: ByteArray, byteOffset: Int, bitOffset: Int, bitCount: Int): Int {
    var res = 0
    for (i in 0 until bitCount) {
        val totalBitOffset = bitOffset + i
        val byte = Math.floorDiv(totalBitOffset, 8)
        val bit = Math.floorMod(totalBitOffset, 8)
        val index = byteOffset + byte
        if (totalBitOffset >= 0 && index in buffer.indices &&
            ((buffer[index].toInt() shr bit) and 0x1) == 1
        ) {
            res = res or (1 shl i)
        }
    }
    return res
}

/** Latest trend sample: 6-byte record, 16-entry ring at body[4]. */
internal data class TrendSample(
    val rawGlucose: Int,
    val rawTemperature: Int,
    val temperatureAdjustment: Int
)

internal fun readLatestTrend(fram: ByteArray): TrendSample? {
    if (fram.size < 28 + 16 * 6) return null
    val trendIndex = fram[26].toInt() and 0xFF
    val newest = ((trendIndex - 1) + 16) % 16
    val offset = 28 + newest * 6
    if (offset + 6 > fram.size) return null
    val rawGlucose = readFramBits(fram, offset, 0, 0xE)
    val rawTemperature = readFramBits(fram, offset, 0x1A, 0xC) shl 2
    var temperatureAdjustment = readFramBits(fram, offset, 0x26, 0x9) shl 2
    if (readFramBits(fram, offset, 0x2F, 0x1) != 0) temperatureAdjustment = -temperatureAdjustment
    return TrendSample(rawGlucose, rawTemperature, temperatureAdjustment)
}

private val t1: DoubleArray = buildT1()
private val t2: DoubleArray = buildT2()

private fun buildT1(): DoubleArray {
    val a = doubleArrayOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5)
    val b = doubleArrayOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75)
    val c = doubleArrayOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75)
    val d = doubleArrayOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75, 3.0)
    val out = ArrayList<Double>(1023)
    repeat(64) { a.forEach(out::add) }
    repeat(23) { b.forEach(out::add) }
    repeat(29) { c.forEach(out::add) }
    repeat(8) { d.forEach(out::add) }
    return out.toDoubleArray()
}

private fun buildT2(): DoubleArray {
    val rows = listOf(
    8 to 0.0377442,
    8 to 0.0381217,
    8 to 0.0385029,
    8 to 0.0388879,
    8 to 0.0392768,
    8 to 0.0396696,
    8 to 0.0400663,
    8 to 0.0404669,
    8 to 0.0408716,
    8 to 0.0412803,
    8 to 0.0416931,
    8 to 0.04211,
    8 to 0.0425311,
    8 to 0.0429565,
    8 to 0.043386,
    8 to 0.0438199,
    8 to 0.0442581,
    8 to 0.0447007,
    8 to 0.0451477,
    8 to 0.0455991,
    8 to 0.0460551,
    8 to 0.0465157,
    8 to 0.0469808,
    8 to 0.0474506,
    8 to 0.0479252,
    8 to 0.0484044,
    8 to 0.0488884,
    8 to 0.0493773,
    8 to 0.0498711,
    8 to 0.0503698,
    8 to 0.0508735,
    8 to 0.0513823,
    8 to 0.0518961,
    8 to 0.052415,
    8 to 0.0529392,
    8 to 0.0534686,
    8 to 0.0540033,
    8 to 0.0545433,
    8 to 0.0550887,
    8 to 0.0556396,
    8 to 0.056196,
    8 to 0.056758,
    8 to 0.0573256,
    8 to 0.0578988,
    8 to 0.0584778,
    8 to 0.0590626,
    8 to 0.0596532,
    8 to 0.0602497,
    8 to 0.0608522,
    8 to 0.0614607,
    8 to 0.0620754,
    8 to 0.0626961,
    8 to 0.0633231,
    8 to 0.0639563,
    8 to 0.0645959,
    8 to 0.0652418,
    8 to 0.0658942,
    8 to 0.0665532,
    8 to 0.0672187,
    8 to 0.0678909,
    8 to 0.0685698,
    8 to 0.0692555,
    8 to 0.0699481,
    8 to 0.0706475,
    9 to 0.071354,
    9 to 0.0720676,
    9 to 0.0727882,
    9 to 0.0735161,
    9 to 0.0742513,
    9 to 0.0749938,
    9 to 0.0757437,
    9 to 0.0765012,
    9 to 0.0772662,
    9 to 0.0780388,
    9 to 0.0788192,
    9 to 0.0796074,
    9 to 0.0804035,
    9 to 0.0812075,
    9 to 0.0820196,
    9 to 0.0828398,
    9 to 0.0836682,
    9 to 0.0845049,
    9 to 0.0853499,
    9 to 0.0862034,
    9 to 0.0870655,
    9 to 0.0879361,
    9 to 0.0888155,
    8 to 0.0897036,
    8 to 0.0906007,
    8 to 0.0915067,
    8 to 0.0924217,
    8 to 0.093346,
    8 to 0.0942794,
    8 to 0.0952222,
    8 to 0.0961744,
    8 to 0.0971362,
    8 to 0.0981075,
    8 to 0.0990886,
    8 to 0.1000795,
    8 to 0.1010803,
    8 to 0.1020911,
    8 to 0.103112,
    8 to 0.1041431,
    8 to 0.1051846,
    8 to 0.1062364,
    8 to 0.1072988,
    8 to 0.1083718,
    8 to 0.1094555,
    8 to 0.11055,
    8 to 0.1116555,
    8 to 0.1127721,
    8 to 0.1138998,
    8 to 0.1150388,
    8 to 0.1161892,
    8 to 0.1173511,
    8 to 0.1185246,
    9 to 0.1197099,
    9 to 0.120907,
    9 to 0.122116,
    9 to 0.1233372,
    9 to 0.1245706,
    9 to 0.1258163,
    9 to 0.1270744,
    9 to 0.1283452
    )
    val out = ArrayList<Double>(1023)
    for ((width, value) in rows) repeat(width) { out.add(value) }
    return out.toDoubleArray()
}
