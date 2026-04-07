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
