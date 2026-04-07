// Top-level build file — configuration shared across sub-projects / modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library)     apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.kotlin.kapt)         apply false
    alias(libs.plugins.google.services)     apply false
}
