package com.tpeapp.bridge

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessaging
import com.tpeapp.fcm.PartnerFcmService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * FcmChannel — MethodChannel bridge for FCM token access and incoming push routing.
 *
 * Channel names:
 *  - `com.tpeapp/fcm`        (MethodChannel — reads and refreshes the FCM token)
 *  - `com.tpeapp/fcm_events` (EventChannel  — forwards FCM data payloads to the Dart UI)
 *
 * Methods exposed to Dart:
 *  - `getToken`  → String?  — cached token from SharedPreferences
 *  - `refresh`              — forces a new token fetch; result delivered via EventChannel
 *
 * [PartnerFcmService] continues to handle all FCM messages natively.  The
 * EventChannel here allows Dart screens (e.g. home screen) to react to UI-relevant
 * pushes without duplicating native handling.  Call [sendEvent] from
 * [PartnerFcmService.onMessageReceived] for any action that the Dart layer cares about.
 */
object FcmChannel {

    private const val CHANNEL        = "com.tpeapp/fcm"
    private const val EVENTS_CHANNEL = "com.tpeapp/fcm_events"

    /** Held so [PartnerFcmService] can push events to the Dart layer at any time. */
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    fun register(messenger: BinaryMessenger, context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getToken" -> {
                    result.success(prefs.getString(PartnerFcmService.PREF_FCM_TOKEN, null))
                }
                "refresh" -> {
                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { token ->
                            prefs.edit().putString(PartnerFcmService.PREF_FCM_TOKEN, token).apply()
                            result.success(token)
                        }
                        .addOnFailureListener { e ->
                            result.error("FCM_ERROR", e.message, null)
                        }
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(messenger, EVENTS_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    eventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            }
        )
    }

    /**
     * Forwards an FCM data payload to the Dart layer.
     *
     * Call this from [com.tpeapp.fcm.PartnerFcmService.onMessageReceived] for any
     * action that the Flutter UI should react to (e.g. SHOW_AFFIRMATION, REQUEST_CHECKIN).
     *
     * @param data The raw FCM data map (action key plus any additional fields).
     */
    fun sendEvent(data: Map<String, String>) {
        eventSink?.success(data)
    }
}
