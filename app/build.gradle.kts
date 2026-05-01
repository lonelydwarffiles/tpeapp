plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.google.services)
}

android {
    namespace  = "com.tpeapp"
    compileSdk = 35

    defaultConfig {
        applicationId   = "com.tpeapp"
        minSdk          = 31   // Android 12 (Pixel 9 target is 14/15 → SDK 34/35)
        targetSdk       = 35
        versionCode     = 1
        versionName     = "1.0.0"

        // Include only the ABIs present on Pixel 9 Pro XL (arm64-v8a)
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        aidl        = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.service)
    implementation(libs.androidx.security.crypto)

    // Coroutines
    implementation(libs.coroutines.android)

    // TFLite – NNAPI delegate is bundled in the base artifact
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.support)
    implementation(libs.tflite.task.vision)
    implementation(libs.tflite.metadata)

    // Image-loading (needed for the xposed module target apps; also used in demo UI)
    implementation(libs.glide)
    kapt(libs.glide.compiler)
    implementation(libs.coil)
    implementation(libs.okhttp)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.firebase.analytics.ktx)

    // Blur utility
    implementation(libs.renderscript.toolkit)

    // CameraX — live preview for QR pairing screen + VideoCapture for adherence kiosk
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.video)

    // WorkManager — background audit upload for adherence module
    implementation(libs.workmanager)

    // ML Kit Barcode Scanning — reads accountability-partner QR codes
    implementation(libs.mlkit.barcode.scanning)

    // WebRTC — peer-review screen-sharing
    implementation(libs.webrtc)

    // Socket.IO — WebRTC signaling channel
    implementation(libs.socketio.client)

    // ── Flutter embedding ─────────────────────────────────────────────────────
    // When building with `flutter build apk` (or `flutter run`), the Flutter
    // Gradle plugin (applied via settings.gradle.kts) automatically provides the
    // flutter_embedding_release/debug AAR and the engine shared library.
    //
    // If you build this module outside of a Flutter toolchain, add:
    //   implementation("io.flutter:flutter_embedding_release:<engine-version>")
    // and ensure the libflutter.so artifact is present in jniLibs/arm64-v8a/.
    //
    // TpeFlutterActivity (com.tpeapp.bridge.TpeFlutterActivity) must be declared
    // as the launcher <activity> in AndroidManifest.xml, replacing the old
    // com.tpeapp.ui.MainActivity entry.
}
