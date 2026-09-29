package com.vstoianov.libre2key

import com.vstoianov.libre2key.libre.FramInfo
import com.vstoianov.libre2key.libre.Libre2Crypto
import com.vstoianov.libre2key.libre.Sensor
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

/** Formats the 6 sensor-reported address bytes (LSB first) as a normal BLE address. */
fun ByteArray.bleAddress(): String = reversed().joinToString(":") { "%02X".format(it) }

data class ScanResult(
    val time: Date,
    val sensor: Sensor,
    val fram: FramInfo?,
    val framError: String?,
    val bleAddress: ByteArray?,
    val unlockCode: Long,
    val nextUnlockCount: Int,
    val streamingWasEnabled: Boolean,
    val notes: List<String>
) {

    /** The payload a BLE client writes to characteristic F001 on its next connection. */
    fun unlockPayload(count: Int): ByteArray = Libre2Crypto.streamingUnlockPayload(
        sensor.uid, sensor.patchInfo, unlockCode, count
    )

    /** Only the fields the watch Libre 2+ app actually reads. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("serial", sensor.serial)
        put("uid", sensor.uid.hex())
        put("patchInfo", sensor.patchInfo.hex())
        bleAddress?.let { put("bleAddress", it.bleAddress()) }
        // String, not number: some Zepp JSON parsers drop or coerce integers.
        put("unlockCode", unlockCode.toString())
        put("nextUnlockCount", nextUnlockCount.toString())
        // Watch must rewind to 1 after A1 1E. xDrip does the same (enableTime 42,
        // connectionIndex 1). A read-only rescan omits this so the watch keeps count.
        if (streamingWasEnabled) put("streamingEnabled", "1")
        put(
            "unlockPayloads",
            JSONArray().apply {
                for (count in nextUnlockCount until nextUnlockCount + 3) {
                    put(
                        JSONObject()
                            .put("unlockCount", count)
                            .put("payload", unlockPayload(count).hex())
                    )
                }
            }
        )
        val info = fram
        val cal = info?.calibration
        if (cal != null && info.crcOk) {
            put(
                "calibration",
                JSONObject()
                    .put("i1", cal.i1.toString())
                    .put("i2", cal.i2.toString())
                    .put("i3", cal.i3.toString())
                    .put("i4", cal.i4.toString())
                    .put("i5", cal.i5.toString())
                    .put("i6", cal.i6.toString())
            )
        }
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
