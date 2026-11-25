package com.example.app // TODO: your package

// The update check's view-model code. Merge these members into the app's ViewModel
// (DrumTrig keeps them in AppViewModel). They need: an Application context [ctx],
// SharedPreferences [prefs], a [toast] state for short messages, CURRENT_VERSION
// (BuildConfig.VERSION_NAME, the same constant the changelog skill uses), a small
// notification icon (R.drawable.ic_notification), and the main activity calling
// setOnScreen(true) in onStart and setOnScreen(false) in onStop.

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

val CURRENT_VERSION: String = BuildConfig.VERSION_NAME

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs: SharedPreferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** A short message for the person (shown as a toast or snackbar by the UI). */
    var toast by mutableStateOf<String?>(null)

    init {
        // Once per process start. Skipped under Robolectric so tests never touch the network.
        if (android.os.Build.FINGERPRINT != "robolectric") checkForUpdates(manual = false)
    }

    /** A newer release, once a check has found one. */
    var update by mutableStateOf<Updates.Release?>(null); private set
    /** What the last check found, for Settings: null = not checked yet. */
    var updateStatus by mutableStateOf<String?>(null); private set
    var updateChecking by mutableStateOf(false); private set

    /**
     * Asks GitHub for the newest release. The automatic check ([manual] false) runs each time
     * the app opens, stays silent when offline (using the last answer), and asks once per
     * start; "Check for updates" always checks, and always says what it found.
     */
    fun checkForUpdates(manual: Boolean) {
        if (updateChecking) return
        val now = System.currentTimeMillis()
        updateChecking = true
        viewModelScope.launch(Dispatchers.IO) {
            val r = Updates.latest()
            updateChecking = false
            if (r == null) {
                updateStatus = "Couldn't reach GitHub. Check your connection and try again."
                if (manual) toast = "Couldn't check for updates."
                // Offline at start: the last answer still counts, so a known update is offered.
                if (!manual) prefs.getString("updateFound", null)?.let { v ->
                    if (Updates.isNewer(v, CURRENT_VERSION)) {
                        update = Updates.Release(
                            v, prefs.getString("updatePage", null) ?: "https://github.com/${Updates.RELEASES_REPO}/releases/latest",
                            prefs.getString("updateApk", null) ?: Updates.apkUrl(v), prefs.getLong("updateApkBytes", 0L),
                        )
                        maybePromptUpdate()
                    }
                }
                return@launch
            }
            prefs.edit().putLong("updateCheckedAt", now).putString("updateFound", r.version).putString("updatePage", r.page)
                .putString("updateApk", r.apk).putLong("updateApkBytes", r.apkBytes).apply()
            if (Updates.isNewer(r.version, CURRENT_VERSION)) {
                update = r
                updateStatus = "${Updates.APP} ${r.version} is available. You have $CURRENT_VERSION."
                // A manual check asks again even if this version was ignored before.
                if (manual) updatePrompt = true else maybePromptUpdate()
            } else {
                update = null
                updateStatus = "You have the newest version, $CURRENT_VERSION."
                if (manual) toast = "${Updates.APP} is up to date."
            }
        }
    }

    /**
     * The "update available" dialog: shown once per app start for a new version not ignored
     * yet. The UI shows it only while no onboarding, tour or permission screen is up.
     */
    var updatePrompt by mutableStateOf(false)
    /** 0..1 while an update downloads, else null. */
    var updateProgress by mutableStateOf<Float?>(null); private set
    /** The download goes on with the dialog closed (Background). */
    var updateInBackground by mutableStateOf(false); private set
    private var updateJob: kotlinx.coroutines.Job? = null
    /** A finished download waiting for the app to be on screen, so Android's installer can open. */
    private var updateReady: File? = null
    /** The app is on screen (the main activity between onStart and onStop). */
    private var onScreen = true

    /** After a check: ask about [update], unless the person chose Ignore for that exact version. */
    fun maybePromptUpdate() {
        val u = update ?: return
        if (prefs.getString("updateIgnored", null) != u.version) updatePrompt = true
    }

    /** Ignore: no more asking at start for this version; Settings still offers it. The next version asks again. */
    fun ignoreUpdate() {
        update?.let { prefs.edit().putString("updateIgnored", it.version).apply() }
        updatePrompt = false
    }

    /**
     * Update: downloads the new APK and hands it to Android's installer, which asks to install
     * it (the first time, Android also asks to allow installs from this app). The new version
     * is signed with the same key, so it installs over this one and keeps everything.
     * While it downloads, the person can Cancel it or send it to the Background.
     */
    fun installUpdate() {
        val u = update ?: return
        if (updateProgress != null) return
        updateProgress = 0f
        updateInBackground = false
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            // Only this folder is shared through the FileProvider (res/xml/file_paths.xml).
            val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, "${Updates.APP}-${u.version}.apk")
            val job = coroutineContext[kotlinx.coroutines.Job]
            try {
                var shown = -1
                // A hard cap well above the real size; the asset's size drives the progress
                // when the server sends no Content-Length.
                Net.download(
                    u.apk, apk, 200L * 1024 * 1024, u.apkBytes.takeIf { it > 0 } ?: 12_000_000L, "update",
                    cancelled = { job?.isActive == false },
                ) { f ->
                    // Only whole percents reach the screen: fewer redraws while it downloads.
                    val pc = (f * 100).toInt()
                    if (pc != shown) { shown = pc; updateProgress = f }
                }
                updatePrompt = false
                updateInBackground = false
                // Android's installer can only open over the app while it is on screen;
                // otherwise a notification says the update is ready, and it opens on return.
                if (onScreen) openInstaller(apk) else { updateReady = apk; notifyUpdateReady(u.version) }
            } catch (e: Net.Cancelled) {
                // Cancel already said so; Net.download deleted the partial file.
            } catch (e: kotlinx.coroutines.CancellationException) {
                apk.delete()
            } catch (e: IllegalStateException) {
                toast = e.message // Net.download's messages are written for the person.
            } catch (e: Exception) {
                toast = "Couldn't open Android's installer. Download the update from the release page instead."
            } finally {
                updateProgress = null
                updateInBackground = false
                updateJob = null
            }
        }
    }

    /** Cancel: stops the download and deletes what came so far. Nothing is installed or changed. */
    fun cancelUpdate() {
        val j = updateJob ?: return
        j.cancel()
        updateProgress = null
        updateInBackground = false
        updatePrompt = false
        toast = "Update cancelled. Nothing was changed."
    }

    /** Background: closes the dialog; the download goes on and Android's installer opens when it's done. */
    fun updateInBackground() {
        if (updateProgress == null) return
        updateInBackground = true
        updatePrompt = false
        toast = "Downloading in the background. Android will ask you to install it when it's done."
    }

    /** The main activity: the app came on screen or left it. A finished update opens its installer on return. */
    fun setOnScreen(on: Boolean) {
        onScreen = on
        if (on) updateReady?.let { apk ->
            updateReady = null
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(UPDATE_NOTIFICATION)
            if (apk.exists()) openInstaller(apk)
        }
    }

    private fun openInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            toast = "Couldn't open Android's installer. Download the update from the release page instead."
        }
    }

    /** "<App> X is ready to install": tapping it opens the app, which opens the installer. */
    private fun notifyUpdateReady(version: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel("updates", "Updates", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            val pi = android.app.PendingIntent.getActivity(ctx, 3, launch, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(
                UPDATE_NOTIFICATION,
                android.app.Notification.Builder(ctx, "updates")
                    .setContentTitle("${Updates.APP} $version is ready to install")
                    // TODO: the app's data in its own words, as in the dialog.
                    .setContentText("Tap to open ${Updates.APP} and install it. Your settings are kept.")
                    .setSmallIcon(R.drawable.ic_notification) // TODO: the app's small notification icon
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Exception) {
            // No notification permission: it still opens the next time the app comes on screen.
        }
    }
}

/** The "update ready to install" notification's id (any id the app does not use elsewhere). */
private const val UPDATE_NOTIFICATION = 7
