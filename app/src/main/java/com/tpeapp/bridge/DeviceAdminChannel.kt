package com.tpeapp.bridge

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.tpeapp.mdm.AppDeviceAdminReceiver
import com.tpeapp.mdm.PartnerPinManager
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * DeviceAdminChannel — MethodChannel bridge for Device Admin and partner-PIN management.
 *
 * Channel name: `com.tpeapp/device_admin`
 *
 * Methods exposed to Dart:
 *  - `isAdminActive`                       → Boolean
 *  - `requestActivation`                   → launches the system Device Admin activation intent
 *  - `deactivate`          (pin: String)   → verifies PIN then calls removeActiveAdmin()
 *  - `isPinSet`                            → Boolean
 *  - `setPin`              (pin: String)   → stores a new PBKDF2-hashed partner PIN
 *  - `verifyPin`           (pin: String)   → Boolean — true if PIN matches stored hash
 *  - `clearPin`                            → removes the stored PIN (post-deactivation)
 *  - `blockUninstall`      (block: Boolean)→ calls setUninstallBlocked via DPM
 */
object DeviceAdminChannel {

    private const val CHANNEL = "com.hound.controller/device_admin"

    fun register(messenger: BinaryMessenger, activity: FlutterFragmentActivity) {
        val appContext = activity.applicationContext
        val dpm     = appContext.getSystemService(DevicePolicyManager::class.java)
        val admin   = ComponentName(appContext, AppDeviceAdminReceiver::class.java)
        val pinMgr  = PartnerPinManager(appContext)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isAdminActive" -> {
                    result.success(dpm.isAdminActive(admin))
                }

                "requestActivation" -> {
                    val intent = if (dpm.isAdminActive(admin)) {
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                    } else {
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Activate to enable uninstall protection and partner controls."
                            )
                        }
                    }
                    activity.startActivity(intent)
                    result.success(null)
                }

                "openAdminSettings" -> {
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }.onSuccess {
                        result.success(null)
                    }.onFailure { e ->
                        result.error("ADMIN_SETTINGS_ERROR", e.message, null)
                    }
                }

                "deactivate" -> {
                    val pin = call.argument<String>("pin")
                        ?: return@setMethodCallHandler result.error("INVALID", "pin required", null)
                    if (!pinMgr.isPinSet()) {
                        return@setMethodCallHandler result.error("NO_PIN", "No PIN configured", null)
                    }
                    if (pinMgr.verifyPin(pin)) {
                        dpm.removeActiveAdmin(admin)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }

                "isPinSet" -> result.success(pinMgr.isPinSet())

                "setPin" -> {
                    val pin = call.argument<String>("pin")
                        ?: return@setMethodCallHandler result.error("INVALID", "pin required", null)
                    if (pin.isBlank()) {
                        return@setMethodCallHandler result.error("INVALID", "PIN must not be blank", null)
                    }
                    pinMgr.setPin(pin)
                    result.success(null)
                }

                "verifyPin" -> {
                    val pin = call.argument<String>("pin")
                        ?: return@setMethodCallHandler result.error("INVALID", "pin required", null)
                    result.success(pinMgr.isPinSet() && pinMgr.verifyPin(pin))
                }

                "clearPin" -> {
                    pinMgr.clearPin()
                    result.success(null)
                }

                "blockUninstall" -> {
                    val block = call.argument<Boolean>("block")
                        ?: return@setMethodCallHandler result.error("INVALID", "block required", null)
                    runCatching {
                        dpm.setUninstallBlocked(admin, appContext.packageName, block)
                        result.success(null)
                    }.onFailure { e ->
                        result.error("DPM_ERROR", e.message, null)
                    }
                }

                "lockNow" -> {
                    runCatching {
                        dpm.lockNow()
                        result.success(true)
                    }.onFailure { e ->
                        result.error("DPM_ERROR", e.message, null)
                    }
                }

                "isIgnoringBatteryOptimizations" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        result.success(true)
                    } else {
                        val pm = appContext.getSystemService(PowerManager::class.java)
                        result.success(pm?.isIgnoringBatteryOptimizations(appContext.packageName) == true)
                    }
                }

                "requestIgnoreBatteryOptimizations" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        result.success(null)
                    } else {
                        runCatching {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${appContext.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(intent)
                            result.success(null)
                        }.onFailure { e ->
                            result.error("BATTERY_OPT_ERROR", e.message, null)
                        }
                    }
                }

                "openBatteryOptimizationSettings" -> {
                    runCatching {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appContext.startActivity(intent)
                        result.success(null)
                    }.onFailure { e ->
                        result.error("BATTERY_OPT_ERROR", e.message, null)
                    }
                }

                else -> result.notImplemented()
            }
        }
    }
}
