package com.tpeapp.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PackageChangeReceiver — listens for system broadcasts that indicate an app has been
 * installed, updated, or removed, and forwards each event to [AppInventoryManager] so
 * the partner dashboard's app list stays current.
 *
 * Registered statically in `AndroidManifest.xml` with a `<data android:scheme="package"/>`
 * element.  `ACTION_PACKAGE_ADDED` and `ACTION_PACKAGE_REMOVED` are on Android's
 * implicit-broadcast exempt list and may therefore be received by manifest-declared
 * receivers even on API 26+.
 *
 * Events handled:
 *  - [Intent.ACTION_PACKAGE_ADDED]    → dispatches `app_installed` webhook event.
 *  - [Intent.ACTION_PACKAGE_REPLACED] → dispatches `app_installed` webhook event (label
 *                                        refresh after an in-place update).
 *  - [Intent.ACTION_PACKAGE_REMOVED]  → dispatches `app_uninstalled` webhook event.
 *                                        Skipped when `EXTRA_REPLACING` is true because
 *                                        `ACTION_PACKAGE_REPLACED` will fire immediately
 *                                        after and handles that case.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED ->
                AppInventoryManager.dispatchInstallEvent(context, packageName)

            Intent.ACTION_PACKAGE_REMOVED -> {
                // If this removal is part of an in-place update, ACTION_PACKAGE_REPLACED
                // fires right after — skip here to avoid a spurious uninstall event.
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (!replacing) {
                    AppInventoryManager.dispatchUninstallEvent(context, packageName)
                }
            }
        }
    }
}
