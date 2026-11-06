package com.vstoianov.libre2key.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Bluetooth LE peripheral that hands the scan JSON to AmazCGM directly,
 * without the Zepp app, the iPhone or Wi‑Fi.
 *
 * Advertises only inside a visibility window. The watch connects, enables
 * notifications on [ScanFrames.DATA_UUID], writes `GET` to
 * [ScanFrames.CONTROL_UUID], receives the frames, and writes `ACK`.
 * All callbacks are handled on the main thread.
 */
class ScanBlePeripheral(
    private val context: Context,
    private val jsonProvider: () -> String?,
    private val listener: Listener
) {
    interface Listener {
        /** [errorCode] is the AdvertiseCallback code for [State.ERROR], else 0. */
        fun onStateChanged(state: State, visibleUntil: Long, errorCode: Int = 0)
        fun onServed()
    }

    enum class State { OFF, STARTING, VISIBLE, BLUETOOTH_OFF, NO_PERMISSION, UNSUPPORTED, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var visibleUntil = 0L
    private var advertisedCrc = -1L
    private var withScanResponse = true
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val notifyEnabled = HashSet<String>()
    private val mtu = HashMap<String, Int>()
    private val pending = HashMap<String, ArrayDeque<ByteArray>>()

    private val windowEnd = Runnable { stop(State.OFF) }

    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return granted(Manifest.permission.BLUETOOTH_ADVERTISE) &&
            granted(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Starts or extends the visibility window. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun makeVisible(windowMs: Long) {
        val json = jsonProvider()?.takeIf { it.isNotBlank() }
        if (json == null) {
            stop(State.OFF)
            return
        }
        if (!hasPermissions()) {
            stop(State.NO_PERMISSION)
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            stop(State.UNSUPPORTED)
            return
        }
        if (!adapter.isEnabled) {
            stop(State.BLUETOOTH_OFF)
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            stop(State.UNSUPPORTED)
            return
        }
        try {
            if (server == null && !openServer()) {
                stop(State.ERROR)
                return
            }
            visibleUntil = System.currentTimeMillis() + windowMs
            main.removeCallbacks(windowEnd)
            main.postDelayed(windowEnd, windowMs)
            // The watch finds devices by advertised name (like ABBOTT<serial>).
            // Android only advertises the adapter name, so rename while visible.
            takeName(adapter)
            val crc = ScanFrames.crc32(json.toByteArray(StandardCharsets.UTF_8))
            // Restart the advert when the scan changed so the CRC is current.
            if (advertising && crc != advertisedCrc) stopAdvertising()
            if (!advertising) {
                // VISIBLE is reported from onStartSuccess, once Android confirms it.
                listener.onStateChanged(State.STARTING, visibleUntil)
                startAdvertising(adv, json)
            } else {
                listener.onStateChanged(State.VISIBLE, visibleUntil)
            }
        } catch (e: SecurityException) {
            stop(State.NO_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "makeVisible", e)
            stop(State.ERROR)
        }
    }

    /** Stops advertising, restores the phone's Bluetooth name and closes the GATT server. */
    @SuppressLint("MissingPermission")
    fun stop(state: State = State.OFF, errorCode: Int = 0) {
        main.removeCallbacks(windowEnd)
        stopAdvertising()
        restoreName()
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        dataChar = null
        notifyEnabled.clear()
        pending.clear()
        mtu.clear()
        visibleUntil = 0L
        listener.onStateChanged(state, 0L, errorCode)
    }

    /** Save the user's Bluetooth name once, then call the phone [ADVERTISED_NAME]. */
    @SuppressLint("MissingPermission")
    private fun takeName(adapter: BluetoothAdapter) {
        try {
            val current = adapter.name
            if (current != null && current != ADVERTISED_NAME && !prefs.contains(KEY_SAVED_NAME)) {
                prefs.edit().putString(KEY_SAVED_NAME, current).apply()
            }
            if (current != ADVERTISED_NAME) adapter.setName(ADVERTISED_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "setName", e)
        }
    }

    /** Put the user's Bluetooth name back. Safe to call when nothing was saved. */
    @SuppressLint("MissingPermission")
    fun restoreName() {
        if (!prefs.contains(KEY_SAVED_NAME)) return
        val saved = prefs.getString(KEY_SAVED_NAME, null)
        try {
            val adapter = manager?.adapter
            if (adapter != null && adapter.isEnabled && adapter.name == ADVERTISED_NAME) {
                adapter.setName(saved)
            }
            prefs.edit().remove(KEY_SAVED_NAME).apply()
        } catch (e: Exception) {
            Log.w(TAG, "restore name", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openServer(): Boolean {
        val gatt = manager?.openGattServer(context, callback) ?: return false
        val service = BluetoothGattService(ScanFrames.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val control = BluetoothGattCharacteristic(
            ScanFrames.CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val data = BluetoothGattCharacteristic(
            ScanFrames.DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        data.addDescriptor(
            BluetoothGattDescriptor(
                ScanFrames.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(control)
        service.addCharacteristic(data)
        if (!gatt.addService(service)) {
            gatt.close()
            return false
        }
        server = gatt
        dataChar = data
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adv: BluetoothLeAdvertiser, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Primary advert: flags 3 + name 2+6 + 16-bit UUID list 4 + service data 9 = 24 bytes.
        // The watch matches the name "AMZCGM" first, like it matches "ABBOTT…" for the sensor.
        val service = ParcelUuid(ScanFrames.SERVICE_UUID)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(service)
            .addServiceData(service, ScanFrames.serviceData(bytes))
            .build()
        // Scan response: manufacturer data for scanners that only look there.
        val scanResponse = if (withScanResponse) {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(ScanFrames.COMPANY_ID, ScanFrames.advertPayload(bytes))
                .build()
        } else {
            null
        }
        advertiser = adv
        advertisedCrc = ScanFrames.crc32(bytes)
        advertising = true
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        advertisedCrc = -1L
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            main.post {
                if (advertising) listener.onStateChanged(State.VISIBLE, visibleUntil)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed $errorCode")
            main.post {
                when {
                    errorCode == ADVERTISE_FAILED_ALREADY_STARTED -> {
                        listener.onStateChanged(State.VISIBLE, visibleUntil)
                    }
                    errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && withScanResponse -> {
                        // Retry once with the primary advert only.
                        withScanResponse = false
                        advertising = false
                        val adv = advertiser
                        val json = jsonProvider()
                        if (adv != null && !json.isNullOrBlank()) startAdvertising(adv, json)
                        else stop(State.ERROR, errorCode)
                    }
                    else -> {
                        advertising = false
                        stop(State.ERROR, errorCode)
                    }
                }
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val key = device.address
                    notifyEnabled.remove(key)
                    pending.remove(key)
                    mtu.remove(key)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            main.post { mtu[device.address] = newMtu }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            main.post {
                if (descriptor.uuid == ScanFrames.CCCD_UUID) {
                    val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                    if (on) notifyEnabled.add(device.address) else notifyEnabled.remove(device.address)
                }
                respond(device, requestId, responseNeeded, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            main.post {
                val on = notifyEnabled.contains(device.address)
                val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                try {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                } catch (_: Exception) {
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Reads are not part of the protocol; answer with the advert bytes
            // so a generic BLE explorer shows something meaningful.
            main.post {
                val json = jsonProvider()?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
                try {
                    server?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ScanFrames.advertPayload(json)
                    )
                } catch (_: Exception) {
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            main.post {
                respond(device, requestId, responseNeeded, value)
                if (characteristic.uuid != ScanFrames.CONTROL_UUID) return@post
                val cmd = value?.toString(StandardCharsets.US_ASCII)?.trim()?.uppercase().orEmpty()
                when {
                    cmd.startsWith(ScanFrames.CMD_GET) -> beginTransfer(device)
                    cmd.startsWith(ScanFrames.CMD_ACK) -> {
                        listener.onServed()
                        // Served: stop advertising, keep the link until the watch drops it.
                        stopAdvertising()
                        main.removeCallbacks(windowEnd)
                        main.postDelayed({ stop(State.OFF) }, CLOSE_AFTER_ACK_MS)
                    }
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post { sendNext(device) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun respond(device: BluetoothDevice, requestId: Int, needed: Boolean, value: ByteArray?) {
        if (!needed) return
        try {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        } catch (_: Exception) {
        }
    }

    private fun beginTransfer(device: BluetoothDevice) {
        val json = jsonProvider()?.takeIf { it.isNotBlank() } ?: return
        val negotiated = mtu[device.address] ?: 23
        // Value is MTU - 3; keep 2 bytes for the frame header.
        val payload = (negotiated - 5).coerceIn(ScanFrames.DEFAULT_PAYLOAD, 180)
        val frames = ScanFrames.frames(json.toByteArray(StandardCharsets.UTF_8), payload)
        pending[device.address] = ArrayDeque(frames)
        sendNext(device)
    }

    @SuppressLint("MissingPermission")
    private fun sendNext(device: BluetoothDevice) {
        val queue = pending[device.address] ?: return
        val frame = queue.removeFirstOrNull()
        if (frame == null) {
            pending.remove(device.address)
            return
        }
        val chr = dataChar ?: return
        val gatt = server ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.notifyCharacteristicChanged(device, chr, false, frame)
            } else {
                @Suppress("DEPRECATION")
                chr.value = frame
                @Suppress("DEPRECATION")
                gatt.notifyCharacteristicChanged(device, chr, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "notify", e)
            pending.remove(device.address)
        }
    }

    companion object {
        private const val TAG = "ScanBlePeripheral"
        /** Name the phone advertises while visible; the watch matches on it. */
        const val ADVERTISED_NAME = "AMZCGM"
        private const val PREFS_NAME = "libre2key_ble"
        private const val KEY_SAVED_NAME = "bt_name_saved"
        const val WINDOW_MS = 10L * 60L * 1000L
        private const val CLOSE_AFTER_ACK_MS = 5_000L

        /** True when the adapter is present and switched on. */
        fun bluetoothOn(context: Context): Boolean {
            val m = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val a: BluetoothAdapter? = m?.adapter
            return try {
                a?.isEnabled == true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
