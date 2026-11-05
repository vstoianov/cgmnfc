package com.vstoianov.libre2key.watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SendResult {
    data class Success(val serial: String, val transport: String) : SendResult
    data class Failure(val reason: String) : SendResult
}

object AmazCgmSender {
    const val SERVICE_NAME = "AmazCGMScan"
    const val PHONE_PORT = 17580
    const val WATCH_PORT = 17581
    const val PREFIX_SCAN = "AMAZCGM_SCAN"
    const val PREFIX_PULL = "AMAZCGM_PULL"
    const val PREFIX_OK = "AMAZCGM_OK"

    val PUSH_UUID: UUID = UUID.fromString("d8b4c9a0-6e3f-4b8e-9c12-42616d617a43")
    val PULL_UUID: UUID = UUID.fromString("d8b4c9a1-6e3f-4b8e-9c12-42616d617a43")

    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Sends scan JSON directly to AmazCGM on the Wear OS watch over Bluetooth RFCOMM
     * and Wi-Fi UDP broadcast in parallel.
     */
    fun send(context: Context, rawJson: String, onDone: (SendResult) -> Unit) {
        val appCtx = context.applicationContext
        val compactJson = try {
            JSONObject(rawJson).toString()
        } catch (_: Exception) {
            rawJson.replace('\n', ' ').replace('\r', ' ').trim()
        }
        val fallbackSerial = try {
            JSONObject(compactJson).optString("serial", "sensor")
        } catch (_: Exception) {
            "sensor"
        }

        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(2)

        fun finishSuccess(serial: String, transport: String) {
            if (completed.compareAndSet(false, true)) {
                mainHandler.post {
                    onDone(SendResult.Success(serial.ifBlank { fallbackSerial }, transport))
                }
            }
        }

        // 1. Bluetooth Classic RFCOMM push to bonded Wear OS watch
        Thread({
            try {
                val serial = trySendBluetooth(appCtx, compactJson)
                if (serial != null) {
                    finishSuccess(serial, "Bluetooth")
                }
            } finally {
                latch.countDown()
            }
        }, "amazcgm-bt-send").apply {
            isDaemon = true
            start()
        }

        // 2. Wi-Fi UDP broadcast push to port 17581
        Thread({
            try {
                val serial = trySendUdp(compactJson) { completed.get() }
                if (serial != null) {
                    finishSuccess(serial, "Wi‑Fi")
                }
            } finally {
                latch.countDown()
            }
        }, "amazcgm-udp-send").apply {
            isDaemon = true
            start()
        }

        // Wait for both workers or timeout
        Thread({
            try {
                latch.await(4500L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            if (completed.compareAndSet(false, true)) {
                mainHandler.post {
                    onDone(
                        SendResult.Failure(
                            "Could not reach AmazCGM. Open AmazCGM on the watch (Bluetooth paired or same Wi‑Fi) and try again."
                        )
                    )
                }
            }
        }, "amazcgm-send-wait").apply {
            isDaemon = true
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun trySendBluetooth(context: Context, compactJson: String): String? {
        if (!hasBluetoothPermission(context)) return null
        val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bt?.adapter?.takeIf { it.isEnabled } ?: return null
        val bonded = adapter.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) return null

        val sorted = bonded.sortedByDescending { dev -> watchScore(dev) }
        val payload = "$PREFIX_SCAN $compactJson\n".toByteArray(StandardCharsets.UTF_8)

        for (device in sorted) {
            try {
                device.createInsecureRfcommSocketToServiceRecord(PUSH_UUID).use { socket ->
                    socket.connect()
                    socket.outputStream.write(payload)
                    socket.outputStream.flush()
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
                    val reply = reader.readLine()?.trim().orEmpty()
                    if (reply.startsWith(PREFIX_OK)) {
                        return reply.substringAfter(':', "")
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun watchScore(device: BluetoothDevice): Int {
        var score = 0
        try {
            if (device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE) {
                score += 100
            }
            val name = device.name?.lowercase(Locale.US).orEmpty()
            if (name.contains("watch") || name.contains("oneplus") || name.contains("oppo") || name.contains("wear")) {
                score += 50
            }
        } catch (_: Exception) {
        }
        return score
    }

    private fun trySendUdp(compactJson: String, isCancelled: () -> Boolean): String? {
        return try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 700
                val payload = "$PREFIX_SCAN\n$compactJson".toByteArray(StandardCharsets.UTF_8)
                val targets = broadcastAddresses()
                val buf = ByteArray(1024)
                for (attempt in 0 until 3) {
                    if (isCancelled()) return null
                    for (addr in targets) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, addr, WATCH_PORT))
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        val replyPkt = DatagramPacket(buf, buf.size)
                        sock.receive(replyPkt)
                        val reply = String(replyPkt.data, replyPkt.offset, replyPkt.length, StandardCharsets.UTF_8).trim()
                        if (reply.startsWith(PREFIX_OK)) {
                            return reply.substringAfter(':', "")
                        }
                    } catch (_: Exception) {
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val set = LinkedHashSet<InetAddress>()
        try {
            set.add(InetAddress.getByName("255.255.255.255"))
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return set.toList()
            for (iface in Collections.list(ifaces)) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) set.add(bcast)
                }
            }
        } catch (_: Exception) {
        }
        return set.toList()
    }
}

/**
 * Bluetooth RFCOMM server on the phone (PULL_UUID) so the watch can also pull
 * the latest scan JSON on demand.
 */
class RfcommPullServer(
    private val context: Context,
    private val jsonProvider: () -> String?,
    private val onServed: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!AmazCgmSender.hasBluetoothPermission(context)) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                while (running.get()) {
                    if (!AmazCgmSender.hasBluetoothPermission(context)) break
                    val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = bt?.adapter?.takeIf { it.isEnabled }
                    if (adapter == null) {
                        Thread.sleep(3000)
                        continue
                    }
                    var srv: BluetoothServerSocket? = null
                    try {
                        srv = adapter.listenUsingInsecureRfcommWithServiceRecord(
                            AmazCgmSender.SERVICE_NAME,
                            AmazCgmSender.PULL_UUID
                        )
                        serverSocket = srv
                        while (running.get()) {
                            val client = srv.accept() ?: break
                            Thread({ handleClient(client) }, "rfcomm-pull-client").apply {
                                isDaemon = true
                                start()
                            }
                        }
                    } catch (_: Exception) {
                        if (!running.get()) break
                        Thread.sleep(2000)
                    } finally {
                        try {
                            srv?.close()
                        } catch (_: Exception) {
                        }
                        serverSocket = null
                    }
                }
            } finally {
                running.set(false)
            }
        }, "rfcomm-pull-srv").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(socket: BluetoothSocket) {
        socket.use { sock ->
            try {
                val json = jsonProvider() ?: return
                val compact = try {
                    JSONObject(json).toString()
                } catch (_: Exception) {
                    json.replace('\n', ' ').replace('\r', ' ').trim()
                }
                val line = "${AmazCgmSender.PREFIX_SCAN} $compact\n"
                sock.outputStream.write(line.toByteArray(StandardCharsets.UTF_8))
                sock.outputStream.flush()
                onServed()
            } catch (_: Exception) {
            }
        }
    }
}
