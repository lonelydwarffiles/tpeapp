package com.hound.controller.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.hound.controller.capability.TpeCapabilityService
import com.hound.controller.mindful.ToneEnforcementService
import com.hound.controller.review.RootChecker
import java.util.concurrent.TimeUnit

/**
 * Keeps required Accessibility services enabled and can self-heal on rooted devices.
 */
object AccessibilityServiceKeeper {
    private const val TAG = "AccessibilityKeeper"
    private const val ROOT_CMD_TIMEOUT_MS = 3_000L

    data class Status(
        val accessibilityEnabled: Boolean,
        val allRequiredEnabled: Boolean,
        val requiredServices: List<String>,
        val enabledServices: List<String>,
        val missingServices: List<String>,
        val rootAvailable: Boolean,
        val rootAttempted: Boolean,
        val rootSucceeded: Boolean,
    ) {
        fun toMap(): Map<String, Any> {
            return mapOf(
                "accessibility_enabled" to accessibilityEnabled,
                "all_required_enabled" to allRequiredEnabled,
                "required_services" to requiredServices,
                "enabled_services" to enabledServices,
                "missing_services" to missingServices,
                "root_available" to rootAvailable,
                "root_attempted" to rootAttempted,
                "root_succeeded" to rootSucceeded,
            )
        }
    }

    fun isFullyEnabled(context: Context): Boolean =
        getStatus(context).allRequiredEnabled

    fun getStatus(context: Context): Status {
        val packageName = context.packageName
        val required = requiredComponents(context)
        val enabledSetting = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
            .split(':')
            .asSequence()
            .mapNotNull { normalizeComponent(it, packageName) }
            .distinct()
            .toList()

        val missing = if (!enabledSetting) {
            required
        } else {
            required.filterNot { enabledServices.contains(it) }
        }

        return Status(
            accessibilityEnabled = enabledSetting,
            allRequiredEnabled = missing.isEmpty(),
            requiredServices = required,
            enabledServices = enabledServices,
            missingServices = missing,
            rootAvailable = RootChecker.cachedAvailable == true,
            rootAttempted = false,
            rootSucceeded = false,
        )
    }

    fun ensurePersistent(context: Context, reason: String): Status {
        val appContext = context.applicationContext
        val initial = getStatus(appContext)
        if (initial.allRequiredEnabled) {
            return initial
        }

        val rootAvailable = RootChecker.isRootAvailable()
        if (!rootAvailable) {
            Log.w(TAG, "Cannot re-enable accessibility services; root unavailable (reason=$reason)")
            return initial.copy(rootAvailable = false)
        }

        val mergedServices = (initial.enabledServices + initial.requiredServices)
            .distinct()
            .joinToString(":")

        val enableMasterOk = runRoot("settings put secure accessibility_enabled 1")
        val enableServicesOk = runRoot(
            "settings put secure enabled_accessibility_services '$mergedServices'"
        )

        val updated = getStatus(appContext)
        val rootSucceeded = enableMasterOk && enableServicesOk && updated.allRequiredEnabled
        if (rootSucceeded) {
            Log.i(TAG, "Accessibility services re-enabled via root (reason=$reason)")
        } else {
            Log.w(TAG, "Accessibility re-enable attempt incomplete (reason=$reason)")
        }

        return updated.copy(
            rootAvailable = true,
            rootAttempted = true,
            rootSucceeded = rootSucceeded,
        )
    }

    private fun requiredComponents(context: Context): List<String> {
        val tone = ComponentName(context, ToneEnforcementService::class.java)
        val capability = ComponentName(context, TpeCapabilityService::class.java)
        val scanner = ComponentName(context, ScreenScannerService::class.java)
        return listOf(tone, capability, scanner)
            .map { normalizeComponent(it.flattenToString(), context.packageName)!! }
    }

    private fun normalizeComponent(raw: String, appPackage: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val component = ComponentName.unflattenFromString(value) ?: return null
        val className = component.className
        val normalizedClass = if (className.startsWith('.')) {
            "$appPackage$className"
        } else {
            className
        }
        return "${component.packageName}/$normalizedClass"
    }

    private fun runRoot(command: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(ROOT_CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            exited && process.exitValue() == 0
        } catch (e: Exception) {
            Log.w(TAG, "Root command failed: $command", e)
            false
        }
    }
}