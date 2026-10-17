plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Jeremy Monitor — a tiny standalone app that logs battery temperature, charge level/rate, current draw
// and memory over time, so we can see whether running Jeremy alongside Waze stays cool and light.
// It has its own application id and shares nothing with the Jeremy app.
android {
    namespace = "com.jeremy.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jeremy.monitor"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/keys/ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
