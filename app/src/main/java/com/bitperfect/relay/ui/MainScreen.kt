package com.bitperfect.relay.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Canvas
import com.bitperfect.relay.Engine
import com.bitperfect.relay.output.usb.UsbDacInfo
import com.bitperfect.relay.output.usb.UsbDacOutput
import com.bitperfect.relay.R
import com.bitperfect.relay.data.AppEntry
import com.bitperfect.relay.data.AppSettings
import androidx.compose.ui.graphics.toArgb
import com.bitperfect.relay.service.RelayMeter
import com.bitperfect.relay.data.OutputSelection
import com.bitperfect.relay.service.RelayService
import com.bitperfect.relay.service.RelayVolume
import com.bitperfect.relay.service.VolumeState
import com.bitperfect.relay.diag.AppLog
import com.bitperfect.relay.diag.Diagnostics
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.bitperfect.relay.capture.CaptureCheck
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

// App-private action for the USB permission result broadcast.
private const val ACTION_USB_PERMISSION = "com.bitperfect.relay.USB_PERMISSION"

private val FALLBACK_RATES = listOf(44100, 48000, 96000, 192000)

/**
 * Stage 7 메인 화면.
 *
 * 카드1: 앱 리스트 (추가/삭제/단일 선택/앱별 샘플레이트 — DataStore로 영속화)
 * 카드2: 출력장치. No DAC: "없음". DAC connected: its facts and a "use" switch.
 *   Switch on asks for USB permission; it is locked while relaying.
 * 시작: app selected and switch on → permissions → MediaProjection consent → ACTION_START.
 *   Otherwise the status line says what is missing.
 * 정지: changing or removing the selected app stops the relay.
 */
@Composable
fun MainScreen(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectionManager = context.getSystemService(MediaProjectionManager::class.java)!!
    val usbManager = context.getSystemService(UsbManager::class.java)!!
    val settings = remember { AppSettings(context) }

    // ── 영속 상태 (DataStore) ──
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var selectedOutput by remember { mutableStateOf<OutputSelection?>(null) }
    val running by RelayService.relaying.collectAsState()
    val relayRate by RelayService.relayRate.collectAsState()
    val captureSilent by RelayService.captureSilent.collectAsState()
    val volume by RelayVolume.state.collectAsState()
    val offerDigital by RelayVolume.offerDigital.collectAsState()
    var diagText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(context.getString(R.string.ui_status_ready)) }

    LaunchedEffect(Unit) {
        settings.apps.collect { apps = it }
    }
    LaunchedEffect(Unit) {
        settings.selectedApp.collect { selectedApp = it }
    }
    LaunchedEffect(Unit) {
        settings.selectedOutput.collect {
            // A saved Bluetooth selection from before 2026-10-11 is no longer an output.
            if (it != null && it.type != "usb") settings.setSelectedOutput(null) else selectedOutput = it
        }
    }

    // ── 서비스 상태 브로드캐스트 ──
    val statusReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != RelayService.ACTION_STATUS) return
                val s = intent.getStringExtra(RelayService.EXTRA_STATUS) ?: return
                status = s
            }
        }
    }
    DisposableEffect(Unit) {
        context.registerReceiver(statusReceiver, IntentFilter(RelayService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(statusReceiver) } }
    }

    // ── USB attach/detach: 트리 자동 갱신 + 선택 장치 소멸 시 처리 ──
    var usbDevices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }

    fun refreshUsb() {
        val found = usbManager.deviceList.values.filter { isUsbAudioDac(it) }.sortedBy { it.deviceName }
        usbDevices = found
        // The selection survives an unplug (ui-spec: re-plug re-registers the DAC); a running
        // relay is stopped by the service's detach receiver.
    }

    val usbAttachReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        refreshUsb()
                        // Re-plug of the selected DAC: ask for permission again (ui-spec). Match by
                        // vendor/product only: the serial is unreadable until permission is granted.
                        val sel = selectedOutput
                        val dev = usbDevices.firstOrNull {
                            sel != null && sel.id.startsWith("${it.vendorId}:${it.productId}:")
                        }
                        if (dev != null && !usbManager.hasPermission(dev)) {
                            usbManager.requestPermission(dev, usbPermissionIntent(ctx))
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshUsb()
                }
            }
        }
    }
    DisposableEffect(usbManager) {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        refreshUsb()
        onDispose { runCatching { context.unregisterReceiver(usbAttachReceiver) } }
    }

    // ── 권한 흐름 (시작 시 요청 — SPEC) ──
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            // 권한 거부 → 앱은 유지, 출력만 제거 (SPEC)
            scope.launch { settings.setSelectedOutput(null) }
            status = context.getString(R.string.ui_status_denied)
            return@rememberLauncherForActivityResult
        }
        val data = result.data!!
        val app = apps.firstOrNull { it.packageName == selectedApp } ?: return@rememberLauncherForActivityResult
        val out = selectedOutput ?: return@rememberLauncherForActivityResult
        val intent = Intent(context, RelayService::class.java).apply {
            action = RelayService.ACTION_START
            putExtra(RelayService.EXTRA_MEDIA_PROJECTION_DATA, data)
            putExtra(RelayService.EXTRA_TARGET_PACKAGE, app.packageName)
            putExtra(RelayService.EXTRA_SAMPLE_RATE, app.sampleRate)
            // The saved id may lack the serial (saved before a grant); the service matches the serial exactly.
            val dev = if (out.type == "usb") usbManager.deviceList.values.firstOrNull { out.id.startsWith("${it.vendorId}:${it.productId}:") } else null
            putExtra(RelayService.EXTRA_OUTPUT_ID, dev?.let { usbId(it) } ?: out.id)
        }
        context.startForegroundService(intent)
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            // Whole-display consent (API 34+) skips the app picker: one "Start" tap. Audio is still
            // captured only from the chosen app (matched by UID). No API pre-selects a given app.
            consentLauncher.launch(
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    projectionManager.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay(),
                    )
                } else {
                    projectionManager.createScreenCaptureIntent()
                },
            )
        } else {
            scope.launch { settings.setSelectedOutput(null) }
            status = context.getString(R.string.ui_status_perm)
        }
    }

    // ── 시작/정지 ──
    var startAfterUsbPermission by remember { mutableStateOf(false) }
    fun proceedStart() {
        val app = apps.firstOrNull { it.packageName == selectedApp }
        if (app == null) { status = context.getString(R.string.ui_status_need_app); return }
        val out = selectedOutput
        if (out == null) { status = context.getString(R.string.ui_status_need_output); return }
        if (out.type == "usb") {
            // Match by vendor/product only: the serial is unreadable until permission is granted.
            val dev = usbManager.deviceList.values.firstOrNull { out.id.startsWith("${it.vendorId}:${it.productId}:") }
            if (dev == null) {
                status = context.getString(R.string.ui_status_usb_gone)
                return
            }
            // The grant ends at unplug; a DAC plugged in while the app was closed has none yet.
            // Ask now and continue the start once it is granted.
            if (!usbManager.hasPermission(dev)) {
                startAfterUsbPermission = true
                usbManager.requestPermission(dev, usbPermissionIntent(context))
                status = context.getString(R.string.ui_status_usb_perm)
                return
            }
        }
        permissionsLauncher.launch(
            arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    // Battery-optimization exemption (SPEC "First run"): asked once, on the first Start tap, never
    // at launch. Whatever the answer, the start continues (the request does not block the relay).
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { proceedStart() }

    fun onStart() {
        if (!settings.batteryAsked) {
            settings.setBatteryAsked()
            val pm = context.getSystemService(android.os.PowerManager::class.java)!!
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val req = Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
                try {
                    batteryLauncher.launch(req)
                    return
                } catch (e: android.content.ActivityNotFoundException) {
                    // Some builds have no handler for the request; fall back to the settings list.
                    AppLog.w("BitPerfect", "battery request: no handler, opening the settings list", e)
                    runCatching {
                        batteryLauncher.launch(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        return
                    }.onFailure { AppLog.w("BitPerfect", "battery settings list: no handler", it) }
                }
            }
        }
        proceedStart()
    }

    fun onStop() {
        if (!running) return
        RelayService.requestStop(context)
        status = context.getString(R.string.ui_status_stop_req)
    }

    // ── USB permission result (sent through our PendingIntent; EXPORTED keeps delivery independent
    //    of the sender identity, and a spoofed broadcast only triggers a hasPermission check) ──
    var permissionTick by remember { mutableStateOf(0) }
    val usbPermissionReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                // Audit defect 20: the old code read the result with a wrong key. Whether the
                // system fills the extras into an immutable PendingIntent is not verified,
                // so the current permission state is checked directly.
                val dev = usbManager.deviceList.values.firstOrNull { isUsbAudioDac(it) }
                if (dev == null || !usbManager.hasPermission(dev)) {
                    // ui-spec: denied → keep the app, remove the output only
                    startAfterUsbPermission = false
                    scope.launch { settings.setSelectedOutput(null) }
                    status = context.getString(R.string.ui_status_denied)
                    return
                }
                scope.launch { settings.setSelectedOutput(OutputSelection("usb", usbId(dev), dev.deviceName)) }
                permissionTick++
                status = context.getString(R.string.ui_status_ready)
            }
        }
    }
    // Start tapped without USB permission: continue in a fresh composition once it is granted.
    LaunchedEffect(permissionTick) {
        if (permissionTick > 0 && startAfterUsbPermission) {
            startAfterUsbPermission = false
            proceedStart()
        }
    }
    DisposableEffect(usbManager) {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(usbPermissionReceiver) } }
    }

    // ── 선택 동작 (단일 3-state) ──
    fun onAppTap(app: AppEntry) {
        if (selectedApp == app.packageName) {
            scope.launch { settings.setSelectedApp(null) }
        } else {
            scope.launch { settings.setSelectedApp(app.packageName) }
            // 재생 중인데 대상 앱이 바뀌면 즉시 정지 (SPEC)
            if (running) onStop()
        }
    }

    fun onUseToggle(dev: UsbDevice, on: Boolean) {
        if (!on) {
            scope.launch { settings.setSelectedOutput(null) }
            return
        }
        if (!usbManager.hasPermission(dev)) {
            usbManager.requestPermission(dev, usbPermissionIntent(context))
            status = context.getString(R.string.ui_status_usb_perm)
            return
        }
        scope.launch { settings.setSelectedOutput(OutputSelection("usb", usbId(dev), dev.deviceName)) }
    }

    // ── 앱 삭제 확인 ──
    var deleteTarget by remember { mutableStateOf<AppEntry?>(null) }
    fun onDelete(app: AppEntry) {
        deleteTarget = app
    }
    fun confirmDelete() {
        val app = deleteTarget ?: return
        scope.launch {
            settings.removeApp(app.packageName)
            if (selectedApp == app.packageName) settings.setSelectedApp(null)
            if (running) onStop()
        }
        deleteTarget = null
    }

    // ── 앱 추가 팝업 ──
    var showAppPicker by remember { mutableStateOf(false) }
    val installedApps = remember {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
            .map {
                val pkg = it.activityInfo.packageName
                val label = it.loadLabel(context.packageManager).toString()
                pkg to label
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    // Apps whose capture is known to fail (manifest or known runtime blockers). They stay selectable:
    // an app update may allow capture again.
    var captureBlocked by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(apps) {
        val pkgs = (installedApps.map { it.first } + apps.map { it.packageName }).distinct()
        captureBlocked = withContext(Dispatchers.IO) {
            pkgs.filter { CaptureCheck.isBlocked(context, it) }.toSet()
        }
    }
    fun addApp(pkg: String, label: String) {
        scope.launch {
            settings.addApp(AppEntry(pkg, label))
            showAppPicker = false
        }
    }

    // ── DAC facts and supported rates ──
    // Read on connect. UsbDacOutput.probe returns null while a relay session owns a DAC
    // (audit defect 19: the old code closed the shared engine under the running service).
    var supportedRates by remember { mutableStateOf<List<Int>>(emptyList()) }
    var dacInfo by remember { mutableStateOf<UsbDacInfo?>(null) }
    LaunchedEffect(usbDevices, permissionTick) {
        if (running) return@LaunchedEffect
        // Drop the previous DAC's facts first: a swapped DAC without permission yet showed the old one's.
        dacInfo = null
        supportedRates = emptyList()
        if (usbDevices.isEmpty()) return@LaunchedEffect
        val dev = usbDevices.first()
        if (!usbManager.hasPermission(dev)) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) {
            val conn = usbManager.openDevice(dev) ?: return@withContext null
            try { UsbDacOutput.probe(context, dev, conn) } finally { conn.close() }
        } ?: return@LaunchedEffect
        if (info.rates.isNotEmpty()) supportedRates = info.rates
        dacInfo = info
    }
    // DSD per-vendor exception list (assets/dac_dsd.json — "vid:pid" entries; may start empty).
    val dsdDevices: Set<String> = remember {
        try {
            context.assets.open("dac_dsd.json").bufferedReader().use { it.readText() }
                .let { text -> JSONArray(text).run { (0 until length()).map { i -> getString(i) }.toSet() } }
        } catch (_: Exception) { emptySet() }
    }
    val rateOptions = supportedRates.ifEmpty { FALLBACK_RATES }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(context.getString(R.string.ui_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                context.getString(R.string.ui_engine, Engine.version()),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            // ── 카드1: 앱 리스트 (재생 중에는 시각화 카드) ──
            val relayApp = apps.firstOrNull { it.packageName == selectedApp }
            if (running && relayApp != null) {
                RelayCard(
                    appLabel = relayApp.displayName,
                    rate = if (relayRate > 0) relayRate else relayApp.sampleRate,
                    rates = rateOptions,
                    onRateChange = { rate ->
                        scope.launch { settings.setAppRate(relayApp.packageName, rate) }
                        RelayService.requestRate(context, rate)
                        status = context.getString(R.string.ui_status_rate_change, khz(rate))
                    },
                    settings = settings,
                )
            } else Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Button(
                        onClick = { showAppPicker = true },
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = context.getString(R.string.ui_app_select) },
                    ) {
                        Text(context.getString(R.string.ui_app_select))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (apps.isEmpty()) {
                        Text(
                            context.getString(R.string.ui_app_add_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    apps.forEach { app ->
                        val isSelected = app.packageName == selectedApp
                        AppRow(
                            app = app,
                            isSelected = isSelected,
                            captureBlocked = app.packageName in captureBlocked,
                            rates = rateOptions,
                            onRateChange = { rate ->
                                scope.launch { settings.setAppRate(app.packageName, rate) }
                            },
                            onTap = { onAppTap(app) },
                            onDelete = { onDelete(app) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 카드2: 출력장치 ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        context.getString(R.string.ui_output),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val dev = usbDevices.firstOrNull()
                    if (dev == null) {
                        Text(
                            context.getString(R.string.ui_output_none),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        val devName = dev.productName ?: dev.deviceName
                        // ui-spec "Output-rate info": while relaying, the rate the relay runs at now.
                        val shownName = if (running && relayRate > 0) context.getString(R.string.ui_output_name_rate, devName, khz(relayRate)) else devName
                        val selfPowered = isSelfPowered(dev)
                        val uac = uacVersion(dev)
                        // DSD = raw-data alt setting (probe) or the per-vendor list (assets)
                        val dsd = (dacInfo?.dsd == true) || ("${dev.vendorId}:${dev.productId}" in dsdDevices)
                        val fmt = formatLine(context, dacInfo, dsd)
                        val powerText = context.getString(
                            if (selfPowered) R.string.ui_usb_power_battery else R.string.ui_usb_power_phone,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = if (fmt != null)
                                        context.getString(R.string.ui_usb_device_full, shownName, uac, fmt, powerText)
                                    else
                                        context.getString(R.string.ui_usb_device, shownName, uac, powerText)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(shownName, style = MaterialTheme.typography.bodyLarge)
                                if (fmt != null) {
                                    Text(
                                        fmt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            UacBadge(uac)
                            PowerIcon(selfPowered)
                        }
                        val useOn = selectedOutput?.type == "usb" && selectedOutput?.id == usbId(dev)
                        val useLabel = context.getString(
                            if (running) R.string.ui_output_use_locked else R.string.ui_output_use,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = useOn,
                                    enabled = !running,
                                    role = Role.Switch,
                                    onValueChange = { onUseToggle(dev, it) },
                                )
                                .semantics { contentDescription = useLabel },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                context.getString(R.string.ui_output_use),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = useOn, onCheckedChange = null, enabled = !running)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 시작/정지 ──
            if (!running) {
                Button(
                    onClick = { onStart() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics { contentDescription = context.getString(R.string.ui_start) },
                ) {
                    Text(context.getString(R.string.ui_start))
                }
            } else {
                OutlinedButton(
                    onClick = { onStop() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics { contentDescription = context.getString(R.string.ui_stop) },
                ) {
                    Text(context.getString(R.string.ui_stop))
                }
            }
            Spacer(Modifier.height(12.dp))
            // ui-spec Accessibility: status changes are announced by TalkBack.
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (running && captureSilent) {
                Spacer(Modifier.height(8.dp))
                Text(
                    context.getString(R.string.ui_capture_silent_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))

            // ── Volume while relaying (DAC hardware volume, or digital if the user enabled it) ──
            if (running && (volume != null || offerDigital)) {
                VolumeCard(volume, onSet = { v -> scope.launch(Dispatchers.IO) { RelayVolume.set(v) } })
                Spacer(Modifier.height(12.dp))
            }

            // [AI judgment] Diagnostics on the main screen: problems are reported while relaying,
            // and the popup must open without leaving the running screen.
            TextButton(
                onClick = { diagText = Diagnostics.build(context, running) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = context.getString(R.string.ui_diag_open) },
            ) {
                Text(context.getString(R.string.ui_diag_open))
            }

            // ── Stage 9: 설정 버튼 (화면 하단) ──
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = context.getString(R.string.ui_settings_open) },
            ) {
                Text(context.getString(R.string.ui_settings_open))
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── 앱 추가 팝업 ──
        if (showAppPicker) {
            AlertDialog(
                onDismissRequest = { showAppPicker = false },
                title = { Text(context.getString(R.string.ui_app_add_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (installedApps.isEmpty()) {
                            Text(context.getString(R.string.ui_app_add_empty))
                        }
                        installedApps.forEach { (pkg, label) ->
                            val already = apps.any { it.packageName == pkg }
                            val blocked = pkg in captureBlocked
                            val blockedText = context.getString(R.string.ui_app_capture_blocked)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { addApp(pkg, label) }
                                    .padding(vertical = 10.dp)
                                    .semantics {
                                        contentDescription = if (blocked) context.getString(R.string.ui_app_row, label, blockedText) else label
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(pkg)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label)
                                    if (blocked) CaptureBlockedLabel()
                                }
                                if (already) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAppPicker = false }) {
                        Text(context.getString(R.string.ui_app_add_close))
                    }
                },
            )
        }

        // ── 진단 정보 팝업: 선택 가능한 글과 복사 버튼 (보내기 기능 없음) ──
        diagText?.let { text ->
            AlertDialog(
                onDismissRequest = { diagText = null },
                title = { Text(context.getString(R.string.ui_diag_title)) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("BitPerfect diagnostics", text))
                            val back = cm.primaryClip?.getItemAt(0)?.text?.length ?: -1
                            AppLog.i("BitPerfect", "diagnostics: copied ${text.length} chars, clipboard holds $back chars")
                            status = context.getString(R.string.ui_diag_copied)
                            diagText = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(context.getString(R.string.ui_diag_copy)) }
                },
                dismissButton = {
                    TextButton(onClick = { diagText = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(context.getString(R.string.ui_diag_close))
                    }
                },
            )
        }

        // ── 삭제 확인 ──
        deleteTarget?.let { app ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(context.getString(R.string.ui_app_delete_title)) },
                text = { Text(context.getString(R.string.ui_app_delete_msg, app.displayName)) },
                confirmButton = {
                    TextButton(onClick = { confirmDelete() }) {
                        Text(context.getString(R.string.ui_app_delete_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text(context.getString(R.string.ui_app_delete_cancel))
                    }
                },
            )
        }
    }
}

// ── 볼륨 카드 ──
@Composable
private fun VolumeCard(state: VolumeState?, onSet: (Int) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(context.getString(R.string.ui_volume_title), style = MaterialTheme.typography.titleMedium)
            if (state == null) {
                Text(context.getString(R.string.ui_volume_none), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            fun label(v: Int) = if (state.hardware) context.getString(R.string.ui_volume_db, v / 256.0)
            else context.getString(R.string.ui_volume_pct, v)
            var pos by remember(state.value) { mutableFloatStateOf(state.value.toFloat()) }
            val shown = label(pos.roundToInt())
            Text(shown, style = MaterialTheme.typography.bodyLarge)
            if (state.hardware) {
                Text(
                    context.getString(R.string.ui_volume_hw_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onSet(state.value - state.step) },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = context.getString(R.string.ui_volume_down) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("-") }
                Slider(
                    value = pos,
                    onValueChange = { pos = it },
                    onValueChangeFinished = { onSet(pos.roundToInt()) },
                    valueRange = state.min.toFloat()..state.max.toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = context.getString(R.string.ui_volume_slider, shown)
                            stateDescription = shown
                        },
                )
                OutlinedButton(
                    onClick = { onSet(state.value + state.step) },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = context.getString(R.string.ui_volume_up) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("+") }
            }
        }
    }
}

// ── 앱 리스트 아이템 ──
/** Sample-rate dropdown (only the DAC's supported rates). */
@Composable
private fun RateButton(current: Int, rates: List<Int>, onRateChange: (Int) -> Unit) {
    val context = LocalContext.current
    var rateMenuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { rateMenuOpen = true },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = context.getString(R.string.ui_app_rate_desc, khz(current)) },
        ) {
            Text(khz(current))
            Spacer(Modifier.width(4.dp))
            Text("▼", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(
            expanded = rateMenuOpen,
            onDismissRequest = { rateMenuOpen = false },
        ) {
            rates.forEach { rate ->
                DropdownMenuItem(
                    text = { Text(khz(rate)) },
                    trailingIcon = {
                        if (rate == current) Text("✓")
                    },
                    onClick = {
                        onRateChange(rate)
                        rateMenuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * Shown in place of the app list while relaying (user, 2026-10-24): no app can be switched
 * mid-relay, and the view shows what is being relayed. The rate button changes the running relay
 * (brief pause) and the app's stored rate.
 */
@Composable
private fun RelayCard(
    appLabel: String,
    rate: Int,
    rates: List<Int>,
    onRateChange: (Int) -> Unit,
    settings: AppSettings,
) {
    val context = LocalContext.current
    val modes = VizMode.entries
    var custom by remember { mutableStateOf(settings.vizCustom?.takeIf { it.size == 7 }?.map { Color(it) }) }
    // Presets cycle through the built-in ones, then the user's own once it exists.
    val presets = VIZ_PRESETS + listOfNotNull(custom?.let { VizPreset.fromSlots(it) })
    var modeIdx by remember { mutableStateOf(settings.vizMode.coerceIn(0, modes.size - 1)) }
    var presetIdx by remember { mutableStateOf(settings.vizPreset) }
    if (presetIdx !in presets.indices) presetIdx = 0
    var tuning by remember { mutableStateOf(VizTuning(settings.vizNeedleMs / 1000f, settings.vizSyncMs)) }
    var showSettings by remember { mutableStateOf(false) }
    var delayMs by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delayMs = RelayMeter.delayMs
            kotlinx.coroutines.delay(500)
        }
    }
    fun stepMode(d: Int) {
        modeIdx = (modeIdx + d + modes.size) % modes.size
        settings.vizMode = modeIdx
    }
    fun setPreset(i: Int) {
        presetIdx = i
        settings.vizPreset = i
    }
    fun stepPreset(d: Int) = setPreset((presetIdx + d + presets.size) % presets.size)
    val modeNames = context.resources.getStringArray(R.array.viz_modes)
    val presetNames = context.resources.getStringArray(R.array.viz_presets).toList() + context.getString(R.string.ui_viz_custom)
    val modeName = modeNames[modeIdx]
    val presetName = presetNames[presetIdx]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.ui_relay_app, appLabel),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                RateButton(rate, rates, onRateChange)
            }
            Spacer(Modifier.height(8.dp))
            Visualizer(
                mode = modes[modeIdx],
                preset = presets[presetIdx],
                description = context.getString(R.string.ui_viz_desc, modeName, presetName),
                actions = listOf(
                    context.getString(R.string.ui_viz_next_mode) to { stepMode(1) },
                    context.getString(R.string.ui_viz_prev_mode) to { stepMode(-1) },
                    context.getString(R.string.ui_viz_next_color) to { stepPreset(1) },
                ),
                onModeStep = ::stepMode,
                onPresetStep = ::stepPreset,
                tuning = tuning,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.ui_viz_footer, modeName, presetName, delayMs),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(context.getString(R.string.ui_viz_settings)) }
            }
        }
    }
    if (showSettings) {
        VizSettingsDialog(
            presetNames = presetNames,
            presetIdx = presetIdx,
            custom = custom ?: presets[presetIdx].slots(),
            tuning = tuning,
            onPreset = { i ->
                // "사용자" before any edit: start the user's colors from the current preset.
                if (i >= presets.size) {
                    val c = presets[presetIdx].slots()
                    custom = c
                    settings.vizCustom = c.map { it.toArgb() }
                }
                setPreset(i)
            },
            onCustom = { colors ->
                custom = colors
                settings.vizCustom = colors.map { it.toArgb() }
                setPreset(VIZ_PRESETS.size)
            },
            onTuning = { t ->
                tuning = t
                settings.vizNeedleMs = (t.needleSec * 1000).toInt()
                settings.vizSyncMs = t.syncOffsetMs
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    isSelected: Boolean,
    captureBlocked: Boolean,
    rates: List<Int>,
    onRateChange: (Int) -> Unit,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // [AI judgment] Above 1.3x font scale the row stacks (checked on the emulator at 1.3 and 2.0).
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val controls: @Composable () -> Unit = {
        // 샘플레이트 드롭다운 (DAC 지원 레이트만)
        RateButton(app.sampleRate, rates, onRateChange)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { onDelete() }) {
            Text("🗑", modifier = Modifier.semantics { contentDescription = context.getString(R.string.ui_app_delete) })
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onTap() }
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .semantics {
                val sel = context.getString(if (isSelected) R.string.ui_app_selected else R.string.ui_app_unselected)
                contentDescription = if (captureBlocked)
                    context.getString(R.string.ui_app_row_blocked, app.displayName, sel, context.getString(R.string.ui_app_capture_blocked))
                else
                    context.getString(R.string.ui_app_row, app.displayName, sel)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.displayName, style = MaterialTheme.typography.bodyLarge)
            if (captureBlocked) CaptureBlockedLabel()
            // Large font: the rate button and trash move under the name, else the name wraps per letter.
            if (stacked) Row(verticalAlignment = Alignment.CenterVertically) { controls() }
        }
        if (!stacked) controls()
    }
}

/** "44.1kHz", "96kHz" (the old integer division showed 44100 as "44kHz"). */
private fun khz(rate: Int): String =
    if (rate % 1000 == 0) "${rate / 1000}kHz" else String.format(java.util.Locale.ROOT, "%.1fkHz", rate / 1000.0)

@Composable
private fun CaptureBlockedLabel() {
    val context = LocalContext.current
    Text(
        context.getString(R.string.ui_app_capture_blocked),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun AppIcon(packageName: String, size: Int = 32) {
    val context = LocalContext.current
    // Drawable → Bitmap 라스터화 (asImageBitmap() 는 Bitmap만 받음)
    val icon = remember(packageName) {
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            val d = context.packageManager.getApplicationIcon(info)
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 96
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 96
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
            bmp
        }.getOrNull()
    }
    if (icon != null) {
        androidx.compose.foundation.Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size.dp),
        )
    } else {
        Box(Modifier.size(size.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

// ── USB DAC 판별/정보 ──
/**
 * USB device identity persisted in [OutputSelection.id].
 * Must match RelayService.openUsbById: "vendorId:productId:serial" (colon-joined).
 */
private fun usbId(d: UsbDevice): String = "${d.vendorId}:${d.productId}:${runCatching { d.serialNumber }.getOrNull() ?: ""}"

private fun isUsbAudioDac(d: UsbDevice): Boolean {
    for (cfg in 0 until d.configurationCount) {
        val c = d.getConfiguration(cfg) ?: continue
        for (i in 0 until c.interfaceCount) {
            val inf = c.getInterface(i) ?: continue
            if (inf.interfaceClass == 1 && inf.interfaceSubclass == 2) return true
        }
    }
    return false
}

/** UAC 1.0 / 2.0 — 오디오 인터페이스 프로토콜 번호 기준 (usb-dac.md). */
private fun uacVersion(d: UsbDevice): String {
    for (cfg in 0 until d.configurationCount) {
        val c = d.getConfiguration(cfg) ?: continue
        for (i in 0 until c.interfaceCount) {
            val inf = c.getInterface(i) ?: continue
            if (inf.interfaceClass == 1 && inf.interfaceSubclass == 2) {
                return if (inf.interfaceProtocol == 0x20) "2.0" else "1.0"
            }
        }
    }
    return "1.0"
}

/** self-powered 비트 (USB 스펙: bmAttributes bit0=자립 전원). */
private fun isSelfPowered(d: UsbDevice): Boolean {
    val cfg = d.getConfiguration(0) ?: return false
    return cfg.isSelfPowered
}

@Composable
private fun UacBadge(version: String) {
    val context = LocalContext.current
    Text(
        context.getString(R.string.ui_usb_uac, version),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = context.getString(R.string.ui_usb_uac, version) },
    )
}

/** 전원 표시 (8단계 스펙): self-powered ⇒ 내장 배터리(🔋), 아니면 휴대폰 전원(⚡). */
@Composable
private fun PowerIcon(selfPowered: Boolean) {
    val context = LocalContext.current
    val desc = context.getString(
        if (selfPowered) R.string.ui_usb_power_battery else R.string.ui_usb_power_phone,
    )
    Text(
        if (selfPowered) "🔋" else "⚡",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = desc },
    )
}

/** 최대 포맷 문자열 (8단계 스펙): "32bit/384kHz", DSD 인지면 "… · DSD". 모르는 값은 null. */
private fun formatLine(context: Context, info: UsbDacInfo?, dsd: Boolean): String? {
    if (info == null || info.bitDepth <= 0 || info.maxRateHz <= 0) return null
    val base = context.getString(R.string.ui_usb_format, info.bitDepth, "${info.maxRateHz / 1000}kHz")
    return if (dsd) context.getString(R.string.ui_usb_format_dsd, base) else base
}

// ── USB 권한 수신기 (선택 장치를 기억해야 하므로 화면 수준에서) ──
// 비-컴포저블(onReceive)에서 호출되므로 @Composable 미부여.
private fun usbPermissionIntent(context: Context): android.app.PendingIntent {
    return android.app.PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )
}
