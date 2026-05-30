package com.hound.controller.xposed

/**
 * Constants shared between the Xposed module and the main app.
 * Duplicated here to avoid a direct dependency on the app module.
 */
object XposedConstants {
    const val ACTION_XPOSED_TONE_BLOCK = "com.hound.controller.ACTION_XPOSED_TONE_BLOCK"
    const val ACTION_XPOSED_TONE_INFRACTION = "com.hound.controller.ACTION_XPOSED_TONE_INFRACTION"
}

