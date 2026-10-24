package com.bitperfect.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bitperfect.relay.Engine
import com.bitperfect.relay.R
import com.bitperfect.relay.data.LibraryCatalog
import com.bitperfect.relay.data.LibraryManager
import com.bitperfect.relay.data.LocalLibFile
import com.bitperfect.relay.data.RelayLibrary
import com.bitperfect.relay.data.AppSettings
import com.bitperfect.relay.output.usb.UsbDacOutput
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 9 settings screen.
 *
 * Shows the full OSS catalog (name / version / license). The swappable
 * native C library (libusb) additionally exposes a "local swap" section:
 * user-placed `.so` candidates under `files/libs/<key>/<version>/<abi>/` for
 * the running ABI, each with its SHA-256 integrity fingerprint. Picking a
 * candidate (or "bundled default") applies the swap immediately — the engine
 * dlopen-swaps the `.so`, auto-restoring the previous version on failure
 * (docs/ref/libs.md contract). Non-swappable entries (all
 * managed/toolchain libs) are display-only.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { LibraryManager(context) }

    var discovered by remember { mutableStateOf<Map<String, List<LocalLibFile>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val result = LinkedHashMap<String, List<LocalLibFile>>()
        LibraryCatalog.swappable.forEach { lib ->
            result[lib.key] = manager.discover(lib.key)
        }
        discovered = result
    }

    val selected = manager.selected.collectAsState().value

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ── Top bar: back + title ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = context.getString(R.string.ui_settings_back) },
            ) {
                Text(context.getString(R.string.ui_settings_back))
            }
            Text(
                context.getString(R.string.ui_settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(context.getString(R.string.ui_settings_about), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        DigitalVolumeSetting()
        Spacer(Modifier.height(16.dp))

        Text(context.getString(R.string.ui_libs_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        // Per-library apply status (set on pick / revert); cleared on next action.
        var applyStatus by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }
        fun apply(key: String, version: String?) {
            val r = manager.selectAndApply(key, version)
            val detail = if (r == 0) {
                ""
            } else {
                Engine.libLastError().ifBlank { context.getString(R.string.ui_libs_fail_unknown) }
            }
            applyStatus = applyStatus.toMutableMap().apply {
                put(key, (r == 0) to if (r == 0)
                    context.getString(R.string.ui_libs_apply_ok, version ?: context.getString(R.string.ui_libs_revert))
                    else context.getString(R.string.ui_libs_apply_fail, detail))
            }
        }

        LibraryCatalog.all.forEach { lib ->
            LibraryCard(
                lib = lib,
                localFiles = discovered[lib.key] ?: emptyList(),
                selectedVersion = selected[lib.key],
                status = applyStatus[lib.key],
                onPick = { file -> apply(lib.key, file.version) },
                onRevert = { apply(lib.key, null) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            context.getString(R.string.ui_libs_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Digital volume switch (task 2026-10-24): off by default, labeled not bit-perfect, and enabled
 * only while the connected DAC has no hardware (Feature Unit) volume.
 */
@Composable
private fun DigitalVolumeSetting() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val on by settings.digitalVolume.collectAsState()
    // null = no DAC with permission, true = hardware volume, false = none.
    var dacHasHw by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        dacHasHw = withContext(Dispatchers.IO) {
            val um = context.getSystemService(UsbManager::class.java)
            val dev = um.deviceList.values.firstOrNull { d ->
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 1 && d.getInterface(it).interfaceSubclass == 2 }
            } ?: return@withContext null
            if (!um.hasPermission(dev)) return@withContext null
            val conn = um.openDevice(dev) ?: return@withContext null
            try { UsbDacOutput.probe(context, dev, conn)?.hwVolume } finally { conn.close() }
        }
    }
    val enabled = dacHasHw == false
    val label = context.getString(R.string.ui_digital_volume)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { settings.setDigitalVolume(it) })
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null, enabled = enabled)
            }
            Text(
                context.getString(
                    when (dacHasHw) {
                        null -> R.string.ui_digital_volume_nodac
                        true -> R.string.ui_digital_volume_hw
                        false -> R.string.ui_digital_volume_desc
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryCard(
    lib: RelayLibrary,
    localFiles: List<LocalLibFile>,
    selectedVersion: String?,
    status: Pair<Boolean, String>?,
    onPick: (LocalLibFile) -> Unit,
    onRevert: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Header: name + badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lib.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${lib.version} · ${lib.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Badge(lib.swappable)
            }

            if (lib.swappable) {
                Spacer(Modifier.height(8.dp))
                SwapSection(lib, localFiles, selectedVersion, status, onPick, onRevert)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    context.getString(R.string.ui_libs_no_swap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwapSection(
    lib: RelayLibrary,
    localFiles: List<LocalLibFile>,
    selectedVersion: String?,
    status: Pair<Boolean, String>?,
    onPick: (LocalLibFile) -> Unit,
    onRevert: () -> Unit,
) {
    val context = LocalContext.current
    Column {
        // Apply status from the last pick / revert action (success or failure).
        if (status != null) {
            Text(
                status.second,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.first) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
        }

        Text(context.getString(R.string.ui_libs_local), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))

        if (localFiles.isEmpty()) {
            Text(
                context.getString(R.string.ui_libs_local_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        localFiles.forEach { file ->
            val isSel = file.version == selectedVersion
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .background(
                        if (isSel) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent
                    )
                    .clickable { onPick(file) }
                    .semantics {
                        contentDescription =
                            "${file.version} ${file.sizeBytes} bytes SHA-256 ${file.sha256} " +
                                if (isSel) "recorded" else "not recorded"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(file.version, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        formatSize(file.sizeBytes) + " · " +
                            context.getString(R.string.ui_libs_sha, file.sha256.take(16) + "…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSel) {
                    Text("●", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        if (selectedVersion != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                context.getString(R.string.ui_libs_selected, selectedVersion) + " — " +
                    context.getString(R.string.ui_libs_selected_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRevert) {
                Text(context.getString(R.string.ui_libs_revert))
            }
        }
    }
}

@Composable
private fun Badge(swappable: Boolean) {
    val context = LocalContext.current
    Text(
        if (swappable) context.getString(R.string.ui_libs_swappable)
        else context.getString(R.string.ui_libs_readonly),
        style = MaterialTheme.typography.labelSmall,
        color = if (swappable) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000L) "%.1f MB".format(bytes / 1_000_000.0)
    else if (bytes >= 1_000L) "${bytes / 1_000} KB"
    else "$bytes B"
