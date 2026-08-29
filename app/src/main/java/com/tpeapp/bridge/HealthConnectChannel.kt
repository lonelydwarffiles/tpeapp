package com.hound.controller.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

object HealthConnectChannel {
    private const val CHANNEL = "com.hound.controller/health"
    private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    private const val READ_STEPS = "android.permission.health.READ_STEPS"

    private var permissionsLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingResult: MethodChannel.Result? = null

    fun register(messenger: BinaryMessenger, activity: ComponentActivity) {
        permissionsLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            val success = granted[READ_HEART_RATE] == true && granted[READ_STEPS] == true
            pendingResult?.success(success)
            pendingResult = null
        }

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> {
                    if (hasPermissions(activity)) {
                        result.success(true)
                        return@setMethodCallHandler
                    }

                    val launcher = permissionsLauncher
                    if (launcher == null) {
                        openHealthPermissionsUi(activity)
                        result.success(false)
                        return@setMethodCallHandler
                    }

                    pendingResult?.success(false)
                    pendingResult = result
                    launcher.launch(arrayOf(READ_HEART_RATE, READ_STEPS))
                }

                "hasPermissions" -> {
                    result.success(hasPermissions(activity))
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun hasPermissions(context: Context): Boolean {
        val heartRateGranted = ContextCompat.checkSelfPermission(
            context,
            READ_HEART_RATE,
        ) == PackageManager.PERMISSION_GRANTED
        val stepsGranted = ContextCompat.checkSelfPermission(
            context,
            READ_STEPS,
        ) == PackageManager.PERMISSION_GRANTED

        return heartRateGranted && stepsGranted
    }

    private fun openHealthPermissionsUi(context: Context) {
        // Prefer Health Connect permission rationale entry-point when available.
        val rationaleIntent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE").apply {
            `package` = context.packageName
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openedRationale = runCatching {
            context.startActivity(rationaleIntent)
            true
        }.getOrDefault(false)
        if (openedRationale) return

        // Fallback to Health Connect Play Store onboarding.
        val uriString =
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = Uri.parse(uriString)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("overlay", true)
                    putExtra("callerId", context.packageName)
                },
            )
        }
    }
}
