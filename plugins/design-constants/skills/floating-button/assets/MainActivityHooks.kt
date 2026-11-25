package com.example.app // TODO: your package

// The main activity's part in the floating button. Merge these members into the app's
// existing activity (DrumTrig: MainActivity.kt). The view model needs:
//   leftApp()            show the button if the work is on, the setting is on, the screen is on
//   refreshPermissions() re-read Settings.canDrawOverlays into an observable overlayOk
//   exitApp()            stop the work and the service, right away
//   cfg.locked           only if the app has a Lock (else drop lockedForBubble)

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... setContent { App(vm) } ...
        // Dropping the floating button on its ✕ closes the app, the same as its own exit.
        FloatingBubble.onClose = closeFromBubble
        FloatingBubble.isLocked = lockedForBubble
    }

    private val lockedForBubble: () -> Boolean = { vm.cfg.locked }

    private val closeFromBubble: () -> Unit = {
        vm.exitApp()
        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's "Display over other apps" page: the switch in Settings follows.
        vm.refreshPermissions()
    }

    /**
     * True while a screen this app opened itself is in front (a file picker, a permission page
     * in Android settings): leaving for one of those is not leaving the app, so no button.
     * Set for every startActivity / startActivityForResult the app makes; Activity Result API
     * launchers go through startActivityForResult too.
     */
    private var ownScreenOpen = false

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ownScreenOpen = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * While a screen the app opened is in front and the button is not yet allowed (most likely
     * Android's "Display over other apps" page), look once a second for the permission. The
     * moment it turns on, the person has in effect left the app for Android settings, so the
     * button shows right away instead of only after the next visit. Stops on return, or after
     * [OVERLAY_WATCH_MS].
     */
    private val overlayWatch = object : Runnable {
        var until = 0L
        override fun run() {
            if (FloatingBubble.allowed(this@MainActivity)) {
                vm.refreshPermissions()
                vm.leftApp()
            } else if (SystemClock.uptimeMillis() < until) {
                main.postDelayed(this, 1000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ownScreenOpen = false
        main.removeCallbacks(overlayWatch)
        // Back in the app: the button's job is done.
        FloatingBubble.hide()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        if (!ownScreenOpen) {
            vm.leftApp()
        } else if (!FloatingBubble.allowed(this)) {
            overlayWatch.until = SystemClock.uptimeMillis() + OVERLAY_WATCH_MS
            main.postDelayed(overlayWatch, 1000)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(overlayWatch)
        // Only this screen's own hooks: a newer screen may already have set its own.
        if (FloatingBubble.onClose === closeFromBubble) FloatingBubble.onClose = null
        if (FloatingBubble.isLocked === lockedForBubble) FloatingBubble.isLocked = null
        super.onDestroy()
    }

    private companion object {
        const val OVERLAY_WATCH_MS = 5 * 60_000L
    }
}

// In the view model (DrumTrig: AppViewModel.leftApp):
//
//     /** The person left the app (Home, Recents, another app) with the work on: show the button. Not when the screen just turned off. */
//     fun leftApp() {
//         val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
//         if (running && cfg.bubble && pm.isInteractive && FloatingBubble.allowed(ctx)) FloatingBubble.show(ctx)
//     }
//
// And call FloatingBubble.flash() wherever the app's "something happened" is detected; it
// does nothing unless the button is showing.
