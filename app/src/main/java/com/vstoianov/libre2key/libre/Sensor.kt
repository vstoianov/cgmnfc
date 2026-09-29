package com.vstoianov.libre2key.libre

/**
 * Everything that can be derived from the 8-byte NFC UID and the 6-byte patch info
 * returned by the `A1 07` command, plus the optional decrypted FRAM.
 */

enum class SensorFamily(val code: Int, val label: String) {
    LIBRE1(0, "Libre 1"),
    LIBRE_PRO(1, "Libre Pro"),
    LIBRE2(3, "Libre 2"),
    LIBRE3(4, "Libre 3"),
    LIBRE_X(5, "Libre X"),
    LIBRE_SENSE(7, "Libre Sense"),
    LINGO(9, "Lingo"),
    UNKNOWN(-1, "unknown");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class SensorRegion(val code: Int, val label: String) {
    EUROPEAN(1, "European"),
    USA(2, "USA"),
    AUSTRALIAN_CANADIAN(4, "Australian / Canadian"),
    EASTERN_ROW(8, "Eastern / rest of world"),
    UNKNOWN(0, "unknown");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class SensorState(val code: Int, val label: String) {
    NOT_ACTIVATED(1, "Not activated"),
    WARMING_UP(2, "Warming up"),
    ACTIVE(3, "Active"),
    EXPIRED(4, "Expired"),
    SHUT_DOWN(5, "Shut down"),
    FAILURE(6, "Failure"),
    UNKNOWN(0, "unknown");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class SensorType(val label: String) {
    LIBRE1("Libre 1"),
    LIBRE_US14DAY("Libre US 14d"),
    LIBRE_PRO_H("Libre Pro/H"),
    LIBRE2("Libre 2"),
    LIBRE2_GEN2("Libre 2 (Gen2 security)"),
    LIBRE3("Libre 3"),
    UNKNOWN("unknown")
}

class Sensor(val uid: ByteArray, val patchInfo: ByteArray) {

    val type: SensorType = when (patchInfo[0].toInt() and 0xFF) {
        0xDF, 0xA2 -> SensorType.LIBRE1
        0xE5, 0xE6 -> SensorType.LIBRE_US14DAY
        0x70 -> SensorType.LIBRE_PRO_H
        0x9D, 0xC5 -> SensorType.LIBRE2
        0xC6 -> SensorType.LIBRE2 // non-Gen2 European Libre 2+
        0x7F -> SensorType.LIBRE2 // newer non-Gen2 European Libre 2 / 2+
        0x76, 0x2B, 0x2C -> SensorType.LIBRE2_GEN2
        else -> SensorType.UNKNOWN
    }

    val family: SensorFamily = SensorFamily.of((patchInfo[2].toInt() and 0xFF) shr 4)
    val region: SensorRegion = SensorRegion.of(patchInfo[3].toInt() and 0xFF)

    /** Generation 1 sensors use the open crypto in [Libre2Crypto]; generation 2 does not. */
    val securityGeneration: Int = run {
        val generation = patchInfo[2].toInt() and 0x0F
        when (family) {
            SensorFamily.LIBRE2 -> if (generation < 9) 1 else 2
            SensorFamily.LIBRE_SENSE -> if (generation < 4) 1 else 2
            else -> 0
        }
    }

    /** True for the 15-day "Plus" variants. */
    val isPlus: Boolean = when (patchInfo[0].toInt() and 0xFF) {
        0xC6, 0x2C -> true
        0x7F -> (patchInfo[2].toInt() and 0x0F) > 0
        0x2B -> (patchInfo[2].toInt() and 0x0F) > 9
        else -> false
    }

    val serial: String = serialNumber(uid, family)

    /** The open unlock/decryption path only works for European generation-1 Libre 2. */
    val supportsOpenStreaming: Boolean =
        (type == SensorType.LIBRE2 || type == SensorType.LIBRE_US14DAY) && securityGeneration == 1

    companion object {
        private val LOOKUP = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'C', 'D', 'E', 'F', 'G',
            'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'
        )

        /**
         * Sensor serial as printed by the reader apps, e.g. "3MH001A2B3C4".
         * Based on https://github.com/UPetersen/LibreMonitor SensorSerialNumber.swift
         */
        fun serialNumber(uid: ByteArray, family: SensorFamily): String {
            if (uid.size != 8) return ""
            // uid is LSB first; take the 6 significant bytes in MSB-first order.
            val b = IntArray(6) { uid[5 - it].toInt() and 0xFF }
            val fiveBits = intArrayOf(
                b[0] shr 3,
                (b[0] shl 2) + (b[1] shr 6),
                b[1] shr 1,
                (b[1] shl 4) + (b[2] shr 4),
                (b[2] shl 1) + (b[3] shr 7),
                b[3] shr 2,
                (b[3] shl 3) + (b[4] shr 5),
                b[4],
                b[5] shr 3,
                b[5] shl 2
            )
            val prefix = if (family == SensorFamily.UNKNOWN) "0" else family.code.toString()
            return fiveBits.fold(StringBuilder(prefix)) { acc, value ->
                acc.append(LOOKUP[value and 0x1F])
            }.toString()
        }
    }
}

/** Values read out of the decrypted 344-byte FRAM image. */
data class FramInfo(
    val headerCrcOk: Boolean,
    val bodyCrcOk: Boolean,
    val footerCrcOk: Boolean,
    val state: SensorState,
    val ageMinutes: Int,
    val maxLifeMinutes: Int,
    val initializations: Int,
    val latestRawGlucose: Int,
    val currentMgdl: Int?,
    val calibration: FactoryCalibration?
) {
    val crcOk: Boolean get() = headerCrcOk && bodyCrcOk && footerCrcOk

    companion object {
        fun parse(fram: ByteArray): FramInfo {
            fun le16(i: Int) = (fram[i].toInt() and 0xFF) or ((fram[i + 1].toInt() and 0xFF) shl 8)

            val headerCrcOk = le16(0) == Libre2Crypto.crc16(fram, 2, 22)
            val bodyCrcOk = le16(24) == Libre2Crypto.crc16(fram, 26, 294)
            val footerCrcOk = le16(320) == Libre2Crypto.crc16(fram, 322, 22)
            val state = SensorState.of(fram[4].toInt() and 0xFF)
            val crcOk = headerCrcOk && bodyCrcOk && footerCrcOk

            val trend = readLatestTrend(fram)
            val rawGlucose = trend?.rawGlucose ?: 0
            val calibration = FactoryCalibration.extract(fram)
            val currentMgdl = if (crcOk && state == SensorState.ACTIVE && trend != null && calibration != null) {
                calibration.glucoseMgdl(
                    trend.rawGlucose,
                    trend.rawTemperature,
                    trend.temperatureAdjustment
                )
            } else {
                null
            }

            return FramInfo(
                headerCrcOk = headerCrcOk,
                bodyCrcOk = bodyCrcOk,
                footerCrcOk = footerCrcOk,
                state = state,
                ageMinutes = le16(316),
                maxLifeMinutes = le16(326),
                initializations = fram[318].toInt() and 0xFF,
                latestRawGlucose = rawGlucose,
                currentMgdl = currentMgdl,
                calibration = calibration
            )
        }
    }
}
