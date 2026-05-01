pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack for LSPosed API
        maven("https://jitpack.io")
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
