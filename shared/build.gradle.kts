// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

plugins { id("org.jetbrains.kotlin.jvm"); `java-library` }

kotlin { jvmToolchain(17) }
sourceSets.test { resources.srcDir("fixtures") }
dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Android supplies org.json; desktop declares the JVM implementation.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
