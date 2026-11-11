// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import org.junit.Assert.*
import org.junit.Test

class ConnectionUiStateTest {
    @Test fun `denied direct access shows actionable policy guidance rather than a DNS error`() {
        val view = ConnectionUiState.create(UiNetwork(42, false), true, true,
            AttemptUi(42, AttemptPhase.valueOf("ACCESS_DENIED"), "EPERM"), false)
        assertEquals(UiTone.ATTENTION, view.tone)
        assertTrue(view.detail.contains("VPN"))
        assertTrue(view.detail.contains("exclude", ignoreCase = true))
        assertFalse(view.heading.contains("EPERM"))
    }
    @Test fun `private DNS failure offers conditional guidance without claiming certainty`() {
        val view = ConnectionUiState.create(UiNetwork(42, false), true, true,
            AttemptUi(42, AttemptPhase.valueOf("PRIVATE_DNS_ERROR"), "UnknownHostException"), false)
        assertEquals(UiTone.ATTENTION, view.tone)
        assertTrue(view.detail.contains("Private DNS"))
        assertTrue(view.detail.contains("may"))
    }
    @Test fun `identified home wifi is never shown as connected to caltrain or offered login`() {
        val view = ConnectionUiState.create(UiNetwork(42, true, kind = WifiKind.OTHER), true, true,
            AttemptUi(42, AttemptPhase.ONLINE, "Connected"), false, identify = true)
        assertEquals("Waiting for\nCaltrain Wi-Fi", view.heading)
        assertEquals(UiAction.WIFI_SETTINGS, view.action)
        assertNotEquals(UiTone.SUCCESS, view.tone)
    }
    @Test fun `unknown validated wifi with permission shows generic connected status`() {
        val view = ConnectionUiState.create(UiNetwork(42, true), true, true, null, false, identify = true)
        assertEquals(UiAction.CONNECT, view.action)
        assertEquals(UiTone.SUCCESS, view.tone)
        assertEquals("You’re connected.", view.heading)
        assertEquals("WI-FI IS ONLINE", view.eyebrow)
    }
    @Test fun `unknown captive wifi with permission offers generic sign in`() {
        val view = ConnectionUiState.create(UiNetwork(42, false, captive = true), true, true, null, false, identify = true)
        assertEquals(UiAction.CONNECT, view.action)
        assertEquals("WI-FI DETECTED", view.eyebrow)
        assertNotEquals(UiTone.SUCCESS, view.tone)
    }
    @Test fun `unknown wifi fallback still requires portal consent`() {
        val view = ConnectionUiState.create(UiNetwork(42, false, captive = true), false, true, null, false, identify = true)
        assertEquals(UiAction.CONSENT, view.action)
    }
    @Test fun `identified validated caltrain has an explicit connected label`() {
        val view = ConnectionUiState.create(UiNetwork(42, true, kind = WifiKind.CALTRAIN), true, true,
            null, false, identify = true)
        assertEquals("Connected to\nCaltrain Wi-Fi", view.heading)
        assertEquals(UiTone.SUCCESS, view.tone)
    }
    private val wifi = UiNetwork(42, false)
    private fun state(network: UiNetwork? = wifi, consent: Boolean = true,
                      attempt: AttemptUi? = null, busy: Boolean = false) =
        ConnectionUiState.create(network, consent, true, attempt, busy)

    @Test fun `disconnected opens wifi settings even before consent`() {
        assertEquals(UiAction.WIFI_SETTINGS, state(null, false).action)
        assertNotEquals(UiTone.SUCCESS, state(null).tone)
    }
    @Test fun `unapproved captive connection asks for consent instead of signing in`() {
        assertEquals(UiAction.CONSENT, state(consent = false).action)
    }
    @Test fun `old network success and errors cannot describe the current connection`() {
        for (phase in listOf(AttemptPhase.ONLINE, AttemptPhase.DNS_ERROR, AttemptPhase.CONNECTING)) {
            val view = state(attempt = AttemptUi(7, phase, "Old network detail"))
            assertEquals(UiTone.NEUTRAL, view.tone)
            assertFalse(view.detail.contains("Old network"))
            assertEquals(UiAction.CONNECT, view.action)
        }
    }
    @Test fun `active background attempt is busy and cannot start again`() {
        val view = state(attempt = AttemptUi(42, AttemptPhase.CONNECTING, "Checking Internet access"), busy = true)
        assertEquals(UiAction.NONE, view.action)
        assertTrue(view.busy)
        assertTrue(view.detail.contains("Checking Internet"))
    }
    @Test fun `verified wifi is not mislabeled as a verified caltrain identity`() {
        val view = state(network = UiNetwork(42, true))
        assertEquals(UiTone.SUCCESS, view.tone)
        assertFalse(view.heading.contains("Caltrain"))
        assertEquals(UiAction.CONNECT, view.action)
    }
    @Test fun `portal acceptance alone never displays verified internet`() {
        val view = state(attempt = AttemptUi(42, AttemptPhase.UNVERIFIED, "Sign-in accepted"))
        assertEquals(UiTone.ATTENTION, view.tone)
        assertEquals(UiAction.CONNECT, view.action)
    }
    @Test fun `dns failure offers retry and keeps technical details out of headline`() {
        val view = state(attempt = AttemptUi(42, AttemptPhase.DNS_ERROR, "UnknownHostException: example"))
        assertEquals(UiTone.ATTENTION, view.tone)
        assertEquals(UiAction.CONNECT, view.action)
        assertFalse(view.heading.contains("UnknownHostException"))
    }
    @Test fun `old worker still blocks another request after network changes`() {
        val view = state(attempt = AttemptUi(7, AttemptPhase.CONNECTING, "Old connection"), busy = true)
        assertEquals(UiAction.NONE, view.action)
    }
    @Test fun `renewed captive portal overrides previous successful attempt on same wifi`() {
        val view = state(network = UiNetwork(42, false, captive = true),
            attempt = AttemptUi(42, AttemptPhase.ONLINE, "Connected"))
        assertNotEquals(UiTone.SUCCESS, view.tone)
        assertEquals(UiAction.CONNECT, view.action)
    }
    @Test fun `online first run still offers consent before a manual attempt`() {
        assertEquals(UiAction.CONSENT, state(network = UiNetwork(42, true), consent = false).action)
    }
    @Test fun `old successful probe cannot conceal lost validation on same wifi`() {
        val view = state(attempt = AttemptUi(42, AttemptPhase.ONLINE, "Connected"))
        assertNotEquals(UiTone.SUCCESS, view.tone)
        assertTrue(view.detail.contains("last check"))
        assertEquals(UiAction.CONNECT, view.action)
    }
}
