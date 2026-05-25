package com.tpeapp.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.tpeapp.filter.IFilterService
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module entry-point.
 *
 * On each package load this class:
 *  1. Checks if the package should be filtered (all packages by default).
 *  2. Installs the relevant hooks ([ImageViewHook], [GlideHook], [CoilHook],
 *     [OkHttpHook]).
 *  3. Lazily binds to [com.tpeapp.service.FilterService] the first time a hook
 *     needs to submit an image.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "TPE_MainHook"

        // Package names to exclude (the filter app itself + system UI)
        private val EXCLUDED_PACKAGES = setOf(
            "com.tpeapp",
            "android",
            "com.android.systemui"
        )

        /** Shared, lazily-initialised reference to the bound FilterService. */
        @Volatile var filterService: IFilterService? = null
            private set

        private var serviceContext: Context? = null

        /**
         * Returns the application [Context] of the currently hooked process.
         * Obtained from the first [ensureServiceBound] call or, as a fallback,
         * via reflection on [android.app.ActivityThread].  Returns `null` if
         * no context has been captured yet and reflection fails.
         */
        fun getContext(): Context? {
            serviceContext?.let { return it }
            return try {
                val atClass = Class.forName("android.app.ActivityThread")
                val method  = atClass.getMethod("currentApplication")
                (method.invoke(null) as? android.app.Application)?.applicationContext
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Lazily bind to FilterService from within the hooked process.
         * Safe to call from any thread; the service binding is asynchronous.
         */
        fun ensureServiceBound(context: Context) {
            if (filterService != null) return
            synchronized(this) {
                if (filterService != null) return
                serviceContext = context.applicationContext
                val intent = Intent("com.tpeapp.BIND_FILTER_SERVICE")
                    .setPackage("com.tpeapp")
                try {
                    val bound = context.applicationContext.bindService(
                        intent,
                        connection,
                        Context.BIND_AUTO_CREATE
                    )
                    if (!bound) {
                        Log.e(TAG, "bindService returned false for package=${context.packageName}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "bindService failed for package=${context.packageName}", t)
                }
            }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                filterService = IFilterService.Stub.asInterface(service)
                Log.i(TAG, "FilterService connected in ${serviceContext?.packageName}")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                filterService = null
                Log.w(TAG, "FilterService disconnected — will rebind on next hook call")
                // Re-bind so transient disconnects heal automatically.
                serviceContext?.let { ctx ->
                    ensureServiceBound(ctx)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  IXposedHookLoadPackage
    // ------------------------------------------------------------------

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg in EXCLUDED_PACKAGES) return

        Log.d(TAG, "Hooking package: $pkg")

        val loader = lpparam.classLoader

        ImageViewHook.install(loader)
        GlideHook.install(loader)
        CoilHook.install(loader)
        OkHttpHook.install(loader)
        TextViewHook.install(loader)
        InputConnectionHook.install(loader)
    }
}
