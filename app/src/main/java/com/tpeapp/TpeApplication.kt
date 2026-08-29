package com.hound.controller

import android.app.Application
import android.util.Log
import com.hound.controller.service.CoreServiceKeeper
import com.hound.controller.vpn.TpeVpnPolicyManager
import com.hound.controller.webhook.WebhookManager

class TpeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WebhookManager.init(this)
        CoreServiceKeeper.scheduleWatchdog(this)
        CoreServiceKeeper.ensureCoreServicesRunning(this, "app_on_create")
        TpeVpnPolicyManager.ensureDefaultEnabled(this, "app_on_create")
        Log.i("TpeApplication", "Accountability app started")
    }
}
