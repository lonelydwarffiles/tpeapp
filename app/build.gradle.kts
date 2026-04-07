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
}
