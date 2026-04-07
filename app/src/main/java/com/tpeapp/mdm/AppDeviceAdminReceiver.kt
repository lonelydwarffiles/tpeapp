package com.tpeapp.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DeviceAdminReceiver for the Accountability app.
 *
 * Registering as a Device Administrator allows the app to call
 * [android.app.admin.DevicePolicyManager.setUninstallBlocked] and manage
 * password / lock policies if the user (or MDM partner) enables them.
 *
 * **Activation flow**:
 * 1. The user (or setup wizard) launches the Device Admin activation intent.
 * 2. The Accountability Partner sets a PIN that must be entered before the
 *    user can deactivate the admin (mirroring parental-control behaviour).
 *
 * **Partner PIN guard** is implemented in [com.tpeapp.mdm.PartnerPinManager].
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AppDeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device Admin enabled")
        // Block uninstallation immediately once admin rights are granted.
        blockUninstall(context, true)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // This message is shown in the system "Deactivate admin" dialog.
        return "Your accountability partner must enter the PIN before this " +
               "app can be uninstalled. Contact your partner to proceed."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device Admin disabled — content filter no longer enforced")
        blockUninstall(context, false)
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun blockUninstall(context: Context, block: Boolean) {
        runCatching {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            val admin = android.content.ComponentName(context, AppDeviceAdminReceiver::class.java)
            dpm.setUninstallBlocked(admin, context.packageName, block)
        }.onFailure { e ->
            // setUninstallBlocked requires MANAGE_DEVICE_ADMINS or profile-owner.
            // Log but don't crash — the manifest <uses-permission> still prevents
            // casual uninstall via the launcher.
            Log.w(TAG, "setUninstallBlocked($block) failed (may need profile-owner)", e)
        }
    }
}
