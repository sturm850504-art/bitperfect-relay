package com.bitperfect.relay.service

import com.bitperfect.relay.diag.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Volume of the running relay. [hardware]: the DAC's Feature Unit, [value] in 1/256 dB.
 * Otherwise digital gain in the packer (not bit-perfect), [value] in percent.
 * [step] is the amount one +/- press or volume key changes.
 */
data class VolumeState(val hardware: Boolean, val value: Int, val min: Int, val max: Int, val step: Int)

/**
 * Bridge between the volume controls (screen slider, +/- buttons, notification actions, the
 * volume-key media session) and the USB session that applies the level. Set calls do USB control
 * transfers: call them off the main thread.
 */
object RelayVolume {
    private const val TAG = "BitPerfect"

    private val _state = MutableStateFlow<VolumeState?>(null)
    /** Null when no relay runs or the relay has no volume control. */
    val state: StateFlow<VolumeState?> = _state

    private val _offerDigital = MutableStateFlow(false)
    /** True while relaying to a DAC without hardware volume and digital volume is off. */
    val offerDigital: StateFlow<Boolean> = _offerDigital

    private var snap: (Int) -> Int = { it }
    private var apply: (Int) -> Boolean = { false }
    private var persist: (Int) -> Unit = {}

    @Synchronized
    fun attach(initial: VolumeState, snap: (Int) -> Int, apply: (Int) -> Boolean, persist: (Int) -> Unit) {
        this.snap = snap
        this.apply = apply
        this.persist = persist
        _offerDigital.value = false
        _state.value = initial
    }

    @Synchronized
    fun setOfferDigital(on: Boolean) {
        _offerDigital.value = on
    }

    @Synchronized
    fun detach() {
        apply = { false }
        persist = {}
        _state.value = null
        _offerDigital.value = false
    }

    /** Apply [value] (snapped to the control's range). False if no control is attached or the DAC refused. */
    @Synchronized
    fun set(value: Int): Boolean {
        val cur = _state.value ?: return false
        val v = snap(value)
        if (v == cur.value) return true
        if (!apply(v)) {
            AppLog.w(TAG, "volume: setting $v failed")
            return false
        }
        persist(v)
        _state.value = cur.copy(value = v)
        return true
    }

    /** One step up or down. */
    fun step(up: Boolean): Boolean {
        val cur = _state.value ?: return false
        return set(cur.value + if (up) cur.step else -cur.step)
    }

    /** Digital gain for [percent], Q16.16. [AI judgment] Square taper: closer to perceived loudness than linear. */
    fun digitalGainQ16(percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        return (65536L * p * p / 10000).toInt()
    }
}
