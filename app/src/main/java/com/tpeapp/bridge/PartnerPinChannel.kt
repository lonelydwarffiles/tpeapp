package com.tpeapp.bridge

import android.content.Context
import com.tpeapp.mdm.PartnerPinManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * PartnerPinChannel — MethodChannel bridge for [PartnerPinManager].
 *
 * Channel name: `com.tpeapp/partner_pin`
 *
 * Methods exposed to Dart:
 *  - `isPinSet`                        → Boolean
 *  - `setPin`          (pin: String)   → stores a new PBKDF2-hashed PIN
 *  - `verifyPin`       (pin: String)   → Boolean
 *  - `clearPin`                        → removes stored PIN
 *
 * Note: [DeviceAdminChannel] duplicates the PIN surface so callers can reach
 * PIN verification inline with admin operations.  This channel exists for
 * standalone PIN management screens (e.g., initial setup, PIN change).
 */
object PartnerPinChannel {

    private const val CHANNEL = "com.tpeapp/partner_pin"

    fun register(messenger: BinaryMessenger, context: Context) {
        val pinMgr = PartnerPinManager(context.applicationContext)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
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

                else -> result.notImplemented()
            }
        }
    }
}
