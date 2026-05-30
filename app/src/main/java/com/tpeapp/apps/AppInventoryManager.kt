package com.tpeapp.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AppInventoryManager — manages the device's installed-app inventory on behalf
 * of the partner dashboard.
 *
 * Responsibilities:
 *  1. **Inventory sync** — on startup, dispatch the full list of user-installed apps
 *     to the webhook so the dashboard can build a searchable list.
 *  2. **Delta events** — dispatch `app_installed` / `app_uninstalled` events as apps
 *     come and go, triggered by [PackageChangeReceiver].
 *  3. **Name resolution** — map a human-readable app name (sent in an FCM command)
 *     to its package name via [PackageManager].
 *  4. **App control** — execute partner-commanded app operations (open, force-stop,
 *     disable, enable, clear-cache, uninstall).  Privileged operations run via a
 *     root shell (`su -c`), which is available on all supported devices.
 *
 * Webhook events emitted:
 * ```
 * { "event": "app_inventory",   "apps": [{"package_name":"…","app_name":"…"},…], "timestamp": … }
 * { "event": "app_installed",   "package_name": "…", "app_name": "…",            "timestamp": … }
 * { "event": "app_uninstalled", "package_name": "…", "app_name": "…",            "timestamp": … }
 * ```
 */
object AppInventoryManager {

    private const val TAG = "AppInventoryManager"

    /** SharedPreferences key for the JSON label-cache `{packageName: appName}`. */
    private const val PREF_APP_LABEL_CACHE = "app_label_cache_json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class AppInventoryEntry(
        val packageName: String,
        val appLabel: String,
        val isSystem: Boolean,
        val isEnabled: Boolean,
        val isSuspended: Boolean,
        val versionName: String?,
        val versionCode: String?,
        val firstInstallTimeMs: Long?,
        val lastUpdateTimeMs: Long?,
        val category: String?,
    )

    // ------------------------------------------------------------------
    //  App list / name resolution
    // ------------------------------------------------------------------

    /**
     * Returns all user-installed apps (i.e., excluding system apps) as a list of
     * `(packageName, displayLabel)` pairs, sorted alphabetically by label.
     */
    fun getInstalledUserApps(context: Context): List<Pair<String, String>> {
        return getInstalledApps(context, includeSystem = false)
            .map { it.packageName to it.appLabel }
    }

    fun getInstalledApps(context: Context, includeSystem: Boolean): List<AppInventoryEntry> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) }
            .map { info ->
                val pkgInfo = runCatching {
                    pm.getPackageInfo(info.packageName, 0)
                }.getOrNull()

                val appLabel = runCatching {
                    pm.getApplicationLabel(info).toString()
                }.getOrDefault(info.packageName)

                val category = runCatching {
                    val categoryValue = info.category
                    if (categoryValue >= 0) "category_$categoryValue" else null
                }.getOrNull()

                AppInventoryEntry(
                    packageName = info.packageName,
                    appLabel = appLabel,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isEnabled = info.enabled,
                    isSuspended = (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
                    versionName = pkgInfo?.versionName,
                    versionCode = pkgInfo?.longVersionCode?.toString(),
                    firstInstallTimeMs = pkgInfo?.firstInstallTime,
                    lastUpdateTimeMs = pkgInfo?.lastUpdateTime,
                    category = category,
                )
            }
            .sortedBy { it.appLabel.lowercase() }
            .toList()
    }

    /**
     * Resolves a human-readable [appName] to a package name by scanning installed
     * applications.  Matching is case-insensitive; an exact label match is preferred
     * over a substring (contains) fallback.
     *
     * @return the package name, or `null` if no installed app matches.
     */
    fun resolvePackageName(context: Context, appName: String): String? {
        val needle = appName.trim()
        if (needle.isBlank()) return null

        // Allow callers to pass package ids directly.
        runCatching {
            context.packageManager.getPackageInfo(needle, 0)
        }.getOrNull()?.let { return needle }

        val apps = getInstalledUserApps(context)
        apps.firstOrNull { (_, label) -> label.equals(needle, ignoreCase = true) }
            ?.let { return it.first }

        apps.firstOrNull { (pkg, _) -> pkg.equals(needle, ignoreCase = true) }
            ?.let { return it.first }

        val normalizedNeedle = needle.lowercase().replace(" ", "")
        apps.firstOrNull { (_, label) ->
            label.lowercase().replace(" ", "") == normalizedNeedle
        }?.let { return it.first }

        return apps.firstOrNull { (_, label) ->
            label.contains(needle, ignoreCase = true)
        }?.first ?: apps.firstOrNull { (pkg, _) ->
            pkg.contains(needle, ignoreCase = true)
        }?.first
    }

    // ------------------------------------------------------------------
    //  Label cache
    // ------------------------------------------------------------------

    /**
     * Loads the persisted label cache.  The cache is used to include the human-readable
     * app name in `app_uninstalled` events, where the package is no longer queryable.
     */
    private fun loadLabelCache(context: Context): MutableMap<String, String> {
        val json = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_APP_LABEL_CACHE, null) ?: return mutableMapOf()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWithTo(mutableMapOf()) { obj.getString(it) }
        }.getOrDefault(mutableMapOf())
    }

    private fun saveLabelCache(context: Context, cache: Map<String, String>) {
        val obj = JSONObject()
        cache.forEach { (pkg, label) -> obj.put(pkg, label) }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_APP_LABEL_CACHE, obj.toString()).apply()
    }

    // ------------------------------------------------------------------
    //  Webhook dispatching
    // ------------------------------------------------------------------

    /**
     * Sends the full user-app inventory to the webhook and refreshes the label cache.
     * Runs asynchronously on the IO dispatcher; callers are never blocked.
     */
    fun syncFullInventory(context: Context) {
        scope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val webhookUrl = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
                ?.takeIf { it.isNotBlank() } ?: return@launch
            val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
                ?.takeIf { it.isNotBlank() }

            val apps = getInstalledUserApps(context)
            saveLabelCache(context, apps.toMap())

            val appsArray = JSONArray()
            apps.forEach { (pkg, label) ->
                appsArray.put(JSONObject().apply {
                    put("package_name", pkg)
                    put("app_name", label)
                })
            }

            val payload = JSONObject().apply {
                put("event", "app_inventory")
                put("apps", appsArray)
                put("timestamp", System.currentTimeMillis())
            }

            WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
            Log.i(TAG, "App inventory synced — ${apps.size} user apps dispatched")
        }
    }

    private fun resolveDeviceAppsUploadUrl(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val webhookUrl = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val uri = runCatching { Uri.parse(webhookUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val authority = uri.authority ?: return null
        val path = uri.path.orEmpty()

        val uploadPath = "/api/handler/device-apps/upload"
        if (path.endsWith("/api/tpe/webhook")) {
            val prefix = path.removeSuffix("/api/tpe/webhook")
            return "$scheme://$authority$prefix$uploadPath"
        }
        return "$scheme://$authority$uploadPath"
    }

    fun uploadInventorySnapshot(
        context: Context,
        pollId: String?,
        includeSystem: Boolean,
        fullSnapshot: Boolean,
        source: String = "mqtt_poll",
    ) {
        scope.launch {
            val uploadUrl = resolveDeviceAppsUploadUrl(context)
            if (uploadUrl.isNullOrBlank()) {
                Log.w(TAG, "Skipping app inventory upload: upload URL could not be resolved")
                return@launch
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
                ?.takeIf { it.isNotBlank() }

            val apps = getInstalledApps(context, includeSystem = includeSystem)
            val appsArray = JSONArray()
            apps.forEach { app ->
                appsArray.put(
                    JSONObject().apply {
                        put("package_name", app.packageName)
                        put("app_label", app.appLabel)
                        put("is_system", app.isSystem)
                        put("is_enabled", app.isEnabled)
                        put("is_suspended", app.isSuspended)
                        if (!app.versionName.isNullOrBlank()) put("version_name", app.versionName)
                        if (!app.versionCode.isNullOrBlank()) put("version_code", app.versionCode)
                        app.firstInstallTimeMs?.let { put("first_install_time_ms", it) }
                        app.lastUpdateTimeMs?.let { put("last_update_time_ms", it) }
                        if (!app.category.isNullOrBlank()) put("category", app.category)
                    }
                )
            }

            val payload = JSONObject().apply {
                if (!pollId.isNullOrBlank()) put("poll_id", pollId)
                put("source", source)
                put("full_snapshot", fullSnapshot)
                put("apps", appsArray)
                put("timestamp", System.currentTimeMillis())
            }

            WebhookManager.dispatchEvent(uploadUrl, bearerToken, payload)
            Log.i(
                TAG,
                "App inventory upload queued — apps=${apps.size} includeSystem=$includeSystem fullSnapshot=$fullSnapshot pollId=${pollId ?: ""}",
            )
        }
    }

    /**
     * Sends an `app_installed` delta event to the webhook and updates the label cache.
     * Called by [PackageChangeReceiver] when a new package is installed or updated.
     */
    fun dispatchInstallEvent(context: Context, packageName: String) {
        scope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val webhookUrl = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
                ?.takeIf { it.isNotBlank() } ?: return@launch
            val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
                ?.takeIf { it.isNotBlank() }

            val appName = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            }.getOrDefault(packageName)

            val cache = loadLabelCache(context)
            cache[packageName] = appName
            saveLabelCache(context, cache)

            val payload = JSONObject().apply {
                put("event", "app_installed")
                put("package_name", packageName)
                put("app_name", appName)
                put("timestamp", System.currentTimeMillis())
            }

            WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
            Log.i(TAG, "app_installed dispatched: $packageName ($appName)")
        }
    }

    /**
     * Sends an `app_uninstalled` delta event to the webhook using the label cache to
     * supply the human-readable name (package info is unavailable after removal).
     * Called by [PackageChangeReceiver].
     */
    fun dispatchUninstallEvent(context: Context, packageName: String) {
        scope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val webhookUrl = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
                ?.takeIf { it.isNotBlank() } ?: return@launch
            val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
                ?.takeIf { it.isNotBlank() }

            val cache = loadLabelCache(context)
            val appName = cache.remove(packageName) ?: packageName
            saveLabelCache(context, cache)

            val payload = JSONObject().apply {
                put("event", "app_uninstalled")
                put("package_name", packageName)
                put("app_name", appName)
                put("timestamp", System.currentTimeMillis())
            }

            WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
            Log.i(TAG, "app_uninstalled dispatched: $packageName ($appName)")
        }
    }

    // ------------------------------------------------------------------
    //  App control
    // ------------------------------------------------------------------

    /**
     * Brings the app to the foreground using the system launch intent.
     * Does not require root.
     */
    fun openApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: run { Log.w(TAG, "No launch intent found for $packageName"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "Opened app: $packageName")
    }

    /** Force-stops the app via `am force-stop`. Requires root. */
    fun forceStopApp(packageName: String) = execRoot("am force-stop $packageName")

    /** Disables the app for the current user via `pm disable-user`. Requires root. */
    fun disableApp(packageName: String) = execRoot("pm disable-user $packageName")

    /** Re-enables a previously disabled app via `pm enable`. Requires root. */
    fun enableApp(packageName: String) = execRoot("pm enable $packageName")

    /** Deletes the app's cache directory. Requires root. */
    fun clearAppCache(packageName: String) =
        execRoot("rm -rf /data/data/$packageName/cache")

    /**
     * Uninstalls the app for the current user via `pm uninstall --user 0`.
     * Requires root.  User data is removed; use `--keep-data` variant on the
     * server side if retention is preferred.
     */
    fun uninstallApp(packageName: String) =
        execRoot("pm uninstall --user 0 $packageName")

    // ------------------------------------------------------------------
    //  Root shell helper
    // ------------------------------------------------------------------

    private fun execRoot(command: String) {
        scope.launch {
            runCatching {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                Log.i(TAG, "Root command executed: [$command] exit=${process.exitValue()}")
            }.onFailure { e ->
                Log.e(TAG, "Root command failed: [$command]", e)
            }
        }
    }
}
