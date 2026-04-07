package com.tpeapp.webhook

import android.util.Log
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
 */
object WebhookManager {

    private const val TAG = "WebhookManager"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fires an asynchronous HTTP POST to [url] with a `Bearer` token in the
     * `Authorization` header and [payload] serialised as the JSON request body.
     *
     * Failures are logged but never propagated to the caller — webhook
     * delivery is best-effort and must not affect core filter behaviour.
     *
     * @param url         Fully-qualified HTTPS endpoint (e.g. `https://example.com/hook`).
     * @param bearerToken Token sent in the `Authorization: Bearer <token>` header.
     * @param payload     Arbitrary JSON object that forms the request body.
     */
    fun dispatchEvent(url: String, bearerToken: String, payload: JSONObject) {
        if (!url.startsWith("https://")) {
            Log.w(TAG, "Webhook URL must use HTTPS — skipping dispatch: $url")
            return
        }

        val body = payload.toString().toRequestBody(JSON_TYPE)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $bearerToken")
            .post(body)
            .build()

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
