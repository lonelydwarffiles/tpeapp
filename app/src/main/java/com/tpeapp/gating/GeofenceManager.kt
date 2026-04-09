package com.tpeapp.gating

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject

object GeofenceManager {

    private const val TAG = "GeofenceManager"
    private const val PREF_GEOFENCES = "geofences_json"
    private const val PREF_ENABLED = "geofence_monitoring_enabled"
    private const val MIN_TIME_MS = 30_000L
    private const val MIN_DISTANCE_M = 20f

    private val insideIds = mutableSetOf<String>()

    @Volatile private var locationManager: LocationManager? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = checkGeofences(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun isEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun getGeofences(ctx: Context): List<GeofenceEntry> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_GEOFENCES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                GeofenceEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    radiusMeters = o.getDouble("radius_meters").toFloat()
                )
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to parse geofences", e); emptyList() }
    }

    fun setGeofences(ctx: Context, list: List<GeofenceEntry>) {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id); put("name", g.name)
                put("latitude", g.latitude); put("longitude", g.longitude)
                put("radius_meters", g.radiusMeters)
            })
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_GEOFENCES, arr.toString()).apply()
    }

    @Synchronized
    fun startMonitoring(ctx: Context) {
        if (locationManager != null) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        locationManager = lm
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, locationListener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, locationListener)
            }
            Log.i(TAG, "Geofence monitoring started")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted", e)
        }
    }

    @Synchronized
    fun stopMonitoring(ctx: Context) {
        locationManager?.removeUpdates(locationListener)
        locationManager = null
        Log.i(TAG, "Geofence monitoring stopped")
    }

    private fun checkGeofences(location: Location) {
        val appCtx = storedCtx ?: return
        val geofences = getGeofences(appCtx)
        for (gf in geofences) {
            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, gf.latitude, gf.longitude, results)
            val inside = results[0] <= gf.radiusMeters
            val wasInside = gf.id in insideIds
            when {
                inside && !wasInside -> {
                    insideIds.add(gf.id)
                    dispatchWebhook(appCtx, "geofence_enter", gf)
                }
                !inside && wasInside -> {
                    insideIds.remove(gf.id)
                    dispatchWebhook(appCtx, "geofence_exit", gf)
                }
            }
        }
    }

    @Volatile var storedCtx: Context? = null

    @Synchronized
    fun startMonitoring(ctx: Context, store: Boolean) {
        storedCtx = ctx.applicationContext
        startMonitoring(ctx)
    }

    private fun dispatchWebhook(ctx: Context, event: String, gf: GeofenceEntry) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        WebhookManager.dispatchEvent(url, token, JSONObject().apply {
            put("event", event); put("geofence_id", gf.id); put("geofence_name", gf.name)
            put("timestamp", System.currentTimeMillis())
        })
    }
}

data class GeofenceEntry(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float
)
