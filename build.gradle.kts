// Top-level build file — configuration shared across sub-projects / modules.
plugins {
    id("com.android.application") apply false
    id("com.android.library")     apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.kapt")    apply false
}

tasks.register("xposed") {
    group = "build"
    description = "Shortcut to build the xposed module"
    dependsOn(":xposed:assemble")
}
