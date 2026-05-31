package com.tpeapp.vpn

import android.app.Activity
import android.net.VpnService
import android.os.Bundle

/**
 * Foreground proxy activity for VPN consent flow.
 *
 * Using an activity result path makes the system consent UI more reliable
 * on devices that suppress plain startActivity handoffs from mixed contexts.
 */
class VpnConsentProxyActivity : Activity() {

    companion object {
        private const val REQUEST_VPN_PREPARE = 44072
    }

    private var launchedConsent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchConsentIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (launchedConsent && VpnService.prepare(this) == null) {
            TrafficCaptureVpnService.requestConnect(this)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PREPARE) return
        if (resultCode == RESULT_OK && VpnService.prepare(this) == null) {
            TrafficCaptureVpnService.requestConnect(this)
        }
        finish()
    }

    private fun launchConsentIfNeeded() {
        if (launchedConsent) return
        launchedConsent = true

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            TrafficCaptureVpnService.requestConnect(this)
            finish()
            return
        }

        runCatching {
            startActivityForResult(prepareIntent, REQUEST_VPN_PREPARE)
        }.onFailure {
            finish()
        }
    }
}
