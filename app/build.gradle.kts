plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase config (google-services.json) is added once the Firebase project exists; without it Pro sign-in shows "not configured".
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.jeremy.dashcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jeremy.dashcam"
        minSdk = 26
        targetSdk = 36
        versionCode = 36
        versionName = "2.0"
        // test APK sent directly (not via Play): 64-bit ARM only, to keep the download small
        if (project.hasProperty("arm64Only")) ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Fixed key for the GitHub test APK, so Google sign-in (registered SHA-1) works on it.
        // Play builds are signed separately with the private upload key.
        getByName("debug") {
            storeFile = file("keys/ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8 shrinking: much smaller download (the Play AAB and the test APK are built from the same code)
            isMinifyEnabled = true
            isShrinkResources = true
            // emulator smoke test of the exact shrunk build: debuggable (run-as) + signed with the CI key
            if (project.hasProperty("smoke")) { isDebuggable = true; signingConfig = signingConfigs.getByName("debug") }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = false
        noCompress += "tflite"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val camerax = "1.5.0"
    val media3 = "1.4.1"
    val lifecycle = "2.8.6"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("androidx.lifecycle:lifecycle-service:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-process:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")

    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // PRO
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1") // plates (model via Play services)
    implementation("com.google.mediapipe:tasks-vision:0.10.35")                           // on-device AI object detection
    implementation("com.android.billingclient:billing:8.3.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    testImplementation("junit:junit:4.13.2")
}
