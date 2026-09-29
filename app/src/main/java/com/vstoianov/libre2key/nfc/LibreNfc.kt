package com.vstoianov.libre2key.nfc

import android.nfc.tech.NfcV
import com.vstoianov.libre2key.libre.Libre2Crypto
import java.io.IOException

/**
 * The ISO 15693 conversation with a Libre sensor.
 *
 * Frame layout for the Abbott custom commands is `02 A1 07 <sub-command> <parameters>`:
 * `02` = high data rate flag, `A1` = custom command, `07` = Texas Instruments manufacturer
 * code. The first byte of every response is the ISO 15693 response flag; bit 0 set means
 * the sensor rejected the command.
 */
object LibreNfc {

    private const val RETRIES = 5

    class NfcError(message: String) : IOException(message)

    private fun isError(response: ByteArray?): Boolean =
        response == null || response.isEmpty() || (response[0].toInt() and 1) != 0

    /** Sends [command], retrying transient tag errors, and strips the response flag byte. */
    private fun transceive(nfcv: NfcV, command: ByteArray, what: String): ByteArray {
        var lastError: Throwable? = null
        repeat(RETRIES) {
            try {
                val response = nfcv.transceive(command)
                if (!isError(response)) {
                    return response.copyOfRange(1, response.size)
                }
                lastError = NfcError(
                    "$what rejected by sensor (error code 0x%02X)".format(
                        if (response != null && response.size > 1) response[1] else 0
                    )
                )
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw NfcError("$what failed: ${lastError?.message ?: "no response"}")
    }

    /** `A1 07` -> the 6-byte patch info identifying sensor type, region and generation. */
    fun readPatchInfo(nfcv: NfcV): ByteArray {
        val info = transceive(nfcv, byteArrayOf(0x02, 0xA1.toByte(), 0x07), "Read patch info")
        if (info.size < 6) throw NfcError("Patch info too short (${info.size} bytes)")
        return info.copyOf(6)
    }

    /** Reads the 344 bytes of FRAM (43 blocks). Still encrypted on Libre 2. */
    fun readFram(nfcv: NfcV): ByteArray {
        val fram = ByteArray(344)
        var block = 0
        while (block < 43) {
            val count = minOf(3, 43 - block)
            val data = try {
                // Read multiple blocks.
                transceive(
                    nfcv,
                    byteArrayOf(0x02, 0x23, block.toByte(), (count - 1).toByte()),
                    "Read blocks $block..${block + count - 1}"
                )
            } catch (e: NfcError) {
                // Some tags/phones only cope with single block reads.
                transceive(nfcv, byteArrayOf(0x02, 0x20, block.toByte()), "Read block $block")
            }
            val copied = minOf(data.size, fram.size - block * 8)
            System.arraycopy(data, 0, fram, block * 8, copied)
            block += if (copied >= count * 8) count else 1
        }
        return fram
    }

    /**
     * `A1 1E` -> turns on BLE advertising and returns the 6-byte BLE MAC address
     * (least significant byte first, as the sensor reports it).
     *
     * [unlockCode] is chosen by the caller and becomes the `enableTime` needed to build
     * every later streaming unlock payload, so it must be stored alongside the address.
     */
    fun enableStreaming(
        nfcv: NfcV,
        uid: ByteArray,
        patchInfo: ByteArray,
        unlockCode: Long
    ): ByteArray {
        val command = byteArrayOf(0x02, 0xA1.toByte(), 0x07) +
            Libre2Crypto.enableStreamingParameters(uid, patchInfo, unlockCode)
        val response = transceive(nfcv, command, "Enable BLE streaming")
        if (response.size < 6) {
            throw NfcError("Unexpected enable-streaming response (${response.size} bytes)")
        }
        return response.copyOf(6)
    }
}
