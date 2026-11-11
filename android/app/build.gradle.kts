// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val productionRelease = providers.gradleProperty("caltrainRelease").orElse("false").get().toBooleanStrict()
val signingValues = listOf("CALTRAIN_KEYSTORE", "CALTRAIN_STORE_PASSWORD", "CALTRAIN_KEY_ALIAS", "CALTRAIN_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val hasSigning = signingValues.values.all { !it.isNullOrBlank() }
require(signingValues.values.all { it.isNullOrBlank() } || hasSigning) { "Release signing environment is incomplete" }
val updateRepository = providers.gradleProperty("caltrainUpdateRepository").orElse("").get()
require(updateRepository.isEmpty() || (updateRepository.matches(Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")) &&
    updateRepository.substringAfter('/') !in setOf(".", ".."))) { "caltrainUpdateRepository must be owner/repo" }
require(!productionRelease || (hasSigning && updateRepository.isNotEmpty())) {
    "Production releases require all signing credentials and caltrainUpdateRepository"
}
android {
    namespace = "dev.brian.caltrainwifi"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.brian.caltrainwifi"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
    }
    if (hasSigning) {
        signingConfigs.create("production") {
            storeFile = file(signingValues.getValue("CALTRAIN_KEYSTORE")!!)
            storePassword = signingValues.getValue("CALTRAIN_STORE_PASSWORD")
            keyAlias = signingValues.getValue("CALTRAIN_KEY_ALIAS")
            keyPassword = signingValues.getValue("CALTRAIN_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("production")
    }
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("release-assets"))
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":shared"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Resolve the exact shipped libraries for notices/source attachment generation.
tasks.register("releaseDependencies") {
    val destination = layout.buildDirectory.file("release-dependencies.json")
    outputs.file(destination)
    doLast {
        val components = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.id.componentIdentifier is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { it.moduleVersion.id.toString() }.distinct().sorted()
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.toJson(components))
        }
    }
}
