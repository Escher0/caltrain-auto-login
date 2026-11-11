// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class UpdateControlsTest {
    @Test fun `identical repeated results finish checking and only an explicit click opens GitHub`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = UpdatePreferences(app)
        prefs.prefs.edit().clear().commit()
        prefs.automatic = false // This test exercises explicit checks only.
        val source = ReleaseSource("owner/app")
        val work = ArrayDeque<Runnable>()
        val checker = UpdateChecker(prefs, source, BuildConfig.VERSION_NAME, { 100_000_000L }, { true },
            Executor { work.add(it) }, { it() }, { ReleaseResult(source.release("9.0.0"), true) }, {})
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val controls = UpdateControls(activity, ScreenComponents(activity), true, source, checker)
        activity.setContentView(controls.disclosure.view)
        controls.start()
        val check = controls.disclosure.view.findViewWithTag<Button>("updates-check")
        val status = controls.disclosure.view.findViewWithTag<TextView>("updates-status")
        repeat(2) {
            check.performClick()
            assertFalse(check.isEnabled)
            work.remove().run()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(check.isEnabled)
            assertFalse(status.text.contains("Checking"))
        }
        assertNull(shadowOf(activity).nextStartedActivity)
        controls.disclosure.view.findViewWithTag<Button>("updates-release").performClick()
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://github.com/owner/app/releases/tag/caltrain-android-v9.0.0", opened.data.toString())
        controls.stop()
        checker.start(UpdateReason.MANUAL)
        assertTrue(check.isEnabled) // Stopped controls no longer subscribe to state.
        work.remove().run()
        controller.pause().stop().destroy()
    }
}
