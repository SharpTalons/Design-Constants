package com.example.app.ui // TODO: your package

// The Settings row that turns the floating button on (DrumTrig: Settings › On stage › Keep
// running › Floating button). Material 3; swap in the app's own row and switch.

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.app.AppViewModel
import com.example.app.FloatingBubble

/**
 * On only when both the setting and Android's permission are on. Turning it on without the
 * permission opens Android's page for it; the row says it is waiting until the person comes
 * back with it allowed ([AppViewModel.refreshPermissions] in onResume updates overlayOk).
 */
@Composable
fun FloatingButtonRow(vm: AppViewModel) {
    val ctx = LocalContext.current
    val c = vm.cfg
    val app = FloatingBubble.APP
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Floating button", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    c.bubble && vm.overlayOk -> "When you leave with $app running, a bubble stays on screen. Tap it to come back; drag it onto ✕ to close $app."
                    c.bubble -> "Waiting for Android's “Display over other apps”. Turn it on again to open that page."
                    else -> "Off. A bubble over other apps that brings you back to $app. Turning it on asks Android for “Display over other apps”."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = c.bubble && vm.overlayOk,
            onCheckedChange = {
                if (c.bubble && vm.overlayOk) {
                    vm.update { it.copy(bubble = false) }
                } else {
                    vm.update { it.copy(bubble = true) }
                    if (!vm.overlayOk) openOverlaySettings(ctx)
                }
            },
        )
    }
}

/** Android's "Display over other apps" page for this app, or the general list if that is missing. */
fun openOverlaySettings(ctx: Context) {
    val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:" + ctx.packageName))
    try { ctx.startActivity(direct) } catch (_: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) { }
    }
}
