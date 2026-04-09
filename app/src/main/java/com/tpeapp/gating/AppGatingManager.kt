package com.tpeapp.gating

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object AppGatingManager {

    private const val TAG = "AppGatingManager"
    private const val PREF_ENABLED = "gating_enabled"
    private const val PREF_APPROVED = "gating_approved_packages"
    private const val PREF_PENDING_PKG = "gating_pending_package"
    private const val PREF_PENDING_REQUEST_ID = "gating_pending_request_id"

    fun isEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun getApprovedPackages(ctx: Context): List<String> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_APPROVED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun setApprovedPackages(ctx: Context, list: List<String>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(it) } }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_APPROVED, arr.toString()).apply()
    }

    fun isApproved(ctx: Context, pkg: String): Boolean =
        getApprovedPackages(ctx).any { it.equals(pkg, ignoreCase = true) }

    fun requestAccess(ctx: Context, pkg: String): String {
        val requestId = UUID.randomUUID().toString()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_PENDING_PKG, pkg)
            .putString(PREF_PENDING_REQUEST_ID, requestId)
            .apply()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return requestId
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        WebhookManager.dispatchEvent(url, token, JSONObject().apply {
            put("event", "app_access_request")
            put("package", pkg)
            put("request_id", requestId)
            put("timestamp", System.currentTimeMillis())
        })
        Log.i(TAG, "App access request $requestId for $pkg")
        return requestId
    }

    fun approveRequest(ctx: Context, requestId: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val storedId = prefs.getString(PREF_PENDING_REQUEST_ID, null)
        if (storedId != requestId) {
            Log.w(TAG, "approveRequest: request ID mismatch ($requestId vs $storedId)")
            return
        }
        val pkg = prefs.getString(PREF_PENDING_PKG, null) ?: return
        val list = getApprovedPackages(ctx).toMutableList()
        if (!list.contains(pkg)) { list.add(pkg); setApprovedPackages(ctx, list) }
        prefs.edit().remove(PREF_PENDING_PKG).remove(PREF_PENDING_REQUEST_ID).apply()
        Log.i(TAG, "App access approved for $pkg")
    }

    fun denyRequest(ctx: Context, requestId: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val storedId = prefs.getString(PREF_PENDING_REQUEST_ID, null)
        if (storedId != requestId) {
            Log.w(TAG, "denyRequest: request ID mismatch")
            return
        }
        val pkg = prefs.getString(PREF_PENDING_PKG, null) ?: return
        prefs.edit().remove(PREF_PENDING_PKG).remove(PREF_PENDING_REQUEST_ID).apply()
        ConsequenceDispatcher.punish(ctx, "app_access_denied:$pkg")
        Log.i(TAG, "App access denied for $pkg — punishment dispatched")
    }
}
