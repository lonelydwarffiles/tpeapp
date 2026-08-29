package com.hound.controller.util

import android.content.Context
import android.util.Log
import com.hound.controller.ble.LovenseManager
import com.hound.controller.ble.PavlokManager
import kotlin.random.Random

object BackgroundBleHapticTrigger {
    private const val TAG = "BleHapticTrigger"
    private const val DEFAULT_PROBABILITY = 0.08f
    private const val DEFAULT_COOLDOWN_MS = 8_000L

    @Volatile
    private var lastTriggerAtMs: Long = 0L

    fun maybeTrigger(
        context: Context,
        reason: String,
        probability: Float = DEFAULT_PROBABILITY,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ) {
        if (Random.nextFloat() > probability.coerceIn(0f, 1f)) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < cooldownMs.coerceAtLeast(0L)) return
        lastTriggerAtMs = now

        runCatching {
            LovenseManager.init(context)
            PavlokManager.init(context)
            LovenseManager.startScan()
            PavlokManager.startScan()

            if (Random.nextBoolean()) {
                LovenseManager.vibrate(Random.nextInt(4, 11))
                Log.d(TAG, "Triggered Lovense background haptic reason=$reason")
            } else {
                PavlokManager.vibrate(
                    intensity = Random.nextInt(80, 151),
                    durationMs = Random.nextInt(300, 901),
                )
                Log.d(TAG, "Triggered Pavlok background haptic reason=$reason")
            }
        }.onFailure {
            Log.w(TAG, "Background BLE haptic trigger failed reason=$reason", it)
        }
    }
}
