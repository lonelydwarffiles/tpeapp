package com.hound.controller.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-side VPN policy + runtime state manager.
 *
 * Supports two execution paths:
 * 1) local_capture/local_vpn mode via [TrafficCaptureVpnService] for handler-driven
 *    traffic restriction and packet/byte counters.
 * 2) provider handoff mode (launch VPN app / VPN settings) for external providers.
 */
object TpeVpnPolicyManager {

    private const val PREF_VPN_POLICY_JSON = "vpn_policy_json"
    private const val PREF_VPN_PROVIDER_MODE = "vpn_provider_mode"
    private const val PREF_VPN_PROFILE_ID = "vpn_profile_id"
    private const val PREF_VPN_DESIRED_STATE = "vpn_desired_state"
    private const val PREF_VPN_LAST_ACTION = "vpn_last_action"
    private const val PREF_VPN_UPDATED_AT_MS = "vpn_updated_at_ms"
    private const val PREF_VPN_LAST_RESULT = "vpn_last_result"
    private const val PREF_VPN_LAST_ERROR = "vpn_last_error"
    private const val PREF_VPN_LAST_ACTION_AT_MS = "vpn_last_action_at_ms"
    private const val PREF_VPN_TUNNEL_ACTIVE = "vpn_tunnel_active"
    private const val PREF_VPN_TUNNEL_STARTED_AT_MS = "vpn_tunnel_started_at_ms"
    private const val PREF_VPN_CAPTURE_BYTES = "vpn_capture_bytes"
    private const val PREF_VPN_CAPTURE_PACKETS = "vpn_capture_packets"
    private const val PREF_VPN_FORWARDED_BYTES = "vpn_forwarded_bytes"
    private const val PREF_VPN_FORWARDED_PACKETS = "vpn_forwarded_packets"
    private const val PREF_VPN_DROPPED_PACKETS = "vpn_dropped_packets"
    private const val PREF_VPN_FLOW_ENDPOINT_COUNTS_JSON = "vpn_flow_endpoint_counts_json"
    private const val PREF_VPN_FLOW_DOMAIN_COUNTS_JSON = "vpn_flow_domain_counts_json"
    private const val PREF_VPN_FLOW_PACKAGE_COUNTS_JSON = "vpn_flow_package_counts_json"
    private const val PREF_VPN_BLOCKED_EVENTS = "vpn_blocked_events"
    private const val PREF_VPN_BLOCKED_DOMAIN_COUNTS_JSON = "vpn_blocked_domain_counts_json"
    private const val PREF_VPN_BLOCKED_DOMAIN_RULES_JSON = "vpn_blocked_domain_rules_json"
    private const val PREF_VPN_MITM_CA_ALIAS = "vpn_mitm_ca_alias"
    private const val PREF_VPN_MITM_CA_GENERATED_AT_MS = "vpn_mitm_ca_generated_at_ms"
    private const val PREF_VPN_MITM_CA_INSTALL_REQUESTED_AT_MS = "vpn_mitm_ca_install_requested_at_ms"
    private const val PREF_VPN_MITM_ENABLED = "vpn_mitm_enabled"
        private const val PREF_SPLIT_TUNNEL_ENABLED = "vpn_split_tunnel_enabled"
        private const val PREF_SPLIT_TUNNEL_PACKAGES = "vpn_split_tunnel_packages"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

    /**
     * Ensures VPN is enabled by default for local policy mode.
     *
     * Behavior:
    * - If no desired state exists yet, default to connected.
     * - If desired state is explicitly disconnected, respect it.
     * - If permission is already granted, start the local tunnel automatically.
     * - If permission is missing, record pending user action but do not force a prompt
     *   from background lifecycle entry points.
     */
    fun ensureDefaultEnabled(context: Context, source: String) {
        val nowMs = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        var desiredState = prefs.getString(PREF_VPN_DESIRED_STATE, "")?.trim().orEmpty()
        val providerMode = prefs.getString(PREF_VPN_PROVIDER_MODE, "")?.trim().orEmpty()
        val lastAction = prefs.getString(PREF_VPN_LAST_ACTION, "")?.trim().orEmpty()

        val edit = prefs.edit()
        var changed = false
        if (providerMode.isBlank()) {
            edit.putString(PREF_VPN_PROVIDER_MODE, "local_capture")
            changed = true
        }
        if (desiredState.isBlank()) {
            desiredState = "connected"
            edit.putString(PREF_VPN_DESIRED_STATE, desiredState)
            changed = true
        } else if (
            desiredState.equals("disconnected", ignoreCase = true) &&
            lastAction.startsWith("AUTO_VPN_DEFAULT:", ignoreCase = true)
        ) {
            // Upgrade legacy auto-defaulted devices from disconnected to connected.
            desiredState = "connected"
            edit.putString(PREF_VPN_DESIRED_STATE, desiredState)
            changed = true
        }
        if (changed) {
            edit.putString(PREF_VPN_LAST_ACTION, "AUTO_VPN_DEFAULT:$source")
                .putString(PREF_VPN_LAST_RESULT, "default_applied")
                .putString(PREF_VPN_LAST_ERROR, "")
                .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
                .putLong(PREF_VPN_LAST_ACTION_AT_MS, nowMs)
                .apply()
        }

        if (!desiredState.equals("connected", ignoreCase = true)) return

        val tunnelActivePref = prefs.getBoolean(PREF_VPN_TUNNEL_ACTIVE, false)
        val vpnTransportActive = isVpnTransportActive(context)
        if (vpnTransportActive) return

        if (tunnelActivePref) {
            // Process restarts can leave this flag stale even when VPN transport is gone.
            prefs.edit()
                .putBoolean(PREF_VPN_TUNNEL_ACTIVE, false)
                .putString(PREF_VPN_LAST_RESULT, "reconnect_pending")
                .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
                .apply()
        }

        if (VpnService.prepare(context) != null) {
            if (source.equals("app_on_create", ignoreCase = true)) {
                requestConnect(context)
                return
            }
            setLastResult(context, "permission_required", "Open app once to grant VPN permission")
            return
        }

        requestConnect(context)
    }

    data class LocalTunnelPolicy(
        val useLocalTunnel: Boolean,
        val restrictionMode: String,
        val blockedPackages: List<String>,
        val allowedPackages: List<String>,
        val captureIpv6: Boolean,
    )

    private val PROVIDER_PACKAGE_DEFAULTS = mapOf(
        "wireguard" to "com.wireguard.android",
        "openvpn" to "net.openvpn.openvpn",
        "ics_openvpn" to "de.blinkt.openvpn",
        "proton" to "ch.protonvpn.android",
        "mullvad" to "net.mullvad.mullvadvpn",
        "tailscale" to "com.tailscale.ipn",
        "cloudflare" to "com.cloudflare.onedotonedotonedotone",
    )

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
            .putString(PREF_VPN_LAST_RESULT, "stored")
            .putString(PREF_VPN_LAST_ERROR, "")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .putLong(PREF_VPN_LAST_ACTION_AT_MS, nowMs)
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
            .putString(PREF_VPN_LAST_RESULT, "stored")
            .putString(PREF_VPN_LAST_ERROR, "")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .putLong(PREF_VPN_LAST_ACTION_AT_MS, nowMs)
            .apply()
    }

    fun requestConnect(context: Context) {
        val nowMs = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(PREF_VPN_DESIRED_STATE, "connected")
            .putString(PREF_VPN_LAST_ACTION, "VPN_CONNECT")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .putLong(PREF_VPN_LAST_ACTION_AT_MS, nowMs)
            .apply()

        val localPolicy = localTunnelPolicy(context)
        if (localPolicy.useLocalTunnel) {
            when (TrafficCaptureVpnService.requestConnect(context)) {
                TrafficCaptureVpnService.CONNECT_RESULT_PERMISSION_PROMPTED -> {
                    setLastResult(context, "permission_prompted", null)
                }
                TrafficCaptureVpnService.CONNECT_RESULT_SERVICE_START_REQUESTED -> {
                    setLastResult(context, "service_start_requested", null)
                }
                else -> {
                    setLastResult(context, "failed", "Local VPN service start failed")
                }
            }
            return
        }

        val launched = executeProviderAction(context, prefs, connect = true)
        if (launched) {
            setLastResult(context, "launch_requested", null)
        } else {
            val handedOff = openVpnSettings(context)
            if (handedOff) {
                setLastResult(context, "settings_prompted", null)
            } else {
                setLastResult(context, "failed", "No VPN provider launch target available")
            }
        }
    }

    fun requestDisconnect(context: Context) {
        val nowMs = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(PREF_VPN_DESIRED_STATE, "disconnected")
            .putString(PREF_VPN_LAST_ACTION, "VPN_DISCONNECT")
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
            .putLong(PREF_VPN_LAST_ACTION_AT_MS, nowMs)
            .apply()

        val localPolicy = localTunnelPolicy(context)
        if (localPolicy.useLocalTunnel) {
            TrafficCaptureVpnService.requestDisconnect(context)
            setLastResult(context, "disconnect_requested", null)
            return
        }

        val launched = executeProviderAction(context, prefs, connect = false)
        if (launched) {
            setLastResult(context, "launch_requested", null)
        } else {
            val handedOff = openVpnSettings(context)
            if (handedOff) {
                setLastResult(context, "settings_prompted", null)
            } else {
                setLastResult(context, "failed", "No VPN disconnect handoff available")
            }
        }
    }

    fun localTunnelPolicy(context: Context): LocalTunnelPolicy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val providerMode = prefs.getString(PREF_VPN_PROVIDER_MODE, "")?.trim().orEmpty()
        val policyJson = prefs.getString(PREF_VPN_POLICY_JSON, "")?.trim().orEmpty()
        val policy = parsePolicy(policyJson)

        val restrictionMode = policy?.optString("restriction_mode")
            ?.ifBlank { policy.optString("mode") }
            ?.trim()
            ?.lowercase()
            .takeUnless { it.isNullOrBlank() }
            ?: "off"

        val blocked = parseStringArray(policy, "blocked_packages", "blockedPackages")
        val allowed = parseStringArray(policy, "allowed_packages", "allowedPackages")
        val captureIpv6 = parseBoolean(policy, "capture_ipv6", "captureIpv6", defaultValue = false)

        val normalizedProvider = providerMode.lowercase()
        val localProvider = normalizedProvider in setOf("local_capture", "local_vpn", "tpe")
        val shouldUseLocal = localProvider || restrictionMode != "off" || blocked.isNotEmpty() || allowed.isNotEmpty()

        return LocalTunnelPolicy(
            useLocalTunnel = shouldUseLocal,
            restrictionMode = restrictionMode,
            blockedPackages = blocked,
            allowedPackages = allowed,
            captureIpv6 = captureIpv6,
        )
    }

    fun statusSnapshot(context: Context): Map<String, Any?> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val providerMode = prefs.getString(PREF_VPN_PROVIDER_MODE, "")?.trim().orEmpty()
        val profileId = prefs.getString(PREF_VPN_PROFILE_ID, "")?.trim().orEmpty()
        val policyJson = prefs.getString(PREF_VPN_POLICY_JSON, "")?.trim().orEmpty()
        val desiredState = prefs.getString(PREF_VPN_DESIRED_STATE, "")?.trim().orEmpty()
        val lastAction = prefs.getString(PREF_VPN_LAST_ACTION, "")?.trim().orEmpty()
        val updatedAtMs = prefs.getLong(PREF_VPN_UPDATED_AT_MS, 0L)
        val lastResult = prefs.getString(PREF_VPN_LAST_RESULT, "")?.trim().orEmpty()
        val lastError = prefs.getString(PREF_VPN_LAST_ERROR, "")?.trim().orEmpty()
        val lastActionAtMs = prefs.getLong(PREF_VPN_LAST_ACTION_AT_MS, 0L)
        val tunnelActive = prefs.getBoolean(PREF_VPN_TUNNEL_ACTIVE, false)
        val tunnelStartedAtMs = prefs.getLong(PREF_VPN_TUNNEL_STARTED_AT_MS, 0L)
        val captureBytes = prefs.getLong(PREF_VPN_CAPTURE_BYTES, 0L)
        val capturePackets = prefs.getLong(PREF_VPN_CAPTURE_PACKETS, 0L)
        val forwardedBytes = prefs.getLong(PREF_VPN_FORWARDED_BYTES, 0L)
        val forwardedPackets = prefs.getLong(PREF_VPN_FORWARDED_PACKETS, 0L)
        val droppedPackets = prefs.getLong(PREF_VPN_DROPPED_PACKETS, 0L)
        val endpointFlowJson = prefs.getString(PREF_VPN_FLOW_ENDPOINT_COUNTS_JSON, "")?.trim().orEmpty()
        val domainFlowJson = prefs.getString(PREF_VPN_FLOW_DOMAIN_COUNTS_JSON, "")?.trim().orEmpty()
        val packageFlowJson = prefs.getString(PREF_VPN_FLOW_PACKAGE_COUNTS_JSON, "")?.trim().orEmpty()
        val blockedEvents = prefs.getLong(PREF_VPN_BLOCKED_EVENTS, 0L)
        val blockedDomainsJson = prefs.getString(PREF_VPN_BLOCKED_DOMAIN_COUNTS_JSON, "")?.trim().orEmpty()
        val blockedRulesJson = prefs.getString(PREF_VPN_BLOCKED_DOMAIN_RULES_JSON, "")?.trim().orEmpty()
        val mitmCaAlias = prefs.getString(PREF_VPN_MITM_CA_ALIAS, "")?.trim().orEmpty()
        val mitmCaGeneratedAtMs = prefs.getLong(PREF_VPN_MITM_CA_GENERATED_AT_MS, 0L)
        val mitmCaInstallRequestedAtMs = prefs.getLong(PREF_VPN_MITM_CA_INSTALL_REQUESTED_AT_MS, 0L)
        val mitmEnabled = prefs.getBoolean(PREF_VPN_MITM_ENABLED, false)

        val localPolicy = localTunnelPolicy(context)
        val providerPackage = resolveProviderPackage(providerMode, policyJson)
        val providerInstalled = providerPackage?.let { isPackageInstalled(context, it) } ?: false
        val vpnPrepareRequired = VpnService.prepare(context) != null
        val vpnActive = isVpnTransportActive(context)

        val connectionState = when {
            tunnelActive || vpnActive -> "connected"
            desiredState.equals("connected", ignoreCase = true) -> "pending_user_action"
            desiredState.equals("disconnected", ignoreCase = true) -> "disconnected"
            else -> "unknown"
        }

        return mapOf(
            "implemented" to true,
            "provider_mode" to providerMode.ifBlank { null },
            "vpn_profile_id" to profileId.ifBlank { null },
            "provider_package" to providerPackage,
            "provider_installed" to providerInstalled,
            "policy_configured" to policyJson.isNotBlank(),
            "desired_state" to desiredState.ifBlank { null },
            "connection_state" to connectionState,
            "vpn_transport_active" to vpnActive,
            "vpn_prepare_required" to vpnPrepareRequired,
            "local_tunnel_enabled" to localPolicy.useLocalTunnel,
            "local_restriction_mode" to localPolicy.restrictionMode,
            "blocked_packages_count" to localPolicy.blockedPackages.size,
            "allowed_packages_count" to localPolicy.allowedPackages.size,
            "capture_ipv6" to localPolicy.captureIpv6,
            "tunnel_active" to tunnelActive,
            "tunnel_started_at_ms" to if (tunnelStartedAtMs > 0L) tunnelStartedAtMs else null,
            "captured_bytes" to captureBytes,
            "captured_packets" to capturePackets,
            "forwarding_supported" to true,
            "forwarding_protocols" to listOf("ipv4_udp", "ipv4_tcp_best_effort"),
            "forwarded_bytes" to forwardedBytes,
            "forwarded_packets" to forwardedPackets,
            "dropped_packets" to droppedPackets,
            "flow_endpoint_counts_json" to endpointFlowJson.ifBlank { "{}" },
            "flow_domain_counts_json" to domainFlowJson.ifBlank { "{}" },
            "flow_package_counts_json" to packageFlowJson.ifBlank { "{}" },
            "blocked_events" to blockedEvents,
            "blocked_domain_counts_json" to blockedDomainsJson.ifBlank { "{}" },
            "blocked_domain_rules_json" to blockedRulesJson.ifBlank { "[]" },
            "mitm_ca_alias" to mitmCaAlias.ifBlank { null },
            "mitm_ca_generated_at_ms" to if (mitmCaGeneratedAtMs > 0L) mitmCaGeneratedAtMs else null,
            "mitm_ca_install_requested_at_ms" to if (mitmCaInstallRequestedAtMs > 0L) mitmCaInstallRequestedAtMs else null,
            "mitm_enabled" to mitmEnabled,
            "last_action" to lastAction.ifBlank { null },
            "last_result" to lastResult.ifBlank { null },
            "last_error" to lastError.ifBlank { null },
            "last_action_at_ms" to if (lastActionAtMs > 0L) lastActionAtMs else null,
            "updated_at_ms" to if (updatedAtMs > 0L) updatedAtMs else null,
                "split_tunnel_enabled" to isSplitTunnelEnabled(context),
                "split_tunnel_packages" to splitTunnelPackages(context).toList(),
        )
    }

        fun isSplitTunnelEnabled(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(PREF_SPLIT_TUNNEL_ENABLED, false)
        }

        fun setSplitTunnelEnabled(context: Context, enabled: Boolean) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putBoolean(PREF_SPLIT_TUNNEL_ENABLED, enabled).apply()
        }

        fun splitTunnelPackages(context: Context): Set<String> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val stored = prefs.getStringSet(PREF_SPLIT_TUNNEL_PACKAGES, emptySet()) ?: emptySet()
            return (stored + ANDROID_AUTO_PACKAGE).filter { it.isNotBlank() }.toSet()
        }

        fun setSplitTunnelPackages(context: Context, packages: Collection<String>) {
            val normalized = (packages + ANDROID_AUTO_PACKAGE)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putStringSet(PREF_SPLIT_TUNNEL_PACKAGES, normalized).apply()
        }

        fun defaultSplitTunnelPackage(): String = ANDROID_AUTO_PACKAGE

    internal fun setTunnelRuntime(context: Context, active: Boolean, error: String? = null) {
        val nowMs = System.currentTimeMillis()
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_VPN_TUNNEL_ACTIVE, active)
            .putLong(PREF_VPN_UPDATED_AT_MS, nowMs)
        if (active) {
            edit.putLong(PREF_VPN_TUNNEL_STARTED_AT_MS, nowMs)
                .putString(PREF_VPN_LAST_RESULT, "active")
                .putString(PREF_VPN_LAST_ERROR, "")
        } else {
            if (!error.isNullOrBlank()) {
                edit.putString(PREF_VPN_LAST_RESULT, "failed")
                    .putString(PREF_VPN_LAST_ERROR, error.trim())
            }
        }
        edit.apply()
    }

    internal fun addCapturedTraffic(context: Context, bytes: Long, packets: Long) {
        if (bytes <= 0L && packets <= 0L) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val nextBytes = (prefs.getLong(PREF_VPN_CAPTURE_BYTES, 0L) + bytes).coerceAtLeast(0L)
        val nextPackets = (prefs.getLong(PREF_VPN_CAPTURE_PACKETS, 0L) + packets).coerceAtLeast(0L)
        prefs.edit()
            .putLong(PREF_VPN_CAPTURE_BYTES, nextBytes)
            .putLong(PREF_VPN_CAPTURE_PACKETS, nextPackets)
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    internal fun addForwardedTraffic(context: Context, bytes: Long, packets: Long) {
        if (bytes <= 0L && packets <= 0L) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val nextBytes = (prefs.getLong(PREF_VPN_FORWARDED_BYTES, 0L) + bytes).coerceAtLeast(0L)
        val nextPackets = (prefs.getLong(PREF_VPN_FORWARDED_PACKETS, 0L) + packets).coerceAtLeast(0L)
        prefs.edit()
            .putLong(PREF_VPN_FORWARDED_BYTES, nextBytes)
            .putLong(PREF_VPN_FORWARDED_PACKETS, nextPackets)
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    internal fun addDroppedPackets(context: Context, packets: Long) {
        if (packets <= 0L) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val nextDropped = (prefs.getLong(PREF_VPN_DROPPED_PACKETS, 0L) + packets).coerceAtLeast(0L)
        prefs.edit()
            .putLong(PREF_VPN_DROPPED_PACKETS, nextDropped)
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    internal fun addFlowSample(
        context: Context,
        endpointKey: String?,
        domain: String?,
        packageName: String?,
    ) {
        val endpoint = endpointKey?.trim().orEmpty()
        val dns = domain?.trim()?.lowercase().orEmpty()
        val pkg = packageName?.trim().orEmpty()
        if (endpoint.isBlank() && dns.isBlank() && pkg.isBlank()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val endpointJson = incrementTopCountJson(
            prefs.getString(PREF_VPN_FLOW_ENDPOINT_COUNTS_JSON, "")?.trim().orEmpty(),
            endpoint,
            maxKeys = 48,
        )
        val domainJson = incrementTopCountJson(
            prefs.getString(PREF_VPN_FLOW_DOMAIN_COUNTS_JSON, "")?.trim().orEmpty(),
            dns,
            maxKeys = 64,
        )
        val packageJson = incrementTopCountJson(
            prefs.getString(PREF_VPN_FLOW_PACKAGE_COUNTS_JSON, "")?.trim().orEmpty(),
            pkg,
            maxKeys = 48,
        )

        prefs.edit()
            .putString(PREF_VPN_FLOW_ENDPOINT_COUNTS_JSON, endpointJson)
            .putString(PREF_VPN_FLOW_DOMAIN_COUNTS_JSON, domainJson)
            .putString(PREF_VPN_FLOW_PACKAGE_COUNTS_JSON, packageJson)
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    internal fun addBlockedDomainEvent(context: Context, domain: String?) {
        val value = domain?.trim()?.lowercase().orEmpty()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val nextTotal = (prefs.getLong(PREF_VPN_BLOCKED_EVENTS, 0L) + 1L).coerceAtLeast(0L)
        val nextDomainJson = incrementTopCountJson(
            prefs.getString(PREF_VPN_BLOCKED_DOMAIN_COUNTS_JSON, "")?.trim().orEmpty(),
            value,
            maxKeys = 64,
        )
        prefs.edit()
            .putLong(PREF_VPN_BLOCKED_EVENTS, nextTotal)
            .putString(PREF_VPN_BLOCKED_DOMAIN_COUNTS_JSON, nextDomainJson)
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    internal fun blockedDomainRules(context: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(PREF_VPN_BLOCKED_DOMAIN_RULES_JSON, "")?.trim().orEmpty()
        if (raw.isBlank()) {
            return listOf(
                "porn",
                "xvideos",
                "xnxx",
                "redtube",
                "youporn",
                "spankbang",
                "chaturbate",
            )
        }
        return runCatching {
            val out = mutableListOf<String>()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim().lowercase()
                if (item.isNotBlank()) out += item
            }
            out.distinct()
        }.getOrDefault(emptyList())
    }

    internal fun recordMitmCaGenerated(context: Context, alias: String, generatedAtMs: Long) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_VPN_MITM_CA_ALIAS, alias.trim())
            .putLong(PREF_VPN_MITM_CA_GENERATED_AT_MS, generatedAtMs.coerceAtLeast(0L))
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    internal fun recordMitmCaInstallRequested(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putLong(PREF_VPN_MITM_CA_INSTALL_REQUESTED_AT_MS, System.currentTimeMillis())
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    internal fun setMitmEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_VPN_MITM_ENABLED, enabled)
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    private fun incrementTopCountJson(currentJson: String, key: String, maxKeys: Int): String {
        if (key.isBlank()) return currentJson.ifBlank { "{}" }

        val map = linkedMapOf<String, Long>()
        runCatching {
            val obj = JSONObject(currentJson.ifBlank { "{}" })
            val names = obj.keys()
            while (names.hasNext()) {
                val next = names.next()
                map[next] = obj.optLong(next, 0L)
            }
        }

        val nextValue = (map[key] ?: 0L) + 1L
        map[key] = nextValue

        if (map.size > maxKeys) {
            val sorted = map.entries
                .sortedByDescending { it.value }
                .take(maxKeys)
            map.clear()
            for (entry in sorted) {
                map[entry.key] = entry.value
            }
        }

        val out = JSONObject()
        for ((k, v) in map) {
            out.put(k, v)
        }
        return out.toString()
    }

    private fun setLastResult(context: Context, result: String, error: String?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_VPN_LAST_RESULT, result)
            .putString(PREF_VPN_LAST_ERROR, error?.trim().orEmpty())
            .putLong(PREF_VPN_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    private fun executeProviderAction(
        context: Context,
        prefs: android.content.SharedPreferences,
        connect: Boolean,
    ): Boolean {
        val providerMode = prefs.getString(PREF_VPN_PROVIDER_MODE, "")?.trim().orEmpty()
        val policyJson = prefs.getString(PREF_VPN_POLICY_JSON, "")?.trim().orEmpty()
        val profileId = prefs.getString(PREF_VPN_PROFILE_ID, "")?.trim().orEmpty()

        val policy = parsePolicy(policyJson)
        val explicitAction = if (connect) {
            policy?.optString("connect_intent_action")?.trim().orEmpty()
        } else {
            policy?.optString("disconnect_intent_action")?.trim().orEmpty()
        }
        val explicitUri = if (connect) {
            policy?.optString("connect_intent_uri")?.trim().orEmpty()
        } else {
            policy?.optString("disconnect_intent_uri")?.trim().orEmpty()
        }

        if (explicitAction.isNotBlank()) {
            val intent = Intent(explicitAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (profileId.isNotBlank()) putExtra("vpn_profile_id", profileId)
                if (providerMode.isNotBlank()) putExtra("provider_mode", providerMode)
            }
            if (explicitUri.isNotBlank()) {
                runCatching { intent.data = android.net.Uri.parse(explicitUri) }
            }
            if (startActivitySafely(context, intent)) return true
        }

        val providerPackage = resolveProviderPackage(providerMode, policyJson)
        if (!providerPackage.isNullOrBlank()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(providerPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (profileId.isNotBlank()) putExtra("vpn_profile_id", profileId)
                if (providerMode.isNotBlank()) putExtra("provider_mode", providerMode)
                putExtra("vpn_connect_requested", connect)
            }
            if (launchIntent != null && startActivitySafely(context, launchIntent)) return true
        }

        return false
    }

    private fun parsePolicy(policyJson: String): JSONObject? {
        if (policyJson.isBlank()) return null
        return runCatching { JSONObject(policyJson) }.getOrNull()
    }

    private fun parseStringArray(policy: JSONObject?, vararg keys: String): List<String> {
        if (policy == null) return emptyList()
        for (key in keys) {
            if (!policy.has(key)) continue
            val raw = policy.opt(key)
            if (raw is JSONArray) {
                val out = mutableListOf<String>()
                for (i in 0 until raw.length()) {
                    val text = raw.optString(i, "").trim()
                    if (text.isNotBlank()) out.add(text)
                }
                return out.distinct()
            }
            if (raw is String) {
                val parsed = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (parsed.isNotEmpty()) return parsed.distinct()
            }
        }
        return emptyList()
    }

    private fun parseBoolean(policy: JSONObject?, snakeKey: String, camelKey: String, defaultValue: Boolean): Boolean {
        if (policy == null) return defaultValue
        if (policy.has(snakeKey)) return policy.optBoolean(snakeKey, defaultValue)
        if (policy.has(camelKey)) return policy.optBoolean(camelKey, defaultValue)
        return defaultValue
    }

    private fun resolveProviderPackage(providerMode: String, policyJson: String): String? {
        val policy = parsePolicy(policyJson)
        val explicit = policy?.optString("vpn_package")
            ?.ifBlank { policy.optString("package") }
            ?.ifBlank { policy.optString("package_name") }
            ?.ifBlank { policy.optString("app_package") }
            ?.trim()
            .orEmpty()
        if (explicit.isNotBlank()) return explicit
        val normalizedMode = providerMode.trim().lowercase()
        if (normalizedMode.isBlank()) return null
        return PROVIDER_PACKAGE_DEFAULTS[normalizedMode]
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isVpnTransportActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun openVpnSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startActivitySafely(context, intent)
    }

    private fun startActivitySafely(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
