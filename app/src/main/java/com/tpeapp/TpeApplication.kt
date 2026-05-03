package com.tpeapp

import android.app.Application
import android.util.Log
import com.tpeapp.webhook.WebhookManager

class TpeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WebhookManager.init(this)
        Log.i("TpeApplication", "Accountability app started")
    }
}
