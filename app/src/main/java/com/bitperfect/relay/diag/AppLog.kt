package com.bitperfect.relay.diag

import android.util.Log

/**
 * Logcat plus an in-app ring of the last [CAPACITY] lines, for the diagnostics popup.
 * The app never reads the system logcat (task 2026-10-24); everything it logs goes through here.
 */
object AppLog {
    const val CAPACITY = 100

    data class Line(val epochMs: Long, val level: Char, val text: String)

    private val lines = ArrayDeque<Line>(CAPACITY)

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add('I', msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        add('W', if (tr != null) "$msg: $tr" else msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        add('E', if (tr != null) "$msg: $tr" else msg)
    }

    @Synchronized
    private fun add(level: Char, text: String) {
        if (lines.size == CAPACITY) lines.removeFirst()
        lines.addLast(Line(System.currentTimeMillis(), level, text))
    }

    @Synchronized
    fun snapshot(): List<Line> = lines.toList()
}
