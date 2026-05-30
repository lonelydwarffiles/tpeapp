package com.tpeapp

import android.app.Application
import android.util.Log
import com.tpeapp.service.CoreServiceKeeper
import com.tpeapp.webhook.WebhookManager

class TpeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WebhookManager.init(this)
        CoreServiceKeeper.scheduleWatchdog(this)
        CoreServiceKeeper.ensureCoreServicesRunning(this, "app_on_create")
        Log.i("TpeApplication", "Accountability app started")
    }
}
