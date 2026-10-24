package com.bitperfect.relay

import android.util.Log

/**
 * JNI 브릿지 — 네이티브 엔진(`libengine.so`)을 Kotlin에서 호출한다.
 */
object Engine {

    private const val TAG = "BitPerfect"

    init {
        System.loadLibrary("engine")
    }

    fun init(nativeLibDir: String = "") {
        nativeInit(nativeLibDir)
        Log.i(TAG, "Engine loaded (version=${version()})")
    }

    fun destroy() {
        nativeDestroy()
    }

    fun version(): String = nativeVersion()

    fun logLevel(level: Int) {
        nativeSetLogLevel(level)
    }

    /** 네이티브에서 호출 가능한 로그 출력 (C 함수 `engine_native_log` ↔ Kotlin). */
    fun nativeLog(level: Int, tag: String, msg: String) {
        Log.println(level, tag, msg)
    }

    // ── USB DAC output: native byte pump (cpp/usb/usbplay.h) ───────
    // Used only by output/usb/UsbDacOutput, which owns claiming, alt setting and rate.

    /** Wrap the UsbDeviceConnection fd with libusb. 0 ok, negative on failure (usbLastError). */
    fun usbOpen(fd: Int): Int = nativeUsbOpen(fd)

    /** libusb_speed of the open device (2 full, 3 high, ...), -1 if not open. */
    fun usbSpeed(): Int = nativeUsbSpeed()

    /** Start the iso pump. Parameters are documented in usbplay.h. 0 ok, negative on failure. */
    fun usbStart(
        epOut: Int, maxPacketBytes: Int, bInterval: Int, epFb: Int, fbPacketBytes: Int,
        rateHz: Int, frameBytes: Int, fillMax: Boolean, primeMs: Int, capacityMs: Int,
    ): Int = nativeUsbStart(epOut, maxPacketBytes, bInterval, epFb, fbPacketBytes, rateHz, frameBytes, fillMax, primeMs, capacityMs)

    /** Queue [len] packed PCM bytes. Bytes queued, 0 if dropped (ring full), negative if the stream is down. */
    fun usbWrite(data: ByteArray, len: Int): Int = nativeUsbWrite(data, len)

    /** 0 streaming or cleanly stopped, -1 failed (usbLastError). */
    fun usbOk(): Int = nativeUsbOk()

    /** [underruns, silentFrames, droppedFrames, feedback Q16.16, errorPackets, ringFrames, sentFrames, fbRaw, fbLen, fbShift,
     *  shortPackets, fbRejected, inFlightUs, dataXfers, packetsPerXfer, stallRestarts] (usbplay.h). */
    fun usbStats(): LongArray = LongArray(16).also { nativeUsbStats(it) }

    /** Last native USB log lines, "<epoch ms>|<I/W/E>|<text>" per line (usbplay_log). */
    fun usbLog(): String = nativeUsbLog()

    /** Stop the pump (idempotent). */
    fun usbStop() = nativeUsbStop()

    /** Re-bind the kernel driver to [iface] of usbfs [fd]. >= 0 ok, -errno on failure. */
    fun usbReattach(fd: Int, iface: Int): Int = nativeUsbReattach(fd, iface)

    /** Stop and close the libusb handle and context (the fd stays open). Idempotent. */
    fun usbClose() = nativeUsbClose()

    /** Last USB error message. */
    fun usbLastError(): String = nativeUsbLastError()

    // ── 9단계 런타임 라이브러리 교체 (dl/dllib.cpp) ─────────────
    // libusb is not linked into libengine.so; it is dlopen-ed
    // (CMake: usb SHARED). 아래 호출은 출력을 잠시 quiesce 한 뒤
    // 새 .so 로 교체하고, 실패하면 이전 버전으로 자동 복원한다 (libs.md 계약).

    /** 라이브러리 교체. key: "libusb", path: 새로 쓸 .so 전체 경로. 0 성공, -1 실패 (libLastError 확인). */
    fun applyLib(key: String, path: String): Int = nativeApplyLib(key, path)

    /** key 로 현재 로드된 .so 경로. 미로드면 null. */
    fun libActivePath(key: String): String? = nativeLibActivePath(key)

    /** 마지막 라이브러리 교체 오류 문구 (없으면 빈 문자열). */
    fun libLastError(): String = nativeLibLastError()

    // ── native ──────────────────────────────────────────────────
    private external fun nativeInit(dir: String)
    private external fun nativeDestroy()
    private external fun nativeVersion(): String
    private external fun nativeSetLogLevel(level: Int)
    private external fun nativeUsbOpen(fd: Int): Int
    private external fun nativeUsbSpeed(): Int
    private external fun nativeUsbStart(
        epOut: Int, maxPacketBytes: Int, bInterval: Int, epFb: Int, fbPacketBytes: Int,
        rateHz: Int, frameBytes: Int, fillMax: Boolean, primeMs: Int, capacityMs: Int,
    ): Int
    private external fun nativeUsbWrite(data: ByteArray, len: Int): Int
    private external fun nativeUsbOk(): Int
    private external fun nativeUsbStats(out: LongArray)
    private external fun nativeUsbStop()
    private external fun nativeUsbReattach(fd: Int, iface: Int): Int
    private external fun nativeUsbClose()
    private external fun nativeUsbLastError(): String
    private external fun nativeUsbLog(): String
    private external fun nativeApplyLib(key: String, path: String): Int
    private external fun nativeLibActivePath(key: String): String?
    private external fun nativeLibLastError(): String
}
