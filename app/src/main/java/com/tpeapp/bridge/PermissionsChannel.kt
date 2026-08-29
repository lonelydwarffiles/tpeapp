package com.hound.controller.bridge

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * Native permission bridge used when permission_handler plugin wiring is unavailable.
 */
object PermissionsChannel {

    private const val CHANNEL = "com.hound.controller/permissions"
    private const val REQUEST_CODE = 9210

    private var pendingResult: MethodChannel.Result? = null
    private var pendingPermissions: List<String> = emptyList()

    fun register(messenger: BinaryMessenger, activity: FlutterFragmentActivity) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getStatuses" -> {
                    val permissions = normalizePermissions(call.argument<List<String>>("permissions"))
                    result.success(statusMap(activity, permissions))
                }

                "requestAndCheck" -> {
                    val permissions = normalizePermissions(call.argument<List<String>>("permissions"))
                    if (permissions.isEmpty()) {
                        result.success(emptyMap<String, Boolean>())
                        return@setMethodCallHandler
                    }

                    val missing = permissions.filter {
                        ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                    }

                    if (missing.isEmpty()) {
                        result.success(statusMap(activity, permissions))
                        return@setMethodCallHandler
                    }

                    if (pendingResult != null) {
                        result.error("BUSY", "Permission request already in progress", null)
                        return@setMethodCallHandler
                    }

                    pendingResult = result
                    pendingPermissions = permissions
                    ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQUEST_CODE)
                }

                "openAppSettings" -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    fun onRequestPermissionsResult(
        activity: FlutterFragmentActivity,
        requestCode: Int,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false

        val result = pendingResult
        val permissions = pendingPermissions
        pendingResult = null
        pendingPermissions = emptyList()

        result?.success(statusMap(activity, permissions))
        return true
    }

    private fun normalizePermissions(raw: List<String>?): List<String> {
        return raw.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun statusMap(activity: FlutterFragmentActivity, permissions: List<String>): Map<String, Boolean> {
        return permissions.associateWith {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}