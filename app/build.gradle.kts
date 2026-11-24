plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.djfulanipro"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.djfulanipro"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Phase 2 uses Android's native MediaPlayer for four independent deck playback.
    // The low-latency Oboe engine will be integrated in a later audio-engine milestone.
}
