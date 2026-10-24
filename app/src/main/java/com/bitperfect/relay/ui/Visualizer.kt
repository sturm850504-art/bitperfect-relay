package com.bitperfect.relay.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bitperfect.relay.service.RelayMeter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Visualizer forms, in swipe order. The first is the default (user, 2026-10-24). */
enum class VizMode { SCROLL, SCOPE, SPECTRUM, VECTOR, VU }

/** Color preset: background, left, right, accent (peaks, red zone), grid, meter face, scale ink, needle. */
class VizPreset(
    val bg: Color, val left: Color, val right: Color, val accent: Color,
    val grid: Color, val face: Color, val ink: Color, val needle: Color,
) {
    /** Editable slots in the color settings, in display order (grid follows ink). */
    fun slots(): List<Color> = listOf(bg, left, right, accent, ink, face, needle)

    companion object {
        fun fromSlots(c: List<Color>) = VizPreset(c[0], c[1], c[2], c[3], c[4].copy(alpha = 0.2f), c[5], c[4], c[6])
    }
}

/** User-tunable behavior: needle response (99% time of a step, s) and view delay offset (ms). */
data class VizTuning(val needleSec: Float = 0.6f, val syncOffsetMs: Int = 0)

val VIZ_PRESETS = listOf(
    // Amber: valve-amp glow.
    VizPreset(Color(0xFF1A1206), Color(0xFFFFB000), Color(0xFFFF7A00), Color(0xFFFF3B30),
        Color(0x33FFB000), Color(0xFF2A1C08), Color(0xFFFFC966), Color(0xFFFFE0A0)),
    // Vintage meter: cream face, black ink.
    VizPreset(Color(0xFFF3E6C4), Color(0xFF222222), Color(0xFF7A4A12), Color(0xFFC62828),
        Color(0x33222222), Color(0xFFF6E7B8), Color(0xFF1A1A1A), Color(0xFF111111)),
    // Green phosphor.
    VizPreset(Color(0xFF04140A), Color(0xFF39FF7A), Color(0xFF00C2A8), Color(0xFFFFD84D),
        Color(0x3339FF7A), Color(0xFF061D0F), Color(0xFF7DFFAA), Color(0xFFB8FFD0)),
    // Ice blue.
    VizPreset(Color(0xFF06101E), Color(0xFF5AC8FA), Color(0xFFA0E9FF), Color(0xFFFF6B8A),
        Color(0x335AC8FA), Color(0xFF0B1A2E), Color(0xFFBEE8FF), Color(0xFFFFFFFF)),
    // Neon magenta.
    VizPreset(Color(0xFF12051A), Color(0xFFFF4FD8), Color(0xFF8A5CFF), Color(0xFF00F0FF),
        Color(0x33FF4FD8), Color(0xFF1C0828), Color(0xFFFFA6EE), Color(0xFFFFE6FA)),
    // Mono.
    VizPreset(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFB0B0B0), Color(0xFFFF5252),
        Color(0x33FFFFFF), Color(0xFF111111), Color(0xFFE0E0E0), Color(0xFFFFFFFF)),
)

/**
 * Live view of the relayed audio (read from [RelayMeter]). Horizontal swipe changes the form,
 * vertical swipe the color preset. [description] is the TalkBack text; [actions] gives TalkBack
 * users the same changes, since a screen reader cannot swipe on a canvas.
 */
@Composable
fun Visualizer(
    mode: VizMode,
    preset: VizPreset,
    description: String,
    actions: List<Pair<String, () -> Unit>>,
    onModeStep: (Int) -> Unit,
    onPresetStep: (Int) -> Unit,
    tuning: VizTuning,
    modifier: Modifier = Modifier,
) {
    val state = remember { VizState() }
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameNs = it } }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = description
                customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
            }
            .pointerInput(Unit) {
                val threshold = 40.dp.toPx()
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        if (abs(dx) > abs(dy) && abs(dx) > threshold) onModeStep(if (dx < 0) 1 else -1)
                        else if (abs(dy) > threshold) onPresetStep(if (dy < 0) 1 else -1)
                    },
                ) { change, amount ->
                    // Consumed: the page must not scroll while the user swipes the view.
                    change.consume()
                    dx += amount.x
                    dy += amount.y
                }
            },
    ) {
        val now = frameNs
        drawRect(preset.bg)
        state.update(now, tuning)
        when (mode) {
            VizMode.SCROLL -> state.drawScroll(this, preset)
            VizMode.SCOPE -> state.drawScope(this, preset)
            VizMode.SPECTRUM -> state.drawSpectrum(this, preset, now)
            VizMode.VECTOR -> state.drawVector(this, preset)
            VizMode.VU -> state.drawVu(this, preset)
        }
    }
}

/** Buffers and the moving parts that keep state between frames (needles, bar decay, AGC). */
private class VizState {
    private val raw = FloatArray(VIEW_FRAMES * 2)
    private val blocks = FloatArray(RelayMeter.BLOCKS * RelayMeter.BLOCK_FIELDS)
    private var lastNs = 0L
    private var lastBlocks = 0L

    // VU needles: position as a fraction of full deflection, and velocity.
    private val needle = FloatArray(2)
    private val needleV = FloatArray(2)
    private val vuInput = FloatArray(2)

    // Spectrum.
    private val fft = Fft(FFT_N)
    private val re = FloatArray(FFT_N)
    private val im = FloatArray(FFT_N)
    private val window = FloatArray(FFT_N) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_N - 1))).toFloat() }
    private val windowSum = window.sum()
    private val bars = FloatArray(BARS)
    private val peaks = FloatArray(BARS)
    private val peakHoldUntil = LongArray(BARS)

    // Vectorscope auto gain.
    private var vecGain = 1f

    private var dt = 0f

    fun update(now: Long, tuning: VizTuning) {
        dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        // The view shows the audio when the DAC plays it: delayed by what the relay has buffered
        // (ring plus USB in flight), plus the user's offset (for the Bluetooth or DAC delay after it).
        val rate = RelayMeter.sampleRate
        val syncMs = (RelayMeter.delayMs + tuning.syncOffsetMs).coerceAtLeast(0)
        delayFrames = (syncMs.toLong() * rate / 1000).toInt().coerceIn(0, RelayMeter.RAW_FRAMES - VIEW_FRAMES)
        val delayBlocks = (syncMs / RelayMeter.BLOCK_MS).coerceAtMost(RelayMeter.BLOCKS - SCROLL_BLOCKS - LAG_BLOCKS)
        // Capture arrives in chunks of about 46 ms. A read position that advances at the block rate
        // and trails the newest data by LAG_BLOCKS turns the chunks into smooth motion.
        count = RelayMeter.blockCount
        val target = (count - LAG_BLOCKS - delayBlocks).coerceAtLeast(0).toDouble()
        if (count < lastBlocks || head > count) head = target // reset (rate change)
        head += dt * BLOCKS_PER_SEC
        head += (target - head) * min(1f, dt * 4f)
        head = head.coerceIn(0.0, count.toDouble())
        val headBlock = head.toLong()
        // VU input: the mean rectified level of the blocks the read position passed this frame.
        val fresh = (headBlock - lastBlocks).toInt().coerceIn(0, RelayMeter.BLOCKS / 2)
        if (fresh > 0) {
            RelayMeter.copyBlocks(blocks, fresh, (count - headBlock).toInt())
            val lvl = FloatArray(2)
            for (ch in 0..1) {
                var s = 0f
                for (k in 0 until fresh) s += blocks[k * RelayMeter.BLOCK_FIELDS + 4 + ch]
                // Mean rectified level x 1.111 (sine form factor) = RMS-equivalent, in dBFS.
                lvl[ch] = 20f * log10(max(s / fresh * 1.111f, 1e-4f))
            }
            trackSensitivity(max(lvl[0], lvl[1]))
            for (ch in 0..1) vuInput[ch] = vuDbFraction(lvl[ch] - sens + TARGET_VU)
            idleNs = 0L
        } else {
            idleNs += (dt * 1e9f).toLong()
            // No audio arriving (paused source): the needle falls back.
            if (idleNs > 150_000_000L) { vuInput[0] = 0f; vuInput[1] = 0f }
        }
        lastBlocks = headBlock
        // The coil current is smoothed before it moves the needle (the meter's rectifier and coil
        // inductance); without it the short block averages made the needle twitch (user, 2026-10-24).
        val k = 1f - kotlin.math.exp(-dt / INPUT_TAU_S)
        for (ch in 0..1) vuDrive[ch] += (vuInput[ch] - vuDrive[ch]) * k
        // Same zeta (1.5% overshoot); omega scaled so 99% of a step takes tuning.needleSec.
        omega = BASE_OMEGA * BASE_T99_S / tuning.needleSec.coerceIn(0.15f, 2f)
        stepNeedles()
    }

    private var delayFrames = 0
    private var omega = BASE_OMEGA
    private val vuDrive = FloatArray(2)

    /**
     * [AI judgment] Automatic meter sensitivity, like an engineer riding the meter-level knob.
     * Loudness of masters differs by 20 dB or more (loud pop pins a fixed -18 dBFS reference, seen
     * on the device), so a slow envelope of the level (up 2 s, down 8 s, held within -30..-6 dBFS)
     * is placed a little below 0 VU. Moves within a passage stay visible; the scale drifts slowly.
     */
    private fun trackSensitivity(levelDb: Float) {
        if (levelDb < SILENCE_DB) return
        val tau = if (levelDb > sens) SENS_UP_S else SENS_DOWN_S
        sens += (levelDb - sens) * (1f - kotlin.math.exp(-dt / tau))
        sens = sens.coerceIn(SENS_MIN_DB, SENS_MAX_DB)
    }

    private var sens = -14f
    private var count = 0L
    private var head = 0.0
    private var idleNs = 0L

    /**
     * Needle mechanics: a damped mass on a spring driven by the rectified signal, like a moving-coil
     * meter. VU standard (IEC 60268-17): 99% of a step in 300 ms, about 1.5% overshoot; zeta 0.8 and
     * omega 13.1 rad/s give exactly that (checked numerically). The user found that too nervous for
     * dense music, so the default is 0.6 s (adjustable). Fixed 1 ms substeps keep it stable.
     */
    private fun stepNeedles() {
        var t = dt
        while (t > 0f) {
            val h = min(t, 0.001f)
            for (ch in 0..1) {
                val a = omega * omega * (vuDrive[ch] - needle[ch]) - 2f * ZETA * omega * needleV[ch]
                needleV[ch] += a * h
                needle[ch] += needleV[ch] * h
                // End stops: the needle knocks against the pins and loses most of its speed.
                if (needle[ch] > PIN_HIGH) { needle[ch] = PIN_HIGH; needleV[ch] = -needleV[ch] * 0.3f }
                if (needle[ch] < 0f) { needle[ch] = 0f; needleV[ch] = -needleV[ch] * 0.2f }
            }
            t -= h
        }
    }

    // ── Scrolling waveform: last 3 s as a filled envelope, newest at the right ──
    fun drawScroll(d: DrawScope, p: VizPreset) = with(d) {
        val total = SCROLL_BLOCKS
        val headBlock = head.toLong()
        RelayMeter.copyBlocks(blocks, total, (count - headBlock).toInt())
        val colW = 3.dp.toPx()
        val cols = (size.width / colW).toInt().coerceAtLeast(1)
        // Columns cover fixed groups of absolute block numbers, so a column does not change while it
        // moves; only its x position follows the smooth read position.
        val perCol = ((total + cols - 1) / cols).coerceAtLeast(1)
        val lane = size.height / 2
        drawLine(p.grid, Offset(0f, lane / 2), Offset(size.width, lane / 2))
        drawLine(p.grid, Offset(0f, lane * 1.5f), Offset(size.width, lane * 1.5f))
        drawLine(p.grid, Offset(0f, lane), Offset(size.width, lane), strokeWidth = 1.dp.toPx())
        val amp = lane / 2 * 0.92f
        val first = headBlock - total // absolute number of blocks[0]
        val xs = ArrayList<Float>(cols + 2)
        val env = ArrayList<FloatArray>(cols + 2) // minL, maxL, minR, maxR, mean |L|, mean |R|
        var j = 0
        while (j < total) {
            val group = Math.floorDiv(first + j, perCol.toLong())
            val groupEnd = (group + 1) * perCol
            val jEnd = min(total.toLong(), groupEnd - first).toInt()
            val e = FloatArray(6)
            for (b in j until jEnd) {
                val o = b * RelayMeter.BLOCK_FIELDS
                e[0] = min(e[0], blocks[o]); e[1] = max(e[1], blocks[o + 1])
                e[2] = min(e[2], blocks[o + 2]); e[3] = max(e[3], blocks[o + 3])
                e[4] += blocks[o + 4]; e[5] += blocks[o + 5]
            }
            if (jEnd > j) { e[4] /= (jEnd - j); e[5] /= (jEnd - j) }
            j = jEnd
            val x = size.width - ((head - groupEnd) / perCol).toFloat() * colW
            if (x < -colW) continue
            xs += x
            env += e
        }
        // Peaks faint, average level solid (like audio editors): loud masters fill the peak envelope
        // almost completely, the average level still shows the beat.
        for (ch in 0..1) {
            val cy = if (ch == 0) lane / 2 else lane * 1.5f
            val color = if (ch == 0) p.left else p.right
            val peak = Path()
            for (i in xs.indices) {
                val y = cy - env[i][ch * 2 + 1].coerceIn(-1f, 1f) * amp
                if (i == 0) peak.moveTo(xs[i], y) else peak.lineTo(xs[i], y)
            }
            for (i in xs.indices.reversed()) peak.lineTo(xs[i], cy - env[i][ch * 2].coerceIn(-1f, 1f) * amp + 1f)
            peak.close()
            drawPath(peak, color.copy(alpha = 0.3f))
            val avg = Path()
            for (i in xs.indices) {
                // Mean |x| x 1.414 ~ the amplitude of a sine with that RMS.
                val a = (env[i][4 + ch] * 1.111f * 1.414f).coerceIn(0f, 1f) * amp
                if (i == 0) avg.moveTo(xs[i], cy - a) else avg.lineTo(xs[i], cy - a)
            }
            for (i in xs.indices.reversed()) {
                val a = (env[i][4 + ch] * 1.111f * 1.414f).coerceIn(0f, 1f) * amp
                avg.lineTo(xs[i], cy + a + 1f)
            }
            avg.close()
            drawPath(avg, color)
        }
    }

    // Scope traces kept for the phosphor afterglow (newest last).
    private val traces = ArrayDeque<FloatArray>()
    private var traceAgeNs = 0L

    // ── Oscilloscope: 20 ms, triggered on a rising zero crossing of the left channel ──
    // A new trace every 50 ms, older ones fading out like a CRT's phosphor (user: the plain trace
    // changed too fast to watch).
    fun drawScope(d: DrawScope, p: VizPreset) = with(d) {
        val rate = RelayMeter.sampleRate
        val win = (rate / 50).coerceIn(64, VIEW_FRAMES / 2)
        traceAgeNs += (dt * 1e9f).toLong()
        if (traces.isEmpty() || traceAgeNs >= SCOPE_TRACE_NS) {
            traceAgeNs = 0L
            RelayMeter.copyRaw(raw, 2 * win, delayFrames)
            val n = 2 * win
            var start = n - win
            for (i in n - win - 1 downTo 0) {
                if (raw[i * 2] <= 0f && raw[(i + 1) * 2] > 0f) { start = i + 1; break }
            }
            val pts = SCOPE_POINTS
            val t = FloatArray(pts * 2)
            for (k in 0 until pts) {
                val idx = start + k * win / pts
                t[k * 2] = raw[idx * 2].coerceIn(-1f, 1f)
                t[k * 2 + 1] = raw[idx * 2 + 1].coerceIn(-1f, 1f)
            }
            traces.addLast(t)
            while (traces.size > SCOPE_TRACES) traces.removeFirst()
        }
        val lane = size.height / 2
        drawLine(p.grid, Offset(0f, lane / 2), Offset(size.width, lane / 2))
        drawLine(p.grid, Offset(0f, lane * 1.5f), Offset(size.width, lane * 1.5f))
        drawLine(p.grid, Offset(0f, lane), Offset(size.width, lane), strokeWidth = 1.dp.toPx())
        for (k in 1..3) {
            val x = size.width * k / 4
            drawLine(p.grid, Offset(x, 0f), Offset(x, size.height))
        }
        val amp = lane / 2 * 0.92f
        traces.forEachIndexed { age, t ->
            val alpha = 0.12f + 0.88f * ((age + 1).toFloat() / traces.size).pow(2)
            for (ch in 0..1) {
                val path = Path()
                val cy = if (ch == 0) lane / 2 else lane * 1.5f
                for (k in 0 until SCOPE_POINTS) {
                    val x = size.width * k / (SCOPE_POINTS - 1)
                    val y = cy - t[k * 2 + ch] * amp
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, (if (ch == 0) p.left else p.right).copy(alpha = alpha), style = Stroke(1.5.dp.toPx()))
            }
        }
    }

    // ── Spectrum: 2048-point FFT of (L+R)/2, log-spaced bars, falling peak caps ──
    fun drawSpectrum(d: DrawScope, p: VizPreset, now: Long) = with(d) {
        val rate = RelayMeter.sampleRate
        RelayMeter.copyRaw(raw, FFT_N, delayFrames)
        for (i in 0 until FFT_N) {
            re[i] = (raw[i * 2] + raw[i * 2 + 1]) * 0.5f * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        val fMax = min(20000f, rate / 2f)
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (BARS - 1)) / BARS
        for (b in 0 until BARS) {
            val f0 = F_MIN * (fMax / F_MIN).pow(b.toFloat() / BARS)
            val f1 = F_MIN * (fMax / F_MIN).pow((b + 1).toFloat() / BARS)
            val k0 = (f0 * FFT_N / rate).toInt().coerceIn(1, FFT_N / 2 - 1)
            val k1 = (f1 * FFT_N / rate).toInt().coerceIn(k0, FFT_N / 2 - 1)
            var m = 0f
            for (k in k0..k1) m = max(m, re[k] * re[k] + im[k] * im[k])
            // Full-scale sine = 0 dB: amplitude 2|X| / sum(window).
            val db = 20f * log10(max(2f * sqrt(m) / windowSum, 1e-6f))
            val target = ((db - DB_FLOOR) / -DB_FLOOR).coerceIn(0f, 1f)
            // Bars jump up and fall at a fixed speed; caps hold, then fall slower.
            bars[b] = if (target > bars[b]) target else max(target, bars[b] - BAR_FALL * dt)
            if (bars[b] >= peaks[b]) { peaks[b] = bars[b]; peakHoldUntil[b] = now + PEAK_HOLD_NS }
            else if (now > peakHoldUntil[b]) peaks[b] = max(bars[b], peaks[b] - PEAK_FALL * dt)
            val x = b * (barW + gap)
            val h = bars[b] * size.height
            if (h > 0.5f) {
                drawRect(
                    Brush.verticalGradient(listOf(p.accent, p.left, p.right), startY = 0f, endY = size.height),
                    topLeft = Offset(x, size.height - h), size = Size(barW, h),
                )
            }
            drawRect(p.ink, topLeft = Offset(x, size.height * (1 - peaks[b]) - 2.dp.toPx()), size = Size(barW, 2.dp.toPx()))
        }
    }

    // ── Vectorscope (goniometer): mid up, side across, with a correlation bar ──
    fun drawVector(d: DrawScope, p: VizPreset) = with(d) {
        val n = 1024
        RelayMeter.copyRaw(raw, n, delayFrames)
        val cx = size.width / 2
        val barH = 6.dp.toPx()
        val cy = (size.height - barH * 3) / 2
        val r = min(size.width / 2, cy) * 0.92f
        drawLine(p.grid, Offset(cx - r, cy), Offset(cx + r, cy))
        drawLine(p.grid, Offset(cx, cy - r), Offset(cx, cy + r))
        drawLine(p.grid, Offset(cx - r * 0.7f, cy - r * 0.7f), Offset(cx + r * 0.7f, cy + r * 0.7f))
        drawLine(p.grid, Offset(cx + r * 0.7f, cy - r * 0.7f), Offset(cx - r * 0.7f, cy + r * 0.7f))
        var peak = 0f
        var lr = 0.0; var ll = 0.0; var rr = 0.0
        for (i in 0 until n) {
            val l = raw[i * 2]; val rv = raw[i * 2 + 1]
            peak = max(peak, max(abs(l), abs(rv)))
            lr += l * rv; ll += l * l; rr += rv * rv
        }
        // Gentle auto gain: quiet passages fill the view, loud ones stay inside (0.5x..8x).
        val want = (0.8f / max(peak, 0.1f)).coerceIn(0.5f, 8f)
        vecGain += (want - vecGain) * min(1f, dt * 2f)
        val pts = ArrayList<Offset>(n)
        for (i in 0 until n) {
            val l = raw[i * 2] * vecGain; val rv = raw[i * 2 + 1] * vecGain
            pts += Offset(cx + ((rv - l) * 0.5f).coerceIn(-1f, 1f) * r, cy - ((l + rv) * 0.5f).coerceIn(-1f, 1f) * r)
        }
        drawPoints(pts, PointMode.Points, p.left.copy(alpha = 0.7f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        val corr = if (ll > 1e-9 && rr > 1e-9) (lr / sqrt(ll * rr)).toFloat() else 0f
        val by = size.height - barH * 1.5f
        val bw = size.width * 0.8f
        val bx = (size.width - bw) / 2
        drawRoundRect(p.grid, Offset(bx, by), Size(bw, barH), CornerRadius(barH / 2))
        val mid = bx + bw / 2
        val end = mid + corr * bw / 2
        drawLine(if (corr < 0) p.accent else p.right, Offset(mid, by + barH / 2), Offset(end, by + barH / 2), strokeWidth = barH)
        drawLine(p.ink, Offset(mid, by - 2f), Offset(mid, by + barH + 2f))
    }

    // ── Analog VU meters, one per channel, needle on a pivot below the face ──
    fun drawVu(d: DrawScope, p: VizPreset) = with(d) {
        val gap = 8.dp.toPx()
        val w = (size.width - gap) / 2
        for (ch in 0..1) drawMeter(this, p, Offset(ch * (w + gap), 0f), Size(w, size.height), needle[ch], if (ch == 0) "L" else "R")
    }

    private fun drawMeter(d: DrawScope, p: VizPreset, origin: Offset, sz: Size, pos: Float, label: String) = with(d) {
        drawRoundRect(p.face, origin, sz, CornerRadius(10.dp.toPx()))
        // The scale arc spans 78% of the face width (labels stay inside); the pivot sits low so the arc top
        // is at 40% of the height, leaving room for the labels above it.
        val half = SWEEP / 2 * (PI / 180).toFloat()
        val arcR = sz.width * 0.39f / sin(half)
        val pivot = Offset(origin.x + sz.width / 2, origin.y + sz.height * 0.40f + arcR)
        fun angle(frac: Float) = (-SWEEP / 2 + frac * SWEEP) * (PI / 180).toFloat()
        fun at(frac: Float, rr: Float) = Offset(pivot.x + rr * sin(angle(frac)), pivot.y - rr * cos(angle(frac)))
        // Scale arc and red zone above 0 VU.
        val steps = 48
        for (s in 0 until steps) {
            val a = s.toFloat() / steps; val b = (s + 1).toFloat() / steps
            val red = a >= ZERO_VU_FRAC
            drawLine(if (red) p.accent else p.ink, at(a, arcR), at(b, arcR), strokeWidth = (if (red) 3f else 1.5f).dp.toPx())
        }
        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 10.dp.toPx()
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        for (db in VU_MARKS) {
            val frac = vuDbFraction(db)
            val major = db in VU_LABELS
            val color = if (db > 0) p.accent else p.ink
            drawLine(color, at(frac, arcR), at(frac, arcR + (if (major) 9 else 5).dp.toPx()), strokeWidth = 1.5.dp.toPx())
            if (major) {
                val tp = at(frac, arcR + 17.dp.toPx())
                paint.color = color.toArgb()
                drawIntoCanvas { it.nativeCanvas.drawText(if (db > 0) "+$db" else "$db", tp.x, tp.y + paint.textSize / 3, paint) }
            }
        }
        paint.color = p.ink.toArgb()
        paint.textSize = 14.dp.toPx()
        drawIntoCanvas {
            it.nativeCanvas.drawText("VU", pivot.x, pivot.y - arcR * 0.45f, paint)
            paint.textSize = 11.dp.toPx()
            it.nativeCanvas.drawText(label, origin.x + 14.dp.toPx(), origin.y + sz.height - 10.dp.toPx(), paint)
        }
        // Needle, with a soft shadow, from the pivot housing to just past the scale.
        val tip = at(pos, arcR * 1.06f)
        val base = pivot
        val shadow = Offset(2.dp.toPx(), 2.dp.toPx())
        drawLine(Color.Black.copy(alpha = 0.25f), base + shadow, tip + shadow, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(p.needle, base, tip, strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
        // Pivot housing: a half disc over the needle root, clipped by the face bottom.
        val hub = sz.width * 0.06f
        val bottom = origin.y + sz.height
        drawCircle(p.ink.copy(alpha = 0.6f), hub, Offset(pivot.x, min(pivot.y, bottom)))
        drawRect(p.bg, Offset(origin.x - 1f, bottom), Size(sz.width + 2f, hub + 2f))
        drawRoundRect(p.ink.copy(alpha = 0.15f), origin, sz, CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
    }

    companion object {
        const val FFT_N = 2048
        const val BARS = 40
        const val F_MIN = 30f
        const val DB_FLOOR = -80f
        const val BAR_FALL = 0.9f      // full height per second
        const val PEAK_FALL = 0.35f
        const val PEAK_HOLD_NS = 600_000_000L
        const val SCROLL_BLOCKS = 3000 / RelayMeter.BLOCK_MS
        const val VIEW_FRAMES = 8192
        const val SCOPE_POINTS = 400
        const val SCOPE_TRACES = 6
        const val SCOPE_TRACE_NS = 50_000_000L
        const val INPUT_TAU_S = 0.06f
        const val BLOCKS_PER_SEC = 1000.0 / RelayMeter.BLOCK_MS
        const val LAG_BLOCKS = 16 // 80 ms: covers one capture chunk
        const val BASE_OMEGA = 13.1f
        const val BASE_T99_S = 0.3f
        const val ZETA = 0.8f
        const val PIN_HIGH = 1.06f
        const val SWEEP = 96f // degrees
        const val SILENCE_DB = -60f
        const val SENS_UP_S = 2f
        const val SENS_DOWN_S = 8f
        const val SENS_MIN_DB = -30f
        const val SENS_MAX_DB = -6f
        const val TARGET_VU = -2f // the level envelope reads -2 VU
        val ZERO_VU_FRAC = 10f.pow(-3f / 20f)
        val VU_MARKS = listOf(-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3)
        val VU_LABELS = setOf(-20, -10, -7, -5, -3, 0, 3)

        /** Needle deflection is proportional to voltage, as in a real VU meter; full scale = +3 VU. */
        fun vuDbFraction(db: Int) = vuDbFraction(db.toFloat())
        fun vuDbFraction(db: Float) = 10f.pow((db - 3f) / 20f)
    }
}

/** In-place iterative radix-2 FFT. */
private class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { -sin(2 * PI * it / n).toFloat() }
    private val levels = 31 - Integer.numberOfLeadingZeros(n)

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tr = re[l] * cosT[k] - im[l] * sinT[k]
                    val ti = re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
