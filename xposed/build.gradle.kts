plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.hound.controller.xposed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hound.controller.xposed"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        aidl = true     // needed to compile the shared .aidl stubs
    }
}

dependencies {
    // Xposed/LSPosed APIs (compile-only — provided at runtime by the framework)
    compileOnly(libs.xposed.api)
    compileOnly(libs.lsposed.api)
    compileOnly(libs.lsposed.service)

    // Coroutines for background work inside hooks
    implementation(libs.coroutines.android)

    // Blur utility (same toolkit used by the app module)
    implementation(libs.renderscript.toolkit)

    // AIDL stubs are needed but we can't depend on :app (an application)
    // implementation(project(":app"))
}
