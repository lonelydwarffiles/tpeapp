package com.tpeapp.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
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
 * LocationTrackingService — a foreground service that periodically samples
 * the device's GPS coordinates and reports them to the Camera-Site backend at
 * [LOCATION_ENDPOINT_PATH] (`/api/handler/device-status`).
 *
 * The service uses the platform [LocationManager] (same provider already used
 * by [com.tpeapp.gating.GeofenceManager]) so no new runtime permissions are
 * required beyond those already declared in the manifest.
 *
 * Start / stop the service via explicit [Intent]:
 *   - `startService(Intent(context, LocationTrackingService::class.java))`
 *   - `stopService(Intent(context, LocationTrackingService::class.java))`
 *
 * The backend URL is read from [PairingActivity.PREF_PARTNER_ENDPOINT] and the
 * bearer token from [FilterService.PREF_WEBHOOK_BEARER_TOKEN] — the same
 * credentials used by the webhook system throughout the app.
 */
class LocationTrackingService : Service() {

    companion object {
        private const val TAG            = "LocationTrackingService"
        private const val CHANNEL_ID     = "tpe_location_tracking"
        private const val NOTIFICATION_ID = 9001

        /** Minimum time between location samples delivered to the listener (ms). */
        private const val MIN_TIME_MS    = 5 * 60 * 1_000L   // 5 minutes

        /** Minimum displacement (metres) between successive samples. */
        private const val MIN_DISTANCE_M = 50f

        /** Path on the FastAPI backend that receives device status (battery, GPS, AI). */
        const val LOCATION_ENDPOINT_PATH = "/api/handler/device-status"
    }

    private var locationManager: LocationManager? = null

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "Location update: lat=${location.latitude} lon=${location.longitude}")
            reportLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    // ------------------------------------------------------------------
    //  Service lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        registerLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        locationManager?.removeUpdates(locationListener)
        locationManager = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    //  Location registration
    // ------------------------------------------------------------------

    private fun registerLocationUpdates() {
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: run {
            Log.w(TAG, "LocationManager unavailable — stopping service")
            stopSelf()
            return
        }
        locationManager = lm

        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)     -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                Log.w(TAG, "No location provider available — stopping service")
                stopSelf()
                return
            }
        }

        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, locationListener)
            Log.i(TAG, "Registered for location updates via $provider")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    //  Backend reporting
    // ------------------------------------------------------------------

    private fun reportLocation(location: Location) {
        val prefs      = PreferenceManager.getDefaultSharedPreferences(this)
        val endpoint   = prefs.getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?.trimEnd('/')
            ?: run {
                Log.d(TAG, "No backend URL configured — skipping location report")
                return
            }
        val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        val deviceId = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "No device_id configured — skipping location report")
                return
            }

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("lat",       location.latitude)
            put("lon",       location.longitude)
        }

        val body    = payload.toString().toRequestBody(JSON_TYPE)
        val builder = Request.Builder()
            .url("$endpoint$LOCATION_ENDPOINT_PATH")
            .post(body)
        if (!bearerToken.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $bearerToken")
        }

        httpClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Location report failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) Log.d(TAG, "Location reported (HTTP ${it.code})")
                    else Log.w(TAG, "Location report rejected (HTTP ${it.code})")
                }
            }
        })
    }

    // ------------------------------------------------------------------
    //  Foreground notification
    // ------------------------------------------------------------------

    private fun buildForegroundNotification() = run {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Location Tracking",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Ongoing notification while periodic location tracking is active."
                }
            )
        }

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Location Tracking Active")
            .setContentText("GPS coordinates are being reported to your accountability partner.")
            .setOngoing(true)
            .build()
    }
}
