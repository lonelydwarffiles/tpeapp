package com.tpeapp.pairing

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.tpeapp.databinding.ActivityPairingBinding
import com.tpeapp.fcm.PartnerFcmService
import com.tpeapp.ui.MainActivity
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PairingActivity — runs on first launch to pair the device with an
 * Accountability Partner's backend.
 *
 * Flow:
 *  1. If the device is already paired the Activity finishes immediately and
 *     forwards to [MainActivity].
 *  2. Otherwise it opens the camera, scans the partner's QR code, retrieves
 *     the FCM registration token, and POSTs both to the partner's backend via
 *     [OkHttpClient].
 *  3. On a successful pairing response the "is_paired" flag is written to
 *     [SharedPreferences] so this screen is never shown again.
 */
class PairingActivity : AppCompatActivity() {

    // ------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------

    companion object {
        private const val TAG                    = "PairingActivity"
        const val PREF_IS_PAIRED                 = "is_paired"
        const val PREF_PARTNER_ENDPOINT          = "partner_endpoint_url"
        const val PREF_PARTNER_SESSION_ID        = "partner_session_id"
        const val PREF_PARTNER_SIGNALING_URL     = "partner_signaling_url"

        private val JSON_TYPE = "application/json".toMediaType()
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private lateinit var binding: ActivityPairingBinding
    private lateinit var cameraExecutor: ExecutorService

    /** Prevents multiple concurrent pairing attempts from one scan burst. */
    private val pairingInProgress = AtomicBoolean(false)

    // ------------------------------------------------------------------
    //  Permission launcher
    // ------------------------------------------------------------------

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showStatus("⚠️ Camera permission is required to scan the QR code.")
            }
        }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already paired — skip directly to MainActivity.
        if (prefs().getBoolean(PREF_IS_PAIRED, false)) {
            navigateToMain()
            return
        }

        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }

    // ------------------------------------------------------------------
    //  Camera setup
    // ------------------------------------------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, QrAnalyzer())
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                showStatus("⚠️ Could not start camera. Please restart the app.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------
    //  QR Analyser
    // ------------------------------------------------------------------

    private inner class QrAnalyzer : ImageAnalysis.Analyzer {

        private val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(proxy: ImageProxy) {
            if (pairingInProgress.get()) {
                proxy.close()
                return
            }

            val mediaImage = proxy.image
            if (mediaImage == null) {
                proxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    barcodes
                        .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                        ?.rawValue
                        ?.let { raw ->
                            if (pairingInProgress.compareAndSet(false, true)) {
                                handleQrPayload(raw)
                            }
                        }
                }
                .addOnFailureListener { e -> Log.w(TAG, "Barcode processing failed", e) }
                .addOnCompleteListener { proxy.close() }
        }
    }

    // ------------------------------------------------------------------
    //  QR payload → FCM token → pairing request
    // ------------------------------------------------------------------

    /**
     * Parses the QR code JSON payload produced by the partner dashboard.
     *
     * Expected format:
     * ```json
     * { "endpoint": "https://partner.example.com", "pairing_token": "abc123",
     *   "webhook_secret": "optional-bearer-token" }
     * ```
     */
    private fun handleQrPayload(raw: String) {
        showStatus("QR code detected — retrieving FCM token…")

        val endpoint: String
        val pairingToken: String
        val webhookSecret: String

        try {
            val json     = JSONObject(raw)
            endpoint     = json.getString("endpoint").trimEnd('/')
            pairingToken = json.getString("pairing_token")
            webhookSecret = json.optString("webhook_secret", "")
        } catch (e: JSONException) {
            Log.e(TAG, "Invalid QR payload", e)
            showStatus("⚠️ Invalid QR code. Ask your accountability partner for a new one.")
            pairingInProgress.set(false)
            return
        }

        // Enforce HTTPS to prevent sending the FCM token over plain HTTP.
        if (!endpoint.startsWith("https://")) {
            Log.w(TAG, "Rejecting non-HTTPS endpoint: $endpoint")
            showStatus("⚠️ Partner endpoint must use HTTPS. Contact your accountability partner.")
            pairingInProgress.set(false)
            return
        }

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken ->
                sendPairingRequest(endpoint, pairingToken, fcmToken, webhookSecret)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "FCM token retrieval failed", e)
                showStatus("⚠️ Could not retrieve device token. Check Google Play Services.")
                pairingInProgress.set(false)
            }
    }

    /**
     * POSTs the device's FCM token to the partner's `/api/pair` endpoint to
     * complete the pairing process.
     */
    private fun sendPairingRequest(
        endpoint: String,
        pairingToken: String,
        fcmToken: String,
        webhookSecret: String
    ) {
        showStatus("Pairing with accountability partner…")

        val body = JSONObject().run {
            put("fcm_token",     fcmToken)
            put("pairing_token", pairingToken)
            toString()
        }.toRequestBody(JSON_TYPE)

        val request = Request.Builder()
            .url("$endpoint/api/pair")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Pairing network request failed", e)
                showStatus("⚠️ Could not reach partner server. Check connectivity and try again.")
                pairingInProgress.set(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        // Persist paired state so this screen is never shown again.
                        // Also auto-wire the webhook URL so ConsequenceDispatcher can
                        // report events to the partner backend without manual config.
                        val editor = prefs().edit()
                            .putBoolean(PREF_IS_PAIRED, true)
                            .putString(PREF_PARTNER_ENDPOINT, endpoint)
                            .putString(PartnerFcmService.PREF_FCM_TOKEN, fcmToken)
                            .putString(
                                com.tpeapp.service.FilterService.PREF_WEBHOOK_URL,
                                "$endpoint/api/tpe/webhook"
                            )
                        if (webhookSecret.isNotBlank()) {
                            editor.putString(
                                com.tpeapp.service.FilterService.PREF_WEBHOOK_BEARER_TOKEN,
                                webhookSecret
                            )
                        }
                        editor.apply()
                        runOnUiThread {
                            Toast.makeText(
                                this@PairingActivity,
                                "Paired successfully!",
                                Toast.LENGTH_SHORT
                            ).show()
                            navigateToMain()
                        }
                    } else {
                        Log.w(TAG, "Pairing rejected: HTTP ${it.code}")
                        showStatus("⚠️ Pairing rejected (${it.code}). Contact your accountability partner.")
                        pairingInProgress.set(false)
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showStatus(message: String) {
        runOnUiThread { binding.tvStatus.text = message }
    }

    private fun prefs(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
}
