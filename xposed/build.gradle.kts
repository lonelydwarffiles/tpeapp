plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.tpeapp.xposed"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
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
    // LSPosed API (compile-only — provided at runtime by the framework)
    compileOnly(libs.lsposed.api)
    compileOnly(libs.lsposed.service)

    // Coroutines for background work inside hooks
    implementation(libs.coroutines.android)

    // Blur utility (same toolkit used by the app module)
    implementation(libs.renderscript.toolkit)

    // AIDL stubs live in the :app module
    compileOnly(project(":app"))
}
