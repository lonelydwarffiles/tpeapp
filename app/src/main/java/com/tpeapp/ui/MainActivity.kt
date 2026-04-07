package com.tpeapp.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tpeapp.R
import com.tpeapp.databinding.ActivityMainBinding
import com.tpeapp.mdm.AppDeviceAdminReceiver
import com.tpeapp.mdm.PartnerPinManager
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.review.ReviewActivity
import com.tpeapp.service.FilterService

/**
 * MainActivity — the sole transparent UI entry-point.
 *
 * Responsibilities:
 *  1. Start (and keep alive) [FilterService].
 *  2. Prompt the user to activate Device Admin if not yet granted.
 *  3. Display current filter status and FCM token for partner setup.
 *  4. Guard the "Deactivate" button behind the partner PIN.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var pinManager: PartnerPinManager

    private val enableAdminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshAdminStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirect to PairingActivity on first launch (before the device has
        // been paired with an Accountability Partner).
        if (!androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getBoolean(PairingActivity.PREF_IS_PAIRED, false)
        ) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }

        binding        = ActivityMainBinding.inflate(layoutInflater)
        dpm            = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, AppDeviceAdminReceiver::class.java)
        pinManager     = PartnerPinManager(this)

        setContentView(binding.root)

        startFilterService()
        refreshAdminStatus()
        bindButtons()
    }

    // ------------------------------------------------------------------
    //  FilterService
    // ------------------------------------------------------------------

    private fun startFilterService() {
        val intent = Intent(this, FilterService::class.java)
        startForegroundService(intent)
    }

    // ------------------------------------------------------------------
    //  Device Admin
    // ------------------------------------------------------------------

    private fun refreshAdminStatus() {
        val active = dpm.isAdminActive(adminComponent)
        binding.tvAdminStatus.text = if (active)
            getString(R.string.admin_status_active)
        else
            getString(R.string.admin_status_inactive)

        binding.btnActivateAdmin.isEnabled   = !active
        binding.btnDeactivateAdmin.isEnabled = active
    }

    private fun bindButtons() {
        binding.btnActivateAdmin.setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_explanation)
                )
            }
            enableAdminLauncher.launch(intent)
        }

        binding.btnDeactivateAdmin.setOnClickListener {
            showPinDialog { pin ->
                if (pinManager.verifyPin(pin)) {
                    dpm.removeActiveAdmin(adminComponent)
                    refreshAdminStatus()
                } else {
                    binding.tvAdminStatus.text = getString(R.string.pin_incorrect)
                }
            }
        }

        binding.btnStartReview.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }
    }

    // ------------------------------------------------------------------
    //  PIN dialog
    // ------------------------------------------------------------------

    /**
     * Shows a simple PIN-entry dialog.  Replace with a custom secure
     * dialog in production to prevent screenshots / autocomplete.
     */
    private fun showPinDialog(onPin: (String) -> Unit) {
        val editText = android.widget.EditText(this).apply {
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter partner PIN"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Partner PIN Required")
            .setMessage("Enter the PIN set by your accountability partner.")
            .setView(editText)
            .setPositiveButton("OK")   { _, _ -> onPin(editText.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
