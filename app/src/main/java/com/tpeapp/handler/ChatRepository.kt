package com.tpeapp.handler

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ChatRepository — persists the conversation history in SharedPreferences and
 * sends messages to an OpenAI-compatible `/v1/chat/completions` endpoint.
 *
 * ### Dom-configurable prefs (writable via FCM)
 * | Key                          | Purpose                                         |
 * |------------------------------|-------------------------------------------------|
 * | [PREF_HANDLER_ENDPOINT]      | Base URL of the chat API, e.g. `https://api.openai.com` |
 * | [PREF_HANDLER_API_KEY]       | Bearer token / API key for the endpoint         |
 * | [PREF_HANDLER_SYSTEM_PROMPT] | System prompt that defines Handler's personality |
 * | [PREF_HANDLER_MODEL]         | Model name, e.g. `gpt-4o`                      |
 *
 * History is capped at [MAX_HISTORY] messages to avoid unbounded SharedPreferences growth.
 * The system prompt is *not* stored in the history list — it is prepended fresh on every
 * API call.
 */
object ChatRepository {

    private const val TAG = "ChatRepository"

    // ------------------------------------------------------------------
    //  SharedPreferences keys
    // ------------------------------------------------------------------

    const val PREF_HANDLER_ENDPOINT      = "handler_endpoint"
    const val PREF_HANDLER_API_KEY       = "handler_api_key"
    const val PREF_HANDLER_SYSTEM_PROMPT = "handler_system_prompt"
    const val PREF_HANDLER_MODEL         = "handler_model"
    private const val PREF_CHAT_HISTORY  = "handler_chat_history_json"

    // ------------------------------------------------------------------
    //  Defaults
    // ------------------------------------------------------------------

    private const val DEFAULT_ENDPOINT      = "https://api.openai.com"
    private const val DEFAULT_MODEL         = "gpt-4o"
    private const val DEFAULT_SYSTEM_PROMPT =
        "You are Handler, a strict but caring AI companion in a TPE (Total Power Exchange) " +
        "dynamic. You speak with authority and warmth. You hold the sub accountable to their " +
        "rules, offer guidance, and track their progress. You may use the word 'Handler' to " +
        "refer to yourself. Keep replies concise unless the sub needs detailed guidance."

    /** Maximum number of messages kept in history (oldest are pruned first). */
    private const val MAX_HISTORY = 100

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    //  History persistence
    // ------------------------------------------------------------------

    fun getHistory(ctx: Context): List<ChatMessage> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(
                    id        = o.getString("id"),
                    role      = o.getString("role"),
                    content   = o.getString("content"),
                    timestamp = o.getLong("timestamp")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse chat history", e)
            emptyList()
        }
    }

    fun addMessage(ctx: Context, message: ChatMessage): List<ChatMessage> {
        val list = getHistory(ctx).toMutableList()
        list.add(message)
        if (list.size > MAX_HISTORY) {
            list.subList(0, list.size - MAX_HISTORY).clear()
        }
        saveHistory(ctx, list)
        return list.toList()
    }

    fun clearHistory(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .remove(PREF_CHAT_HISTORY).apply()
    }

    // ------------------------------------------------------------------
    //  API call — blocking; call on a background thread / coroutine
    // ------------------------------------------------------------------

    /**
     * Sends the user's [userMessage] (plus history context) to the configured
     * OpenAI-compatible endpoint and returns the assistant's reply text.
     *
     * Throws an [Exception] on network failure or non-200 response.
     */
    fun sendMessage(ctx: Context, userMessage: String): String {
        val prefs    = PreferenceManager.getDefaultSharedPreferences(ctx)
        val endpoint = prefs.getString(PREF_HANDLER_ENDPOINT, DEFAULT_ENDPOINT)
            ?.trimEnd('/') ?: DEFAULT_ENDPOINT
        val apiKey   = prefs.getString(PREF_HANDLER_API_KEY, null)?.takeIf { it.isNotBlank() }
        val model    = prefs.getString(PREF_HANDLER_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val systemPrompt = prefs.getString(PREF_HANDLER_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

        // Build the messages array: [system] + recent history + [new user message]
        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })

        // Include only the last 20 turns to stay within context window limits
        val history = getHistory(ctx).takeLast(20)
        history.forEach { msg ->
            messages.put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
        }
        messages.put(JSONObject().apply { put("role", "user"); put("content", userMessage) })

        val requestBody = JSONObject().apply {
            put("model",    model)
            put("messages", messages)
        }.toString().toRequestBody("application/json".toMediaType())

        val reqBuilder = Request.Builder()
            .url("$endpoint/v1/chat/completions")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
        if (apiKey != null) reqBuilder.addHeader("Authorization", "Bearer $apiKey")

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response body")
            if (!response.isSuccessful) {
                Log.w(TAG, "API error ${response.code}: $body")
                throw Exception("Handler API error ${response.code}")
            }
            val json = JSONObject(body)
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    // ------------------------------------------------------------------
    //  Setters (called by FCM handlers)
    // ------------------------------------------------------------------

    fun setEndpoint(ctx: Context, endpoint: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_HANDLER_ENDPOINT, endpoint).apply()
    }

    fun setApiKey(ctx: Context, key: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_HANDLER_API_KEY, key).apply()
    }

    fun setSystemPrompt(ctx: Context, prompt: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_HANDLER_SYSTEM_PROMPT, prompt).apply()
    }

    fun setModel(ctx: Context, model: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_HANDLER_MODEL, model).apply()
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private fun saveHistory(ctx: Context, list: List<ChatMessage>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("role", m.role)
                put("content", m.content); put("timestamp", m.timestamp)
            })
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_CHAT_HISTORY, arr.toString()).apply()
    }

    /** Convenience factory for a new user message. */
    fun newUserMessage(text: String) = ChatMessage(
        id        = UUID.randomUUID().toString(),
        role      = "user",
        content   = text,
        timestamp = System.currentTimeMillis()
    )

    /** Convenience factory for a new assistant message. */
    fun newAssistantMessage(text: String) = ChatMessage(
        id        = UUID.randomUUID().toString(),
        role      = "assistant",
        content   = text,
        timestamp = System.currentTimeMillis()
    )
}
