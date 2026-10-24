package com.bitperfect.relay

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ui-spec Accessibility: the status line is a polite live region, so TalkBack announces its changes.
 * Reads the node the accessibility framework exports (what TalkBack sees), not Compose semantics.
 */
@RunWith(AndroidJUnit4::class)
class StatusLiveRegionTest {

    @Test
    fun statusLineIsPoliteLiveRegion() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val instr = InstrumentationRegistry.getInstrumentation()
            val ready = instr.targetContext.getString(R.string.ui_status_ready)
            var node: AccessibilityNodeInfo? = null
            for (i in 0 until 40) {
                // Compose's node provider does not implement findAccessibilityNodeInfosByText: walk the tree.
                node = instr.uiAutomation.rootInActiveWindow?.let { find(it, ready) }
                if (node != null) break
                Thread.sleep(250)
            }
            assertNotNull("status node \"$ready\" not found", node)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, node!!.liveRegion)
        }
    }

    private fun find(n: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (n.text?.toString() == text) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            find(c, text)?.let { return it }
        }
        return null
    }
}
