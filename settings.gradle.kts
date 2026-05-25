pluginManagement {
    val localProperties = java.util.Properties()
    val localPropertiesFile = file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }
    val flutterSdkPath = localProperties.getProperty("flutter.sdk")
    if (!flutterSdkPath.isNullOrBlank()) {
        includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.6.0" apply false
    id("com.android.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.kapt") version "2.2.20" apply false
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        maven { url = uri("https://api.xposed.info/") }
        // JitPack for other dependencies
        maven("https://jitpack.io")
        mavenLocal()
    }
}

rootProject.name = "tpeapp"
include(":app")
include(":xposed")

// ── Flutter embedding ──────────────────────────────────────────────────────────
// When building via the Flutter toolchain (`flutter build apk` / `flutter run`
// from the flutter_app/ directory), Flutter's settings.gradle.kts shim
// automatically applies the Flutter Gradle plugin and registers the engine.
//
// If building from this root project directly without Flutter CLI, apply the
// Flutter plugin manually:
//
//   pluginManagement {
//     includeBuild("<flutter-sdk>/packages/flutter_tools/gradle")
//   }
//   plugins {
//     id("dev.flutter.flutter-gradle-plugin") version "1.0.0" apply false
//   }
//
// Then in app/build.gradle.kts add: apply(plugin = "dev.flutter.flutter-gradle-plugin")
