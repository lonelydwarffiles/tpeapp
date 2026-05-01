package com.tpeapp.bridge

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.tpeapp.mdm.AppDeviceAdminReceiver
import com.tpeapp.mdm.PartnerPinManager
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

    private const val CHANNEL = "com.tpeapp/device_admin"

    fun register(messenger: BinaryMessenger, context: Context) {
        val dpm     = context.getSystemService(DevicePolicyManager::class.java)
        val admin   = ComponentName(context, AppDeviceAdminReceiver::class.java)
        val pinMgr  = PartnerPinManager(context.applicationContext)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isAdminActive" -> {
                    result.success(dpm.isAdminActive(admin))
                }

                "requestActivation" -> {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Activate to enable uninstall protection and partner controls."
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    result.success(null)
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
                        dpm.setUninstallBlocked(admin, context.packageName, block)
                        result.success(null)
                    }.onFailure { e ->
                        result.error("DPM_ERROR", e.message, null)
                    }
                }

                else -> result.notImplemented()
            }
        }
    }
}
