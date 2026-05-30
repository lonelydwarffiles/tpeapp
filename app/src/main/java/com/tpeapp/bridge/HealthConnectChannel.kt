package com.tpeapp.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

object HealthConnectChannel {

    private const val CHANNEL = "com.hound.controller/health"
    private const val HEALTH_PERMISSION_READ_HEART_RATE =
        "android.permission.health.READ_HEART_RATE"
    private const val HEALTH_PERMISSION_READ_STEPS =
        "android.permission.health.READ_STEPS"

    private var permissionsLauncher: ActivityResultLauncher<Set<String>>? = null
    private var pendingPermissions: Set<String> = emptySet()
    private var pendingResult: MethodChannel.Result? = null

    fun register(messenger: BinaryMessenger, activity: ComponentActivity) {
        permissionsLauncher = activity.registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            val success = hasPermissions(activity) ||
                (pendingPermissions.isNotEmpty() && granted.containsAll(pendingPermissions))
            pendingResult?.success(success)
            pendingResult = null
            pendingPermissions = emptySet()
        }

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> {
                    val status = HealthConnectClient.getSdkStatus(activity)
                    if (status != HealthConnectClient.SDK_AVAILABLE) {
                        launchHealthConnectInstall(activity)
                        result.success(false)
                        return@setMethodCallHandler
                    }

                    val launcher = permissionsLauncher
                    if (launcher == null) {
                        result.success(false)
                        return@setMethodCallHandler
                    }

                    if (hasPermissions(activity)) {
                        result.success(true)
                        return@setMethodCallHandler
                    }

                    pendingPermissions = setOf(
                        HEALTH_PERMISSION_READ_HEART_RATE,
                        HEALTH_PERMISSION_READ_STEPS,
                    )
                    pendingResult = result
                    launcher.launch(pendingPermissions)
                }

                "hasPermissions" -> {
                    result.success(hasPermissions(activity))
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun hasPermissions(context: Context): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) return false

        val heartRateGranted = ContextCompat.checkSelfPermission(
            context,
            HEALTH_PERMISSION_READ_HEART_RATE,
        ) == PackageManager.PERMISSION_GRANTED
        val stepsGranted = ContextCompat.checkSelfPermission(
            context,
            HEALTH_PERMISSION_READ_STEPS,
        ) == PackageManager.PERMISSION_GRANTED

        return heartRateGranted && stepsGranted
    }

    private fun launchHealthConnectInstall(context: Context) {
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
