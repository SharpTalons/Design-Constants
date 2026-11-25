package com.example.app // TODO: your package

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Foreground service so Android keeps the app's background work alive and at normal priority
 * while the person is in another app. Its notification carries the app's mark and up to three
 * buttons: an optional toggle (DrumTrig: Mute / Unmute), Stop and Open.
 *
 * Reference: DrumTrig (KeepAliveService.kt), which runs it while its audio engine is on.
 * Start it with ContextCompat.startForegroundService when the work starts; stop it with
 * stopService when the work stops.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> onToggle?.invoke()
            // Stop closes the app like its own exit; while Locked it only says so.
            ACTION_STOP -> FloatingBubble.requestClose(this)
        }
        val n = build(this, isToggled?.invoke() ?: false)
        if (Build.VERSION.SDK_INT >= 29) {
            // TODO: the type that matches the work (DrumTrig: MICROPHONE; media playback:
            // MEDIA_PLAYBACK). It must match android:foregroundServiceType in the manifest.
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID, n)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // The work is off: the floating button has nothing to come back to.
        FloatingBubble.hide()
        super.onDestroy()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "running"
        private const val ACTION_TOGGLE = "TOGGLE"
        private const val ACTION_STOP = "STOP"

        /**
         * Optional toggle button (DrumTrig: Mute). Set by the view model while it exists; leave
         * [onToggle] null to show only Stop and Open.
         */
        @Volatile var onToggle: (() -> Unit)? = null
        @Volatile var isToggled: (() -> Boolean)? = null

        /** Shows the notification again, for example after the toggle changed inside the app. */
        fun refresh(ctx: Context, toggled: Boolean) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.activeNotifications.none { it.id == ID }) return // the work is off
            nm.notify(ID, build(ctx, toggled))
        }

        private fun build(ctx: Context, toggled: Boolean): Notification {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // Low importance: no sound, no pop-up; it only has to be there.
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running in the background", NotificationManager.IMPORTANCE_LOW))
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
            val open = PendingIntent.getActivity(ctx, 0, launch, flags)
            fun service(action: String, code: Int) =
                PendingIntent.getService(ctx, code, Intent(ctx, KeepAliveService::class.java).setAction(action), flags)
            fun action(icon: Int, title: String, pi: PendingIntent) =
                Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, pi).build()
            val b = Notification.Builder(ctx, CHANNEL)
                // TODO: say what it is doing, in the app's words (DrumTrig: "DrumTrig is listening" / "…, muted").
                .setContentTitle(if (toggled) "${FloatingBubble.APP} is running, muted" else "${FloatingBubble.APP} is running")
                .setContentText("Tap to open")
                .setSmallIcon(R.drawable.ic_notification) // TODO: a white-on-transparent small icon
                .setColor(0xFF16B98A.toInt())             // TODO: the app's accent colour
                .setContentIntent(open)
                .setOngoing(true)
            // TODO: the toggle's icons and words (DrumTrig: volume icons, "Mute" / "Unmute").
            if (onToggle != null) b.addAction(action(R.drawable.ic_toggle, if (toggled) "Unmute" else "Mute", service(ACTION_TOGGLE, 1)))
            b.addAction(action(R.drawable.ic_power, "Stop", service(ACTION_STOP, 2)))
            b.addAction(action(R.drawable.ic_open, "Open", open))
            return b.build()
        }
    }
}
