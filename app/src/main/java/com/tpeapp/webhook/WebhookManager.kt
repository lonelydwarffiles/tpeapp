package com.tpeapp.webhook

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebhookManager — a singleton that dispatches event payloads to an arbitrary
 * HTTPS endpoint using a Bearer-token-authenticated POST request.
 *
 * All network calls are made asynchronously via [OkHttpClient.enqueue] so
 * callers are never blocked.
 *
 * Call [init] once from [com.tpeapp.TpeApplication.onCreate] so that all
 * outbound webhook payloads are automatically enriched with the `device_id`
 * field required by the Camera-Site backend for multi-device routing.
 */
object WebhookManager {

    private const val TAG = "WebhookManager"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Application context used to read `device_id` from SharedPreferences. */
    private var appContext: Context? = null

    /**
     * Must be called once from [com.tpeapp.TpeApplication.onCreate].
     * Stores the application context so that every [dispatchEvent] call can
     * automatically inject the `device_id` field into the outbound payload.
     */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /**
     * Fires an asynchronous HTTP POST to [url] with an optional `Bearer` token in
     * the `Authorization` header and [payload] serialised as the JSON request body.
     *
     * If [bearerToken] is null or blank the `Authorization` header is omitted.
     * The Camera-Site backend accepts requests without authentication when no
     * webhook secret has been configured server-side.
     *
     * The `device_id` value stored in SharedPreferences is automatically injected
     * into [payload] (if [init] has been called and the key is non-blank) so that
     * the server can attribute every event to a specific device in multi-sub setups.
     *
     * Failures are logged but never propagated to the caller — webhook
     * delivery is best-effort and must not affect core filter behaviour.
     *
     * @param url         Fully-qualified HTTPS endpoint (e.g. `https://example.com/hook`).
     * @param bearerToken Optional Bearer token for `Authorization` header.
     * @param payload     Arbitrary JSON object that forms the request body.
     */
    fun dispatchEvent(url: String, bearerToken: String?, payload: JSONObject) {
        if (!url.startsWith("https://")) {
            Log.w(TAG, "Webhook URL must use HTTPS — skipping dispatch: $url")
            return
        }

        // Inject device_id for server-side device routing if not already present.
        appContext?.let { ctx ->
            val deviceId = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("device_id", null)?.takeIf { it.isNotBlank() }
            if (deviceId != null && !payload.has("device_id")) {
                payload.put("device_id", deviceId)
            }
        }

        val body = payload.toString().toRequestBody(JSON_TYPE)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
        if (!bearerToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $bearerToken")
        }

        val request = requestBuilder.build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Webhook delivery failed → $url", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "Webhook delivered → $url (HTTP ${it.code})")
                    } else {
                        Log.w(TAG, "Webhook rejected → $url (HTTP ${it.code})")
                    }
                }
            }
        })
    }
}
