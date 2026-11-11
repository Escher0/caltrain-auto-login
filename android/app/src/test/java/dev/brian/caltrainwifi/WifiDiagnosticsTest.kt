// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

package dev.brian.caltrainwifi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WifiDiagnosticsTest {
    @Test fun `diagnostics omit URL secrets regardless of scheme capitalization`() {
        val detail = WifiDiagnostics.describe("Loading final page", "www.caltrain.com",
            IOException("Failed HTTPS://user:password@example.com/private?token=secret\nretry"))
        assertTrue(detail.contains("host=www.caltrain.com"))
        assertTrue(detail.contains("Loading final page"))
        assertTrue(detail.contains("IOException"))
        assertFalse(detail.contains("password"))
        assertFalse(detail.contains("token="))
        assertFalse(detail.contains('\n'))
    }
    @Test fun `diagnostics bound long exception chains but retain underlying cause`() {
        val detail = WifiDiagnostics.describe("Loading portal", null,
            IOException("failed", IOException("underlying ${"x".repeat(5000)}")))
        assertTrue(detail.contains("underlying"))
        assertTrue(detail.length < 850)
    }
}
