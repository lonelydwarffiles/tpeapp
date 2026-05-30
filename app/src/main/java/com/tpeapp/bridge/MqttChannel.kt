package com.tpeapp.bridge

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel

/**
 * EventChannel bridge for inbound MQTT command payloads.
 */
object MqttChannel {
    private const val EVENTS_CHANNEL = "com.hound.controller/mqtt_events"

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    fun register(messenger: BinaryMessenger, context: Context) {
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

    fun sendEvent(data: Map<String, String>) {
        eventSink?.success(data)
    }
}
