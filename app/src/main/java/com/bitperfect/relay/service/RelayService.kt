package com.bitperfect.relay.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.VolumeProvider
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.bitperfect.relay.MainActivity
import com.bitperfect.relay.R
import com.bitperfect.relay.capture.WavWriter
import com.bitperfect.relay.diag.AppLog
import com.bitperfect.relay.diag.Diagnostics
import com.bitperfect.relay.output.usb.TestTone
import com.bitperfect.relay.output.usb.UsbDacOutput
import com.bitperfect.relay.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 포그라운드 서비스.
 *
 * 2단계 (캡처 시험) 모드 ACTION_CAPTURE_TEST:
 *   1. MediaProjection 토큰으로 캡처 권한 확보
 *   2. 대상 앱 UID를 AudioPlaybackCaptureConfiguration.addMatchingUid로 지정
 *   3. AudioRecord(PCM_FLOAT, 스테레오) 를 대상 앱 샘플레이트로 열기
 *   4. float 샘플을 WAV 파일 (앱 저장소 .../Music/bitperfect/capture_test.wav) 로 저장
 *
 * 5단계 (USB 출력 연결):
 *   - Captured floats go unprocessed to the USB DAC (output/usb/UsbDacOutput).
 *   - A selected DAC that cannot start, errors or is unplugged stops the relay with the reason.
 *   - No output id (capture test only): record a WAV file.
 *   - Bluetooth output was removed on 2026-10-11 (capture plays the original on the BT headset too).
 * Debug tone (EXTRA_TONE_SEC > 0): a test tone replaces the capture; the USB sequence is the same.
 * Volume (task 2026-10-24): RelayVolume; volume keys reach us through a remote-playback MediaSession.
 */
class RelayService : android.app.Service() {

    companion object {
        const val TAG = "BitPerfect"
        // [AI judgment] AOSP AudioFlinger closes an idle output after 3 s (kDefaultStandbyTimeInNsecs).
        private const val ANCHOR_SETTLE_MS = 3500L
        private const val CHANNEL_ID = "relay"
        private const val NOTIF_ID = 1
        private const val VOLUME_PRIORITY_MS = 1000L
        private const val ROUTE_RETURN_TIMEOUT_MS = 2000L
        private const val DETACH_STOP_TIMEOUT_MS = 3000L
        private const val USB_GONE_WAIT_MS = 500L

        private fun state(s: Int) = PlaybackState.Builder()
            .setState(s, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (s == PlaybackState.STATE_PLAYING) 1f else 0f)
            .build()

        const val ACTION_CAPTURE_TEST = "com.bitperfect.relay.action.CAPTURE_TEST"
        const val ACTION_STOP = "com.bitperfect.relay.action.STOP"
        // Stage 7: production relay start (same path as CAPTURE_TEST, plus output device)
        const val ACTION_START = "com.bitperfect.relay.action.START"
        const val ACTION_VOLUME_UP = "com.bitperfect.relay.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.bitperfect.relay.action.VOLUME_DOWN"
        // Change the relay rate while relaying (EXTRA_SAMPLE_RATE); the stream pauses briefly.
        const val ACTION_SET_RATE = "com.bitperfect.relay.action.SET_RATE"
        // Debug: the volume-key session and MediaMute for N seconds without USB (key routing test).
        const val ACTION_DEBUG_VOLUME_KEYS = "com.bitperfect.relay.action.DEBUG_VOLUME_KEYS"
        const val EXTRA_SECONDS = "extra.seconds"

        const val EXTRA_MEDIA_PROJECTION_DATA = "extra.mp.data"
        const val EXTRA_TARGET_PACKAGE = "extra.target.package"
        const val EXTRA_SAMPLE_RATE = "extra.sample.rate"
        // Output device id — usb: "vendorId:productId:serial" (UsbDevice fields, colon-joined)
        const val EXTRA_OUTPUT_ID = "extra.output.id"
        // Debug: play a test tone for this many seconds instead of capturing (needs EXTRA_OUTPUT_ID).
        const val EXTRA_TONE_SEC = "extra.tone.sec"

        // Relay state lives here, not in the UI, so a recreated screen still knows it (audit defect 19).
        private val relayingState = MutableStateFlow(false)
        val relaying: StateFlow<Boolean> = relayingState

        private val relayRateState = MutableStateFlow(0)
        /** Rate of the running relay (changes with ACTION_SET_RATE); 0 before the first start. */
        val relayRate: StateFlow<Int> = relayRateState

        fun requestRate(context: Context, rate: Int) {
            context.startService(
                Intent(context, RelayService::class.java).setAction(ACTION_SET_RATE).putExtra(EXTRA_SAMPLE_RATE, rate),
            )
        }

        private val captureSilentState = MutableStateFlow(false)
        /** The captured audio has been all zero for 5 s while relaying (some apps block capture). */
        val captureSilent: StateFlow<Boolean> = captureSilentState

        /** UI status feedback — MainScreen subscribes with a BroadcastReceiver. */
        const val ACTION_STATUS = "com.bitperfect.relay.action.STATUS"
        const val EXTRA_STATUS = "extra.status"

        fun sendStatus(context: Context, message: String) {
            context.sendBroadcast(
                Intent(ACTION_STATUS)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_STATUS, message),
            )
        }

        /** 메인 화면에서 호출 — 캡처 종료 요청. */
        fun requestStop(context: Context) {
            val intent = Intent(context, RelayService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /** USB device identity used as EXTRA_OUTPUT_ID; must match MainScreen.usbId. */
        fun usbId(d: UsbDevice): String = "${d.vendorId}:${d.productId}:${runCatching { d.serialNumber }.getOrNull() ?: ""}"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var powerWakeLock: PowerManager.WakeLock? = null

    // USB DAC session. The capture loop reads it once per chunk.
    @Volatile private var usbOut: UsbDacOutput? = null
    private var captureConfig: AudioPlaybackCaptureConfiguration? = null
    /** Rate requested by the UI while relaying; the capture loop applies it (0 = none). */
    @Volatile private var pendingRate = 0
    /** The relayed DAC was unplugged: [stopUsb] pauses the target before unmuting. */
    @Volatile private var detached = false
    // Pins the media output to the speaker while we own the DAC (see SpeakerAnchor).
    private val speakerAnchor by lazy { SpeakerAnchor(this) }
    private var usbDetachReceiver: BroadcastReceiver? = null

    // Volume keys: remote-playback session (main thread only).
    private var volumeSession: MediaSession? = null
    private var volumeJob: Job? = null
    // [AI judgment] Re-take the top of the session stack every second (docs/ref/volume.md): a music
    // app that skips a track enters SKIPPING_TO_NEXT, which AOSP MediaSessionRecord always pushes to
    // the top, and its local session would then take the volume keys (they would unmute the music
    // stream). PAUSED -> PLAYING is a transition that pushes our session back to the top.
    private val volumePriority = object : Runnable {
        override fun run() {
            val session = volumeSession ?: return
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
            mainHandler.postDelayed(this, VOLUME_PRIORITY_MS)
        }
    }
    private var notifTitle = ""
    private var notifBody = ""

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "RelayService created")
        createNotificationChannel()
        // Stage 7: physical USB DAC detach → auto-stop with reason (spec: output drop → stop).
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // "deviceName" is the AOSP-verified literal for UsbManager.EXTRA_DEVICE_NAME
                // (missing from the compileSdk 37 stub jar).
                AppLog.i(TAG, "USB detach detected: ${intent.getStringExtra("deviceName")}")
                if (usbOut != null) {
                    detached = true
                    Diagnostics.lastFailure = "USB DAC detached"
                    sendStatus(this@RelayService, "USB DAC 분리됨 → 자동 정지")
                    // stopUsb waits for the target app to stop; keep that off the main thread.
                    scope.launch {
                        stopUsb()
                        mainHandler.post { stopCaptureAndSelf() }
                    }
                }
            }
        }
        // targetSdk 36: runtime-registered receivers must declare their export flag
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        usbDetachReceiver = receiver

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "RelayService onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureAndSelf()
                return START_NOT_STICKY
            }
            ACTION_VOLUME_UP, ACTION_VOLUME_DOWN -> {
                val up = intent.action == ACTION_VOLUME_UP
                scope.launch { RelayVolume.step(up) }
                return START_NOT_STICKY
            }
            ACTION_SET_RATE -> {
                if (relayingState.value) pendingRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 0)
                return START_NOT_STICKY
            }
            ACTION_DEBUG_VOLUME_KEYS -> {
                debugVolumeKeys(intent.getIntExtra(EXTRA_SECONDS, 30))
                return START_NOT_STICKY
            }
        }

        // Stage 7: ACTION_START (production) and ACTION_CAPTURE_TEST (legacy test UI) share the path
        if (intent != null && (intent.action == ACTION_START || intent.action == ACTION_CAPTURE_TEST)) {
            startRelay(intent)
            return START_STICKY
        }

        // 기본: 대기
        startForeground(NOTIF_ID, buildNotification("대기 중", "BitPerfect Relay"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.i(TAG, "RelayService onDestroy")
        stopCaptureAndSelf()
        usbDetachReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbDetachReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    // ── 캡처 시험 ─────────────────────────────────────────────

    private fun startRelay(intent: Intent) {
        val toneSec = intent.getIntExtra(EXTRA_TONE_SEC, 0)
        val tone = toneSec > 0
        val mpData = intent.getParcelableExtra<Intent>(EXTRA_MEDIA_PROJECTION_DATA)
        val targetPkg = if (tone) "test tone" else intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 44100)
        val outputId = intent.getStringExtra(EXTRA_OUTPUT_ID)
        notifTitle = getString(R.string.notif_relay_title)
        notifBody = targetPkg ?: "?"

        // 0) FGS first — MainScreen starts this service via startForegroundService(),
        //    so startForeground() MUST run before any early return below
        //    (Android 12+ throws RemoteServiceException if the service is not
        //    promoted to foreground within ~5s).
        //    mediaProjection type: required on Android 14+ before getMediaProjection()
        //    (otherwise SecurityException, confirmed on real device 2026-10-09).
        //    The debug tone has no projection: connectedDevice type (allowed with a USB grant).
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(notifTitle, notifBody),
                if (tone) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            // Fallback if the type itself is rejected (e.g. the mpData == null path below).
            AppLog.w(TAG, "startForeground(typed) failed: ${e.message}; falling back")
            try {
                startForeground(NOTIF_ID, buildNotification(notifTitle, notifBody))
            } catch (e2: Exception) {
                // The debug tone is started with startService from a visible activity, so it may run
                // without foreground status; a capture never gets here without consent data.
                AppLog.w(TAG, "startForeground failed: ${e2.message}; running as a started service")
            }
        }

        if (tone && outputId.isNullOrBlank()) {
            failStart("시험음 실패: USB DAC 없음 또는 권한 없음")
            return
        }
        var audioRecord: AudioRecord? = null
        if (!tone) {
            if (mpData == null) {
                failStart("캡처 실패: MediaProjection data 없음")
                return
            }
            if (targetPkg.isNullOrBlank()) {
                failStart("캡처 실패: 대상 패키지명 없음")
                return
            }
            audioRecord = openCapture(mpData, targetPkg, sampleRate) ?: return
        }

        // 5) WAV file — app-private external dir. Shared Music dir requires
        //    MANAGE_EXTERNAL_STORAGE on Android 13+; private dir needs no permission.
        //    Fixed name: the USB play-test UI points at the same path.
        val outDir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "bitperfect")
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "capture_test.wav")

        // 6) wakelock — 백그라운드에서도 계속 기록
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitPerfect:capture")
        wl.acquire(10 * 60 * 60 * 1000L) // 10분 안전장치
        powerWakeLock = wl

        // 8)-9) Output start and read loop run off the main thread: the start waits for the
        //    system USB output to close (below) and does USB control transfers.
        //    No EXTRA_OUTPUT_ID (capture test) → record a WAV file instead.
        val floatBuffer = FloatArray(4096) // 2048 프레임 × 2ch
        var totalFrames = 0L
        var wavWriter: WavWriter? = null
        val toneSource = if (tone) TestTone(sampleRate, toneSec) else null
        val read: (FloatArray) -> Int = toneSource?.let { t -> { buf: FloatArray -> t.read(buf) } }
            ?: { buf: FloatArray -> audioRecord!!.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) }

        captureJob = scope.launch {
            // Status shown after the stop: the failure reason if any (audit defect 18).
            var failReason: String? = null
            var focus: AudioFocusRequest? = null
            try {
                if (!outputId.isNullOrBlank()) {
                    // Pause the target app briefly (transient focus) so our silent anchor is the only
                    // media client; then the media output moves to the speaker before we detach the
                    // DAC's kernel driver. With the target still playing the output stays on the USB
                    // route and the capture breaks up (device test 2026-10-11; UAPP takes focus too).
                    val am = getSystemService(AudioManager::class.java)
                    focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                        )
                        .setOnAudioFocusChangeListener { }
                        .build()
                    if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        AppLog.w(TAG, "transient audio focus not granted")
                    }
                    detached = false
                    // Mute before moving the output: the target fades out on the speaker while pausing.
                    MediaMute.engage(this@RelayService)
                    speakerAnchor.start()
                    delay(ANCHOR_SETTLE_MS)
                    val opened = openUsbById(getSystemService(USB_SERVICE) as UsbManager, outputId)
                    val reason = if (opened == null) {
                        getString(R.string.ui_status_usb_open_fail)
                    } else {
                        val out = UsbDacOutput(this@RelayService, opened.first, opened.second)
                        val digital = AppSettings(this@RelayService).digitalVolume.value
                        if (out.start(sampleRate, digitalVolume = digital)) {
                            usbOut = out
                            null
                        } else out.lastError
                    }
                    if (reason != null) {
                        failReason = getString(R.string.ui_status_usb_fail, reason)
                        return@launch
                    }
                }

                audioRecord?.startRecording()
                AppLog.i(TAG, if (tone) "test tone ${sampleRate} Hz for $toneSec s" else "음성 기록 시작")
                relayingState.value = true
                // Give focus back: the target app resumes, now on the speaker route (muted).
                focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
                if (usbOut != null) {
                    sendStatus(this@RelayService, getString(R.string.ui_status_usb_on, targetPkg, sampleRate))
                    mainHandler.post { startVolumeSession() }
                } else {
                    sendStatus(this@RelayService, "출력 미연결 → WAV 폴백: ${outFile.name}")
                }

                relayRateState.value = sampleRate
                RelayMeter.reset(sampleRate)
                var meterAt = 0L
                while (isActive) {
                    val newRate = pendingRate
                    if (newRate != 0 && usbOut != null && audioRecord != null) {
                        pendingRate = 0
                        if (newRate != relayRateState.value) {
                            val rec = switchRate(audioRecord!!, newRate)
                            if (rec == null) {
                                audioRecord = null
                                failReason = getString(R.string.ui_status_rate_fail, newRate)
                                break
                            }
                            audioRecord = rec
                        }
                    }
                    // API 37에서 read(float[],int,int) 제거됨 → 플래그 인자 추가 버전 사용
                    val n = read(floatBuffer)
                    if (n > 0) RelayMeter.push(floatBuffer, n)
                    val now = SystemClock.elapsedRealtime()
                    if (now - meterAt >= 250) {
                        meterAt = now
                        RelayMeter.delayMs = usbOut?.bufferedMs() ?: 0
                    }
                    if (n < 0) {
                        AppLog.e(TAG, "AudioRecord.read 오류 code=$n")
                        failReason = getString(R.string.ui_status_capture_error, n)
                        break
                    }
                    if (n == 0) {
                        if (tone) break // tone finished
                        continue
                    }
                    totalFrames += (n / 2)
                    val usb = usbOut
                    if (usb != null) {
                        if (!usb.write(floatBuffer, n)) {
                            // A stop in progress is not an error.
                            if (usb !== usbOut || !isActive) continue
                            AppLog.e(TAG, "USB 스트리밍 오류 — 캡처 정지: ${usb.lastError}")
                            // An unplug fails the transfers before the detach broadcast arrives.
                            if (awaitUsbGone(usb.device)) detached = true
                            failReason = getString(R.string.ui_status_usb_error, usb.lastError)
                            break  // SPEC: 출력 장치 끊김·오류 시 자동 정지
                        }
                        if (usb.captureSilent != captureSilentState.value) {
                            captureSilentState.value = usb.captureSilent
                            AppLog.i(TAG, "capture silent for 5 s: ${usb.captureSilent}")
                        }
                    } else {
                        if (wavWriter == null) wavWriter = WavWriter(outFile, sampleRate, 2)
                        wavWriter?.write(floatBuffer.copyOfRange(0, n))
                    }
                }
            } catch (e: CancellationException) {
                AppLog.i(TAG, "기록 루프 취소")
            } catch (e: Exception) {
                AppLog.e(TAG, "기록 루프 예외: ${e.message}", e)
                failReason = getString(R.string.ui_status_usb_error, e.toString())
            } finally {
                mainHandler.post { stopVolumeSession() }
                focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
                runCatching { wavWriter?.close() }  // close() 가 WAV 헤더를 확정한다
                stopUsb()
                captureSilentState.value = false
                try { audioRecord?.stop() } catch (_: Exception) {}
                audioRecord?.release()
                powerWakeLock?.let { runCatching { it.release() } }
                powerWakeLock = null
                mediaProjection?.stop()
                mediaProjection = null
                AppLog.i(TAG, "기록 종료: 총 $totalFrames 프레임, ${totalFrames / sampleRate.toFloat()}s")
                failReason?.let { Diagnostics.lastFailure = it }
                val mode = if (wavWriter == null) "USB 출력" else "WAV 저장 완료 (${outFile.name})"
                sendStatus(this@RelayService, failReason ?: "정지됨: ${totalFrames / sampleRate.toFloat()}s — $mode")
                relayingState.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** MediaProjection + playback capture of [targetPkg]. Null (after reporting) on failure. */
    /**
     * Rate change while relaying (capture loop thread): close the capture, move the DAC to the new
     * rate, reopen the capture at that rate with the same projection. Mute and speaker anchor stay,
     * so nothing leaks in the gap. Null on failure (the old capture is already released).
     */
    private fun switchRate(old: AudioRecord, rate: Int): AudioRecord? {
        AppLog.i(TAG, "rate change: ${relayRateState.value} -> $rate Hz")
        runCatching { old.stop() }
        old.release()
        val usb = usbOut ?: return null
        if (!usb.changeRate(rate)) {
            AppLog.e(TAG, "rate change: USB failed: ${usb.lastError}")
            return null
        }
        val config = captureConfig ?: return null
        val rec = buildRecord(config, rate) ?: return null
        rec.startRecording()
        relayRateState.value = rate
        RelayMeter.reset(rate)
        sendStatus(this, getString(R.string.ui_status_usb_on, notifBody, rate))
        AppLog.i(TAG, "rate change: now $rate Hz")
        return rec
    }

    private fun openCapture(mpData: Intent, targetPkg: String, sampleRate: Int): AudioRecord? {
        // 1) MediaProjection (단 1회)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, mpData)
            ?: run {
                failStart("캡처 실패: MediaProjection 생성 실패")
                return null
            }
        // 사용자가 퀵셋팅에서 캡처를 해제하면 onStop → 즉시 정리 (공식 가이드: registerCallback 필수)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.i(TAG, "MediaProjection onStop (사용자 해제) → 캡처 종료")
                sendStatus(this@RelayService, "사용자 해제(퀵셋팅) → 캡처 종료")
                stopCaptureAndSelf()
            }
        }, mainHandler)
        mediaProjection = projection

        // 2) 대상 UID 조회
        val uid = try {
            packageManager.getApplicationInfo(targetPkg, 0).uid
        } catch (e: Exception) {
            failStart("캡처 실패: 대상 앱 UID 조회 실패 — $targetPkg")
            return null
        }
        AppLog.i(TAG, "대상 앱: $targetPkg uid=$uid 샘플레이트=$sampleRate Hz")

        // 3) AudioPlaybackCaptureConfiguration — MediaProjection 토큰 전달
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(uid)
            .build()
        captureConfig = config
        val audioRecord = buildRecord(config, sampleRate)
        if (audioRecord == null) {
            failStart("캡처 실패: AudioRecord 생성 실패 (${Diagnostics.lastFailure})")
            return null
        }
        return audioRecord
    }

    /** AudioRecord on [captureConfig] at [sampleRate]; null (reason in Diagnostics.lastFailure) on failure. */
    private fun buildRecord(captureConfig: AudioPlaybackCaptureConfiguration, sampleRate: Int): AudioRecord? {
        // 4) AudioRecord — float32, 스테레오, 지정된 샘플레이트
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val minBufBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(4096)
        AppLog.i(TAG, "AudioRecord minBufferSize=$minBufBytes bytes")

        val audioRecord = try {
            // API 37 (android-37 stub, javap verified) redesigned AudioRecord.Builder:
            // - setSampleRate()/setChannelMask() removed -> pass via AudioFormat
            // - setPlaybackCaptureConfiguration() -> setAudioPlaybackCaptureConfig()
            //
            // Do NOT call setAudioSource() when using AudioPlaybackCaptureConfiguration:
            // AOSP checkAndThrow() throws IllegalStateException "Cannot both set audio
            // source and set playback capture config" (confirmed on real device 2026-10-09).
            // With a playback capture config present the source must be left unset.
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            AppLog.e(TAG, "AudioRecord build failed", e)
            Diagnostics.lastFailure = e.message ?: e.toString()
            return null
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            val st = audioRecord.state
            audioRecord.release()
            AppLog.e(TAG, "AudioRecord not initialized (state=$st)")
            Diagnostics.lastFailure = "state=$st"
            return null
        }
        return audioRecord
    }

    private fun failStart(reason: String) {
        AppLog.e(TAG, reason)
        Diagnostics.lastFailure = reason
        sendStatus(this, reason)
        stopCaptureAndSelf()
    }

    private fun stopCaptureAndSelf() {
        AppLog.i(TAG, "stopCaptureAndSelf 호출")
        relayingState.value = false
        captureJob?.cancel()
        captureJob = null
        stopUsb()
        powerWakeLock?.let { runCatching { it.release() } }
        powerWakeLock = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Volume keys ────────────────────────────────────────────

    /**
     * Debug (task 2026-10-24): which session gets the volume keys while a music app plays, with the
     * music stream muted as in a relay. No USB: a dummy percent volume that only logs.
     */
    private fun debugVolumeKeys(seconds: Int) {
        if (usbOut != null || volumeSession != null) return
        scope.launch {
            MediaMute.engage(this@RelayService)
            RelayVolume.attach(
                VolumeState(false, 50, 0, 100, 5),
                snap = { it.coerceIn(0, 100) },
                apply = { AppLog.i(TAG, "volume key test: level $it"); true },
                persist = {},
            )
            mainHandler.post { startVolumeSession() }
            AppLog.i(TAG, "volume key test: session for $seconds s")
            delay(seconds * 1000L)
            mainHandler.post { stopVolumeSession() }
            RelayVolume.detach()
            MediaMute.release(this@RelayService)
            AppLog.i(TAG, "volume key test: done")
            stopSelf()
        }
    }

    /**
     * Remote-playback media session so hardware volume keys change the relay volume instead of
     * the (muted) music stream. AOSP MediaSessionService.dispatchAdjustVolumeLocked sends a volume
     * key to MediaSessionStack.getDefaultVolumeSession(): the first session in priority order whose
     * playback state is active and that can handle volume keys (docs/ref/volume.md). MediaSession
     * only forwards volume to the VolumeProvider when a Callback is set, hence the empty one.
     */
    private fun startVolumeSession() {
        if (volumeSession != null) return
        val st = RelayVolume.state.value
        if (st == null) {
            updateNotification()
            return
        }
        val steps = ((st.max - st.min) / st.step).coerceAtLeast(1)
        val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, steps, ((st.value - st.min) / st.step).coerceIn(0, steps)) {
            override fun onSetVolumeTo(volume: Int) {
                AppLog.i(TAG, "volume session: set to step $volume")
                scope.launch { RelayVolume.set(st.min + volume * st.step) }
            }

            override fun onAdjustVolume(direction: Int) {
                AppLog.i(TAG, "volume session: adjust $direction")
                if (direction != 0) scope.launch { RelayVolume.step(direction > 0) }
            }
        }
        val session = MediaSession(this, "BitPerfectRelayVolume").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    AppLog.i(TAG, "volume session: media button reached us: $mediaButtonIntent")
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            }, mainHandler)
            setPlaybackToRemote(provider)
            setPlaybackState(state(PlaybackState.STATE_PLAYING))
            isActive = true
        }
        volumeSession = session
        mainHandler.postDelayed(volumePriority, VOLUME_PRIORITY_MS)
        AppLog.i(TAG, "volume session: active, $steps steps, now ${provider.currentVolume}")
        volumeJob = scope.launch {
            RelayVolume.state.collect { s ->
                if (s != null) provider.currentVolume = ((s.value - s.min) / s.step).coerceIn(0, steps)
                mainHandler.post { updateNotification() }
            }
        }
    }

    private fun stopVolumeSession() {
        mainHandler.removeCallbacks(volumePriority)
        volumeJob?.cancel()
        volumeJob = null
        volumeSession?.let {
            it.isActive = false
            it.release()
            AppLog.i(TAG, "volume session: released")
        }
        volumeSession = null
    }

    // ── Stage 7: 출력 장치 ─────────────────────────────────────

    /** Stop and release the USB session, if any (idempotent; audit defect 16: it was never closed). */
    @Synchronized
    private fun stopUsb() {
        if (detached && speakerAnchor.isRunning()) {
            stopAfterDetach()
            return
        }
        usbOut?.let {
            usbOut = null
            it.stop()
        }
        // The music volume index is kept per output device (AOSP AudioService VolumeStreamState).
        // MediaMute set the USB device's index at engage, so restore only after the media route
        // has left the anchored speaker; restoring first put the saved index on the speaker and
        // left the USB DAC at 2 (device test 2026-10-24).
        val anchored = speakerAnchor.isRunning()
        speakerAnchor.stop()
        if (anchored) awaitMediaRouteOffSpeaker()
        MediaMute.release(this)
    }

    /**
     * The DAC was unplugged. The target app keeps playing on the muted speaker (the anchor holds the
     * route there, so the system sends no "becoming noisy"), and unmuting now leaks it out of the
     * speaker. Order (user, 2026-10-24): make the player stop, then unmute. Permanent focus makes it
     * stop; a transient loss would let it resume when we abandon focus.
     */
    private fun stopAfterDetach() {
        val am = getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AppLog.w(TAG, "detach: audio focus not granted")
        }
        usbOut?.let {
            usbOut = null
            it.stop()
        }
        speakerAnchor.stop()
        val start = SystemClock.elapsedRealtime()
        while (am.isMusicActive && SystemClock.elapsedRealtime() - start < DETACH_STOP_TIMEOUT_MS) {
            Thread.sleep(50)
        }
        if (am.isMusicActive) {
            // [AI judgment] An app that ignores focus would otherwise keep the phone muted for good.
            AppLog.w(TAG, "detach: music still active after $DETACH_STOP_TIMEOUT_MS ms; unmuting anyway")
        } else {
            AppLog.i(TAG, "detach: player stopped after ${SystemClock.elapsedRealtime() - start} ms")
        }
        MediaMute.release(this, deviceGone = true)
        am.abandonAudioFocusRequest(focus)
    }

    /** True if [device] leaves the USB device list within a short wait (an unplug in progress). */
    private fun awaitUsbGone(device: UsbDevice): Boolean {
        val um = getSystemService(USB_SERVICE) as UsbManager
        val deadline = SystemClock.elapsedRealtime() + USB_GONE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (um.deviceList.values.none { it.deviceName == device.deviceName }) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun awaitMediaRouteOffSpeaker() {
        val am = getSystemService(AudioManager::class.java)
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val start = SystemClock.elapsedRealtime()
        val deadline = start + ROUTE_RETURN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val routeOnSpeaker = am.getAudioDevicesForAttributes(media)
                .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            // The volume index follows the device of an active stream, not the strategy route:
            // the target app's track kept playing on the speaker for a moment after the route
            // had already switched, and the restore landed on the speaker (Air Link, 2026-10-24).
            val playingOnSpeaker = am.activePlaybackConfigurations
                .any { it.audioDeviceInfo?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (!routeOnSpeaker && !playingOnSpeaker) {
                AppLog.i(TAG, "media left the speaker after ${SystemClock.elapsedRealtime() - start} ms")
                return
            }
            Thread.sleep(50)
        }
        AppLog.w(TAG, "media route still on the speaker after $ROUTE_RETURN_TIMEOUT_MS ms; restoring volume anyway")
    }

    /**
     * Open a USB DAC persisted as "vendorId:productId:serial".
     * Returns null when the device is absent or this app lacks the USB permission
     * (permission grant is the UI's job; the service only checks).
     */
    private fun openUsbById(um: UsbManager, id: String): Pair<UsbDevice, UsbDeviceConnection>? {
        val parts = id.split(":")
        if (parts.size != 3) {
            AppLog.w(TAG, "openUsbById: 잘못된 id 형식 ($id)")
            return null
        }
        val vid = parts[0].toIntOrNull() ?: return null
        val pid = parts[1].toIntOrNull() ?: return null
        val device = um.deviceList.values.firstOrNull {
            // Must match MainScreen.usbId: "vendorId:productId:serial" (serialNumber may be null without USB permission)
            it.vendorId == vid && it.productId == pid && (it.serialNumber ?: "") == parts[2]
        } ?: run {
            AppLog.w(TAG, "openUsbById: 장치 찾을 수 없음 ($id)")
            return null
        }
        if (!um.hasPermission(device)) {
            AppLog.w(TAG, "openUsbById: USB 권한 없음 (${device.deviceName})")
            return null
        }
        return try {
            um.openDevice(device)?.let { device to it }
        } catch (e: Exception) {
            AppLog.e(TAG, "openUsbById: openDevice 실패 (${device.deviceName}): ${e.message}")
            null
        }
    }

    // ── 알림 ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_relay),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_relay_desc)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    /** Re-post the relay notification, with volume -/+ actions while a volume control is attached. */
    private fun updateNotification() {
        if (!relayingState.value) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(notifTitle, notifBody))
    }

    private fun buildNotification(title: String, body: String): Notification {
        fun service(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, RelayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val vol = RelayVolume.state.value
        val text = if (vol != null) "$body · ${volumeText(vol)}" else body
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (vol != null) {
            b.addAction(Notification.Action.Builder(null, getString(R.string.ui_volume_down), service(ACTION_VOLUME_DOWN, 2)).build())
            b.addAction(Notification.Action.Builder(null, getString(R.string.ui_volume_up), service(ACTION_VOLUME_UP, 3)).build())
        }
        b.addAction(Notification.Action.Builder(null, getString(R.string.notif_relay_stop), service(ACTION_STOP, 0)).build())
        return b.build()
    }

    private fun volumeText(v: VolumeState): String =
        if (v.hardware) getString(R.string.ui_volume_db, v.value / 256.0)
        else getString(R.string.ui_volume_pct, v.value)
}
