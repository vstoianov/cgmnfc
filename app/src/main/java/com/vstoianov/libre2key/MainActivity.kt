package com.vstoianov.libre2key

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.vstoianov.libre2key.databinding.ActivityMainBinding
import com.vstoianov.libre2key.libre.FramInfo
import com.vstoianov.libre2key.libre.Libre2Crypto
import com.vstoianov.libre2key.libre.Sensor
import com.vstoianov.libre2key.libre.SensorType
import com.vstoianov.libre2key.nfc.LibreNfc
import com.vstoianov.libre2key.server.ScanHttpServer
import com.vstoianov.libre2key.server.ScanServerService
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var lastResult: ScanResult? = null

    // NFC callbacks arrive off the UI thread — never read views from there.
    private val enableStreamingWanted = AtomicBoolean(true)
    private val unlockCodeWanted = AtomicLong(DEFAULT_UNLOCK_CODE)

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val storedCode = prefs.getLong(KEY_LAST_CODE, DEFAULT_UNLOCK_CODE)
        binding.unlockCode.setText(storedCode.toString())
        unlockCodeWanted.set(storedCode)
        enableStreamingWanted.set(binding.enableStreaming.isChecked)

        binding.enableStreaming.setOnCheckedChangeListener { _, checked ->
            enableStreamingWanted.set(checked)
            binding.enableStreamingHint.text = getString(
                if (checked) R.string.enable_streaming_hint_on else R.string.enable_streaming_hint_off
            )
        }
        binding.unlockCode.doAfterTextChanged { text ->
            val code = parseUnlockCode(text?.toString())
            unlockCodeWanted.set(code)
            prefs.edit().putLong(KEY_LAST_CODE, code).apply()
        }

        binding.copyButton.setOnClickListener { copyJson() }
        binding.shareButton.setOnClickListener { shareJson() }
        setResultButtonsEnabled(false)
        requestNotifyPermission()
    }

    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        if (adapter == null) {
            binding.status.setText(R.string.no_nfc)
            return
        }
        if (!adapter.isEnabled) {
            binding.status.setText(R.string.nfc_disabled)
            return
        }
        if (lastResult == null) {
            binding.status.setText(R.string.ready)
        }
        val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000) }
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            options
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /** Called on a binder thread while the tag is in range. */
    override fun onTagDiscovered(tag: Tag) {
        val enableStreaming = enableStreamingWanted.get()
        val unlockCode = unlockCodeWanted.get()
        runOnUiThread { binding.status.setText(R.string.reading) }
        try {
            val result = scan(tag, enableStreaming, unlockCode)
            vibrate(longArrayOf(0, 60, 40, 60))
            runOnUiThread { show(result) }
        } catch (e: Throwable) {
            vibrate(longArrayOf(0, 400))
            runOnUiThread {
                binding.status.text = getString(R.string.scan_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun scan(tag: Tag, enableStreaming: Boolean, unlockCode: Long): ScanResult {
        val nfcv = NfcV.get(tag) ?: throw IOException(getString(R.string.not_nfcv))
        nfcv.connect()
        nfcv.use {
            val uid = tag.id
            val patchInfo = LibreNfc.readPatchInfo(nfcv)
            val sensor = Sensor(uid, patchInfo)
            val notes = mutableListOf<String>()

            if (!sensor.supportsOpenStreaming) {
                notes += if (sensor.securityGeneration >= 2) {
                    getString(R.string.note_gen2)
                } else {
                    getString(R.string.note_unsupported_type, sensor.type.label)
                }
            }

            // BLE key first — FRAM dump is slower and the tag can walk out of range.
            var address: ByteArray? = null
            var enabledNow = false
            if (enableStreaming && sensor.supportsOpenStreaming) {
                address = LibreNfc.enableStreaming(nfcv, uid, patchInfo, unlockCode)
                enabledNow = true
                prefs.edit()
                    .putLong(KEY_LAST_CODE, unlockCode)
                    .putLong(codeKey(sensor.serial), unlockCode)
                    .putInt(countKey(sensor.serial), 0)
                    .putString(macKey(sensor.serial), address.hex())
                    .apply()
            }

            var framInfo: FramInfo? = null
            var framError: String? = null
            try {
                val raw = LibreNfc.readFram(nfcv)
                val plain = if (sensor.supportsOpenStreaming) {
                    Libre2Crypto.decryptFRAM(uid, patchInfo, raw)
                } else {
                    raw
                }
                framInfo = FramInfo.parse(plain)
                if (!framInfo.crcOk && sensor.type == SensorType.LIBRE2) {
                    notes += getString(R.string.note_crc_failed)
                }
            } catch (e: Throwable) {
                framError = e.message ?: e.javaClass.simpleName
            }

            // Fall back to what a previous scan of this sensor stored.
            val storedCode = prefs.getLong(codeKey(sensor.serial), unlockCode)
            if (address == null) {
                address = prefs.getString(macKey(sensor.serial), null)?.let { hexToBytes(it) }
            }
            val storedCount = prefs.getInt(countKey(sensor.serial), 0)

            if (!enabledNow && storedCode != unlockCode) {
                notes += getString(R.string.note_code_not_written, unlockCode, storedCode)
            }

            return ScanResult(
                time = Date(),
                sensor = sensor,
                fram = framInfo,
                framError = framError,
                bleAddress = address,
                // JSON must match the key the sensor actually has. A typed-but-not-
                // written code would make the watch compute unlock payloads the
                // sensor will reject.
                unlockCode = if (enabledNow) unlockCode else storedCode,
                nextUnlockCount = storedCount + 1,
                streamingWasEnabled = enabledNow,
                notes = notes
            )
        }
    }

    private fun show(result: ScanResult) {
        lastResult = result
        setResultButtonsEnabled(true)
        binding.status.text = getString(R.string.scanned, result.sensor.serial)
        // The field is the code in the JSON, not whatever was typed and not written.
        binding.unlockCode.setText(result.unlockCode.toString())
        unlockCodeWanted.set(result.unlockCode)
        showGlucose(result)
        binding.report.text = buildReport(result)
        persistScanJson(result)
        copyJsonSilent()
        ScanServerService.start(this)
        binding.serverStatus.visibility = android.view.View.VISIBLE
        binding.serverStatus.text = getString(R.string.serving, scannerUrl())
    }

    private fun showGlucose(result: ScanResult) {
        val mgdl = result.fram?.currentMgdl
        if (mgdl == null) {
            binding.glucose.visibility = android.view.View.GONE
            return
        }
        val mmol = mgdl / 18.0
        binding.glucose.visibility = android.view.View.VISIBLE
        binding.glucose.text = getString(R.string.glucose_reading, mmol, mgdl) +
            "  ·  " + getString(R.string.glucose_age, 0)
    }

    private fun persistScanJson(result: ScanResult) {
        prefs.edit().putString(KEY_SCAN_JSON, result.toJson().toString()).apply()
    }

    private fun buildReport(result: ScanResult): String {
        val s = result.sensor
        val sb = StringBuilder()

        sb.appendLine("SENSOR")
        sb.appendLine("  Serial (BLE name)  ${s.serial}")
        sb.appendLine("  Type               ${s.type.label}${if (s.isPlus) " Plus" else ""}")
        sb.appendLine("  Region             ${s.region.label}")
        sb.appendLine("  Security gen       ${s.securityGeneration}")
        sb.appendLine("  UID                ${s.uid.hex()}")
        sb.appendLine("  Patch info         ${s.patchInfo.hex()}")

        result.fram?.let {
            sb.appendLine()
            sb.appendLine("STATUS")
            sb.appendLine("  State              ${it.state.label}")
            sb.appendLine("  Age                ${it.ageMinutes / 60} h ${it.ageMinutes % 60} min")
            if (it.maxLifeMinutes > 0) {
                sb.appendLine("  Wear duration      ${it.maxLifeMinutes / 1440} days")
            }
            sb.appendLine("  FRAM checksums     ${if (it.crcOk) "OK (decryption verified)" else "FAILED"}")
            if (it.crcOk) sb.appendLine("  Latest raw value   ${it.latestRawGlucose}")
            it.currentMgdl?.let { mgdl ->
                val mmol = mgdl / 18.0
                sb.appendLine(
                    "  Glucose            ${"%.1f".format(Locale.US, mmol)} mmol/L ($mgdl mg/dL)"
                )
            }
        }
        result.framError?.let {
            sb.appendLine()
            sb.appendLine("STATUS")
            sb.appendLine("  FRAM read failed: $it")
        }

        sb.appendLine()
        sb.appendLine("BLUETOOTH PAIRING DATA")
        if (result.bleAddress != null) {
            sb.appendLine("  MAC address        ${result.bleAddress.bleAddress()}")
        } else {
            sb.appendLine("  MAC address        (unknown - tick \"enable BLE streaming\" and rescan)")
        }
        sb.appendLine("  Unlock code        ${result.unlockCode}")
        sb.appendLine("  Next unlock count  ${result.nextUnlockCount}")
        sb.appendLine("  Service / write    FDE3 / F001")
        sb.appendLine("  Notify             F002")
        sb.appendLine()
        sb.appendLine("  Unlock payloads (write to F001, one per connection):")
        for (count in result.nextUnlockCount until result.nextUnlockCount + 3) {
            sb.appendLine("    count $count".padEnd(14) + result.unlockPayload(count).hex())
        }

        if (result.streamingWasEnabled) {
            sb.appendLine()
            sb.appendLine("BLE streaming was (re)enabled during this scan.")
        }

        if (result.notes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("NOTES")
            result.notes.forEach { sb.appendLine("  - $it") }
        }

        return sb.toString().trimEnd()
    }

    private fun parseUnlockCode(text: String?): Long {
        val value = text?.trim()?.toLongOrNull()
        return if (value != null && value in 1..0xFFFFFFFFL) value else DEFAULT_UNLOCK_CODE
    }

    private fun setResultButtonsEnabled(enabled: Boolean) {
        binding.copyButton.isEnabled = enabled
        binding.shareButton.isEnabled = enabled
    }

    private fun copyJsonSilent() {
        val json = lastResult?.toJson()?.toString(2) ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Libre 2 keys", json))
    }

    private fun copyJson() {
        copyJsonSilent()
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareJson() {
        val result = lastResult ?: return
        val body = result.toJson().toString(2) + "\n\n" + scannerUrl()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Libre 2 keys ${result.sensor.serial}")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    /** Wi‑Fi IPv4 of this phone, for the iPhone Zepp companion on the same network. */
    private fun lanIpv4(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in Collections.list(ifaces)) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun scannerUrl(): String {
        val ip = lanIpv4() ?: "PHONE-WIFI-IP"
        return "http://$ip:${ScanHttpServer.PORT}${ScanHttpServer.PATH}"
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun codeKey(serial: String) = "code_${serial.lowercase(Locale.US)}"
    private fun countKey(serial: String) = "count_${serial.lowercase(Locale.US)}"
    private fun macKey(serial: String) = "mac_${serial.lowercase(Locale.US)}"

    companion object {
        const val PREFS = "libre2key"
        const val KEY_SCAN_JSON = "scan_json"
        private const val KEY_LAST_CODE = "last_unlock_code"
        private const val DEFAULT_UNLOCK_CODE = 42L
    }
}
