plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val updateRepository = providers.gradleProperty("caltrainUpdateRepository").orElse("").get()
require(updateRepository.isEmpty() || (updateRepository.matches(Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")) &&
    updateRepository.substringAfter('/') !in setOf(".", ".."))) { "caltrainUpdateRepository must be owner/repo" }
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
