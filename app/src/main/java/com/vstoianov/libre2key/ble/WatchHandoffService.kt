package com.vstoianov.libre2key.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.vstoianov.libre2key.MainActivity
import com.vstoianov.libre2key.R

/**
 * Foreground service that keeps [ScanBlePeripheral] advertising while this app
 * is in the background, so AmazCGM can pull the scan over Bluetooth LE.
 * Each start opens a fresh [ScanBlePeripheral.WINDOW_MS] window. Stops after
 * 30 minutes without a transfer.
 */
class WatchHandoffService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var ble: ScanBlePeripheral? = null
    private val idleStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        touchState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !hasPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val peripheral = ble ?: ScanBlePeripheral(
            context = applicationContext,
            jsonProvider = { prefs.getString(MainActivity.KEY_SCAN_JSON, null) },
            listener = object : ScanBlePeripheral.Listener {
                override fun onStateChanged(state: ScanBlePeripheral.State, visibleUntil: Long, errorCode: Int) {
                    prefs.edit()
                        .putString(MainActivity.KEY_BLE_STATE, state.name)
                        .putLong(MainActivity.KEY_BLE_UNTIL, visibleUntil)
                        .putInt(MainActivity.KEY_BLE_ERROR, errorCode)
                        .apply()
                }

                override fun onServed() {
                    prefs.edit().putLong(MainActivity.KEY_LAST_SERVED, System.currentTimeMillis()).apply()
                    bumpIdleTimer()
                }
            }
        ).also { ble = it }
        peripheral.makeVisible(ScanBlePeripheral.WINDOW_MS)
        bumpIdleTimer()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleStop)
        ble?.stop()
        ble?.restoreName()
        ble = null
        running = false
        touchState()
        super.onDestroy()
    }

    /** Lets the activity re-render when the service starts or stops. */
    private fun touchState() {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(MainActivity.KEY_HANDOFF_STATE, System.currentTimeMillis())
            .apply()
    }

    private fun bumpIdleTimer() {
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun startInForeground() {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WatchHandoffService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(getString(R.string.handoff_notification_title))
            .setContentText(getString(R.string.handoff_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.handoff_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.handoff_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_STOP = "com.vstoianov.libre2key.STOP_WATCH_HANDOFF"
        private const val CHANNEL_ID = "watch_handoff"
        private const val NOTIFY_ID = 17580
        private const val IDLE_STOP_MS = 30L * 60L * 1000L

        /** True while the service is alive in this process. */
        @Volatile
        var running = false
            private set

        /** connectedDevice services need these on Android 12+, or start fails. */
        fun hasPermissions(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }

        /** Starts or re-opens the visibility window. Returns false without permissions. */
        fun start(context: Context): Boolean {
            if (!hasPermissions(context)) return false
            val intent = Intent(context, WatchHandoffService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchHandoffService::class.java))
        }
    }
}
