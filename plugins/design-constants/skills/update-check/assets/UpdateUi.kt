package com.example.app.ui // TODO: your package

// The two places the update check shows up: the prompt over the main screen, and a row in
// Settings › About. Material 3 components; swap in the app's own dialog, row and button
// components where it has them (DrumTrig uses its SettingRow and Pill).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app.AppViewModel
import com.example.app.CURRENT_VERSION
import com.example.app.Updates

/**
 * Mount once, at the root of the main screen:
 *
 *     val upd = vm.update
 *     if (vm.updatePrompt && upd != null && !firstRunFlowShowing) UpdateDialog(vm, upd)
 *
 * Update downloads and opens Android's installer; Ignore stops asking for this version
 * (Settings still offers it); Back asks again next start. While downloading it shows a
 * percentage and a bar, and its buttons become Background (close the dialog, keep
 * downloading) and Cancel (stop and delete it). Back while downloading means Background.
 */
@Composable
fun UpdateDialog(vm: AppViewModel, u: Updates.Release) {
    val prog = vm.updateProgress
    AlertDialog(
        onDismissRequest = { if (prog == null) vm.updatePrompt = false else vm.updateInBackground() },
        title = { Text("${Updates.APP} ${u.version} is available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (prog != null) "Downloading… ${(prog * 100).toInt()}%. Android will then ask you to install it."
                    else "You have $CURRENT_VERSION. Update downloads the new version" +
                        (if (u.apkBytes > 0) " (${(u.apkBytes + 500_000) / 1_000_000} MB)" else "") +
                        // TODO: name the app's own data, in its own words (DrumTrig: "instruments, samples, EQs and settings").
                        " and Android asks you to install it. Your settings are kept.",
                )
                if (prog != null) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (prog != null) TextButton(onClick = { vm.updateInBackground() }) { Text("Background", fontWeight = FontWeight.Bold) }
            else TextButton(onClick = { vm.installUpdate() }) { Text("Update", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            if (prog != null) TextButton(onClick = { vm.cancelUpdate() }) { Text("Cancel") }
            else TextButton(onClick = { vm.ignoreUpdate() }) { Text("Ignore") }
        },
    )
}

/** The first row of Settings › About: the installed version, what the last check found, and one action. */
@Composable
fun UpdateRow(vm: AppViewModel) {
    val u = vm.update
    val prog = vm.updateProgress
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${Updates.APP} $CURRENT_VERSION", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    prog != null -> "Downloading ${u?.version ?: "the update"}… ${(prog * 100).toInt()}%"
                    vm.updateStatus != null -> vm.updateStatus!!
                    else -> "New versions are checked on GitHub each time ${Updates.APP} opens. Nothing about you or your phone is sent."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            prog != null -> OutlinedButton(onClick = { vm.cancelUpdate() }) { Text("Cancel") }
            u != null -> Button(onClick = { vm.installUpdate() }) { Text("Update to ${u.version}") }
            else -> OutlinedButton(onClick = { vm.checkForUpdates(manual = true) }, enabled = !vm.updateChecking) {
                Text(if (vm.updateChecking) "Checking…" else "Check for updates")
            }
        }
    }
}
