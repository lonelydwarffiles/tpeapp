package com.tpeapp.mindful

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject

object PermissionToSpeakManager {

    private const val TAG = "PermissionToSpeakMgr"
    private const val PREF_ENABLED = "pts_enabled"
    private const val PREF_APPROVED = "pts_approved_contacts"
    private const val PREF_PENDING_PKG = "pts_pending_request_package"

    fun isEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun getApprovedContacts(ctx: Context): List<String> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_APPROVED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun setApprovedContacts(ctx: Context, list: List<String>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(it) } }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_APPROVED, arr.toString()).apply()
    }

    fun isApprovedPackage(ctx: Context, packageName: String): Boolean =
        getApprovedContacts(ctx).any { it.equals(packageName, ignoreCase = true) }

    fun requestPermission(ctx: Context, packageName: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_PENDING_PKG, packageName).apply()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        val payload = JSONObject().apply {
            put("event", "permission_to_speak_request")
            put("package", packageName)
            put("timestamp", System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(url, token, payload)
        Log.i(TAG, "Permission-to-speak request sent for $packageName")
    }

    fun grantPermission(ctx: Context, packageName: String) {
        val list = getApprovedContacts(ctx).toMutableList()
        if (!list.contains(packageName)) {
            list.add(packageName)
            setApprovedContacts(ctx, list)
        }
        Log.i(TAG, "Permission granted for $packageName")
    }
}
