// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class UpdateCheckerTest {
    private lateinit var app: Application
    private lateinit var prefs: UpdatePreferences
    private val source=ReleaseSource("owner/app")
    @Before fun setup() { app=RuntimeEnvironment.getApplication(); prefs=UpdatePreferences(app); prefs.prefs.edit().clear().commit() }
    private class Queue : Executor {
        val tasks=java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.remove().run() }
    }
    @Test fun `failure checks throttle at twelve hours including failed requests and manual checks`() {
        val queue=Queue(); var now=100_000_000L; var requests=0
        val checker=UpdateChecker(prefs, source, "0.1.5", { now }, { true }, queue, { it() },
            { _ -> requests++; throw IOException("untrusted server detail") }, {})
        assertNotNull(checker.start(UpdateReason.FAILURE)); queue.run()
        assertFalse(prefs.message.contains("untrusted"))
        assertNull(checker.start(UpdateReason.FAILURE))
        now += 12*60*60*1000L-1
        assertNull(checker.start(UpdateReason.FAILURE))
        now++
        assertNotNull(checker.start(UpdateReason.FAILURE)); queue.run()
        assertNotNull(checker.start(UpdateReason.MANUAL)); queue.run()
        assertEquals(3,requests)
        assertNull(checker.start(UpdateReason.FAILURE))
    }
    @Test fun `app open checks daily but failures can check after twelve hours`() {
        val queue=Queue(); var now=100_000_000L
        val checker=UpdateChecker(prefs, source,"0.1.5", {now}, {true},queue,{it()},
            { ReleaseResult(source.release("0.1.5"),false) },{})
        checker.start(UpdateReason.OPEN); queue.run()
        now+=12*60*60*1000L
        assertNull(checker.start(UpdateReason.OPEN))
        assertNotNull(checker.start(UpdateReason.FAILURE)); queue.run()
        now-=60*60*1000L // Clock rollback does not create a tight automatic loop.
        assertNull(checker.start(UpdateReason.FAILURE))
    }
    @Test fun `offline unconfigured and opt out do not request or consume the throttle`() {
        val queue=Queue(); var online=false
        val checker=UpdateChecker(prefs,source,"0.1.5",{100_000_000L},{online},queue,{it()},
            { error("Unexpected request") },{})
        assertNull(checker.start(UpdateReason.MANUAL)); assertEquals(0L,prefs.lastAttempt)
        online=true; prefs.automatic=false
        assertNull(checker.start(UpdateReason.FAILURE)); assertNull(checker.start(UpdateReason.OPEN))
        assertEquals(0L,prefs.lastAttempt)
        assertNotNull(checker.start(UpdateReason.MANUAL))
        val disabled=UpdateChecker(prefs,null,"0.1.5",{0},{true},queue,{it()},{error("Unexpected")},{})
        assertNull(disabled.start(UpdateReason.MANUAL)); assertTrue(prefs.message.contains("not configured"))
    }
    @Test fun `single flight cancellation suppresses late result and preserves portal history`() {
        val queue=Queue(); val delivery=Queue(); var finished=0
        Settings(app).record("Portal history")
        val history=Settings(app).history
        val checker=UpdateChecker(prefs,source,"0.1.5",{100_000_000L},{true},queue,{ delivery.execute(it) },
            {ReleaseResult(source.release("0.2.0"),true)}, {})
        val request=checker.start(UpdateReason.FAILURE) {finished++}!!
        assertNull(checker.start(UpdateReason.MANUAL))
        queue.run(); request.cancel(); while(delivery.tasks.isNotEmpty()) delivery.run()
        assertNull(prefs.available(source,"0.1.5"))
        assertEquals(1,finished)
        assertEquals(history,Settings(app).history)
    }
    @Test fun `offer persists across app restarts but not installed version or repository changes`() {
        val queue=Queue(); var alerts=0
        val checker=UpdateChecker(prefs,source,"0.1.5",{100_000_000L},{true},queue,{it()},
            { ReleaseResult(source.release("0.2.0"),true) },{ alerts++ })
        checker.start(UpdateReason.FAILURE); queue.run()
        assertEquals("0.2.0",UpdatePreferences(app).available(source,"0.1.5")?.version)
        assertNull(prefs.available(source,"0.2.0"))
        assertFalse("Installed updates must not leave availability text", prefs.message.contains("available"))
        assertNull(prefs.available(ReleaseSource("other/app"),"0.1.5"))
        assertEquals(1,alerts)
    }
}
