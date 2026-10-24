package com.bitperfect.relay.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCheckTest {

    @Test
    fun knownRuntimeBlockerIsBlockedEvenIfManifestAllows() {
        assertTrue(CaptureCheck.isBlocked("com.spotify.music", manifestAllows = true))
    }

    @Test
    fun manifestDisallowIsBlocked() {
        assertTrue(CaptureCheck.isBlocked("com.example.player", manifestAllows = false))
    }

    @Test
    fun manifestAllowIsNotBlocked() {
        assertFalse(CaptureCheck.isBlocked("com.apple.android.music", manifestAllows = true))
    }

    @Test
    fun unreadableManifestIsNotMarked() {
        // Unknown is not shown as blocked; the 5 s silence hint covers it while relaying.
        assertFalse(CaptureCheck.isBlocked("com.example.player", manifestAllows = null))
    }
}
