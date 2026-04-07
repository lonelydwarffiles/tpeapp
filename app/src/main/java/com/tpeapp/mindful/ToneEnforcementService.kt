package com.tpeapp.mindful

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import org.json.JSONArray

/**
 * ToneEnforcementService — an [AccessibilityService] that monitors text-field
 * changes and instantly clears (or replaces with a safe phrase) any text that
 * contains a word from the "restricted vocabulary" managed by the Accountability
 * Partner via FCM.
 *
 * The restricted vocabulary is stored as a JSON array string in [SharedPreferences]
 * under the key [PREF_RESTRICTED_VOCABULARY].  An empty or absent key means no
 * outgoing text is filtered.
 *
 * Memory efficiency: the vocabulary list is loaded from prefs on each relevant
 * event and compared with lightweight [String.contains] checks.  The list is
 * expected to be short (tens of words at most), so no trie or regex is needed.
 */
class ToneEnforcementService : AccessibilityService() {

    companion object {
        private const val TAG = "ToneEnforcementService"

        /** SharedPreferences key for the JSON-encoded restricted-vocabulary list. */
        const val PREF_RESTRICTED_VOCABULARY = "mindful_restricted_vocabulary"

        /** The safe replacement phrase substituted when a restricted word is detected. */
        private const val SAFE_PHRASE = "[Redacted]"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        val node = event.source ?: return
        try {
            val currentText = node.text?.toString() ?: return
            if (currentText.isBlank()) return

            val restricted = loadRestrictedVocabulary()
            if (restricted.isEmpty()) return

            for (word in restricted) {
                if (word.isNotBlank() && currentText.contains(word, ignoreCase = true)) {
                    Log.i(TAG, "Restricted word \"$word\" detected — replacing text")
                    replaceText(node, SAFE_PHRASE)
                    return
                }
            }
        } finally {
            node.recycle()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "ToneEnforcementService interrupted")
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Replaces the text inside [node] with [replacement] using
     * [AccessibilityNodeInfo.ACTION_SET_TEXT].
     */
    private fun replaceText(node: AccessibilityNodeInfo, replacement: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Loads the restricted vocabulary from [SharedPreferences].  Returns a list of
     * lower-case strings, or an empty list on any parse error.
     */
    private fun loadRestrictedVocabulary(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val json  = prefs.getString(PREF_RESTRICTED_VOCABULARY, null)
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i).lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse restricted vocabulary JSON", e)
            emptyList()
        }
    }
}
