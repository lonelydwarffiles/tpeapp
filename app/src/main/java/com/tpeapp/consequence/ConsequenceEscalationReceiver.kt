package com.tpeapp.consequence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the escalation alarm and calls [ConsequenceEscalationHelper.punishAtLevel]
 * with the level stored in the intent extras.
 */
class ConsequenceEscalationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ConsequenceEscalationRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConsequenceEscalationHelper.ACTION_ESCALATE) return
        val reason = intent.getStringExtra(ConsequenceEscalationHelper.EXTRA_REASON) ?: "escalation"
        val level  = intent.getIntExtra(ConsequenceEscalationHelper.EXTRA_LEVEL, 1)
        Log.i(TAG, "Escalation alarm fired: reason=$reason level=$level")
        ConsequenceEscalationHelper.punishAtLevel(context, reason, level)
    }
}
