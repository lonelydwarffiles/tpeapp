package com.tpeapp

import android.app.Application
import android.util.Log

class TpeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("TpeApplication", "Accountability app started")
    }
}
