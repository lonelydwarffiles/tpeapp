package com.tpeapp.vpn

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Persists handler-driven VPN policy/profile intent for phased rollout.
 *
 * This does not establish an OS VPN tunnel yet; it provides a stable
 * command/state contract so backend and UI can operate before the transport
 * implementation is plugged in.
 */
object VpnPolicyManager {

    private const val PREF_VPN_POLICY_JSON = "vpn_policy_json"
    private const val PREF_VPN_PROVIDER_MODE = "vpn_provider_mode"
    private const val PREF_VPN_PROFILE_ID = "vpn_profile_id"
    private const val PREF_VPN_DESIRED_STATE = "vpn_desired_state"
    private const val PREF_VPN_LAST_ACTION = "vpn_last_action"
    private const val PREF_VPN_UPDATED_AT_MS = "vpn_updated_at_ms"

    fun setPolicy(
        context: Context,
        policyJson: String?,
        providerMode: String?,
    ) {
        val nowMs = System.currentTimeMillis()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_VPN_POLICY_JSON, policyJson?.trim().orEmpty())
            .putString(PREF_VPN_PROVIDER_MODE, providerMode?.trim().orEmpty())
            .putString(PREF_VPN_LAST_ACTION, "SET_VPN_POLICY")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .apply()
    }

    fun setProviderProfile(
        context: Context,
        providerMode: String?,
        profileId: String?,
        policyJson: String?,
    ) {
        val nowMs = System.currentTimeMillis()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_VPN_PROVIDER_MODE, providerMode?.trim().orEmpty())
            .putString(PREF_VPN_PROFILE_ID, profileId?.trim().orEmpty())
            .putString(PREF_VPN_POLICY_JSON, policyJson?.trim().orEmpty())
            .putString(PREF_VPN_LAST_ACTION, "SET_VPN_PROVIDER_PROFILE")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .apply()
    }

    fun requestConnect(context: Context) {
        val nowMs = System.currentTimeMillis()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_VPN_DESIRED_STATE, "connected")
            .putString(PREF_VPN_LAST_ACTION, "VPN_CONNECT")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .apply()
    }

    fun requestDisconnect(context: Context) {
        val nowMs = System.currentTimeMillis()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_VPN_DESIRED_STATE, "disconnected")
            .putString(PREF_VPN_LAST_ACTION, "VPN_DISCONNECT")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .apply()
    }

    fun statusSnapshot(context: Context): Map<String, Any?> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val providerMode = prefs.getString(PREF_VPN_PROVIDER_MODE, "")?.trim().orEmpty()
        val profileId = prefs.getString(PREF_VPN_PROFILE_ID, "")?.trim().orEmpty()
        val policyJson = prefs.getString(PREF_VPN_POLICY_JSON, "")?.trim().orEmpty()
        val desiredState = prefs.getString(PREF_VPN_DESIRED_STATE, "")?.trim().orEmpty()
        val lastAction = prefs.getString(PREF_VPN_LAST_ACTION, "")?.trim().orEmpty()
        val updatedAtMs = prefs.getLong(PREF_VPN_UPDATED_AT_MS, 0L)

        return mapOf(
            "implemented" to false,
            "provider_mode" to providerMode.ifBlank { null },
            "vpn_profile_id" to profileId.ifBlank { null },
            "policy_configured" to policyJson.isNotBlank(),
            "desired_state" to desiredState.ifBlank { null },
            "connection_state" to "not_implemented",
            "last_action" to lastAction.ifBlank { null },
            "updated_at_ms" to if (updatedAtMs > 0L) updatedAtMs else null,
        )
    }
}
