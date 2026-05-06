package com.tpeapp.vault

import android.annotation.SuppressLint
import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.tpeapp.R

/**
 * VaultAutofillService — Android [AutofillService] that fills managed credentials
 * from [PasswordVaultManager] into third-party apps.
 *
 * ### How it works
 *  1. Android calls [onFillRequest] when the user focuses a password (or username)
 *     field in any app.
 *  2. We walk the [AssistStructure] to find username/email and password fields.
 *  3. We load all (non-locked) vault entries and return one [Dataset] per matching
 *     entry — ranked by site-name substring match against the requesting package /
 *     web domain.
 *  4. Tapping a suggestion fills username **and** password simultaneously.
 *
 * ### Activation
 * The user must navigate to **Settings → Passwords & accounts → Autofill service**
 * and select "TpeApp" (or the equivalent path for their launcher).  The Settings
 * screen provides a deep-link button that opens that system page directly.
 *
 * ### Save
 * We intentionally do NOT implement save-on-submit because the partner controls
 * what credentials are stored.  [onSaveRequest] is a no-op.
 */
class VaultAutofillService : AutofillService() {

    companion object {
        private const val TAG = "VaultAutofillService"
        private const val MASKED_PASSWORD_DISPLAY = "●●●●●●●●"
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        // Identify username + password autofill nodes.
        val fields = mutableListOf<AutofillField>()
        parseStructure(structure, fields)

        val usernameField = fields.firstOrNull { it.type == FieldType.USERNAME }
        val passwordField = fields.firstOrNull { it.type == FieldType.PASSWORD }

        if (usernameField == null && passwordField == null) {
            // No fillable fields found.
            callback.onSuccess(null)
            return
        }

        // Derive a hint for matching: package name + any web domain hints.
        val pkg       = structure.activityComponent.packageName ?: ""
        val webDomain = fields.mapNotNull { it.webDomain }.firstOrNull() ?: ""

        val vault   = PasswordVaultManager(applicationContext)
        val entries = vault.getEntries().filter {
            // Exclude time-locked entries — the sub can't use them anyway.
            !vault.isLocked(it.getString("id"))
        }

        if (entries.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        // Score each entry by how well its site name matches the requesting context.
        fun score(site: String): Int {
            val s = site.lowercase()
            return when {
                webDomain.isNotBlank() && (webDomain.contains(s) || s.contains(webDomain)) -> 3
                pkg.contains(s) || s.contains(pkg.substringAfterLast('.')) -> 2
                else -> 1
            }
        }

        val sorted = entries.sortedByDescending { score(it.optString("site")) }

        val responseBuilder = FillResponse.Builder()
        var added = 0

        for (entry in sorted) {
            val id       = entry.getString("id")
            val site     = entry.optString("site")
            val username = entry.optString("username")

            // Reveal the password from encrypted storage.
            val password = vault.revealPassword(applicationContext, id) ?: continue

            val datasetBuilder = Dataset.Builder()

            if (usernameField != null) {
                val view = remoteView("$username ($site)")
                datasetBuilder.setValue(usernameField.id, AutofillValue.forText(username), view)
            }
            if (passwordField != null) {
                val view = if (usernameField == null) remoteView("$username ($site)") else remoteView(MASKED_PASSWORD_DISPLAY)
                datasetBuilder.setValue(passwordField.id, AutofillValue.forText(password), view)
            }

            responseBuilder.addDataset(datasetBuilder.build())
            added++
        }

        if (added == 0) {
            callback.onSuccess(null)
            return
        }

        callback.onSuccess(responseBuilder.build())
        Log.d(TAG, "onFillRequest: offered $added dataset(s) for pkg=$pkg")
    }

    /** We do not implement partner-controlled saves via autofill. */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    @SuppressLint("InflateParams")
    private fun remoteView(text: String): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.autofill_text, text)
        }

    /**
     * Walks the [AssistStructure] recursively and appends any username / password
     * [AutofillId]s to [out].
     */
    private fun parseStructure(structure: AssistStructure, out: MutableList<AutofillField>) {
        for (i in 0 until structure.windowNodeCount) {
            parseViewNode(structure.getWindowNodeAt(i).rootViewNode, out)
        }
    }

    private fun parseViewNode(node: AssistStructure.ViewNode, out: MutableList<AutofillField>) {
        val autofillId   = node.autofillId ?: return run {
            for (i in 0 until node.childCount) parseViewNode(node.getChildAt(i), out)
        }
        val hints        = node.autofillHints
        val inputType    = node.inputType
        val webDomain    = node.webDomain

        val type = when {
            hints != null && hints.any { it.contains("password", ignoreCase = true) }          -> FieldType.PASSWORD
            hints != null && hints.any {
                it.contains("username", ignoreCase = true) ||
                it.contains("email", ignoreCase = true) ||
                it == View.AUTOFILL_HINT_USERNAME ||
                it == View.AUTOFILL_HINT_EMAIL_ADDRESS
            } -> FieldType.USERNAME
            // Fall back to inputType heuristics.
            inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0             -> FieldType.PASSWORD
            inputType and android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0     -> FieldType.PASSWORD
            inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != 0         -> FieldType.PASSWORD
            inputType and android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != 0        -> FieldType.USERNAME
            else -> null
        }

        if (type != null) {
            out.add(AutofillField(autofillId, type, webDomain))
        }

        for (i in 0 until node.childCount) parseViewNode(node.getChildAt(i), out)
    }

    private enum class FieldType { USERNAME, PASSWORD }

    private data class AutofillField(
        val id:        AutofillId,
        val type:      FieldType,
        val webDomain: String?,
    )
}
