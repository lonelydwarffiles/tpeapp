package com.tpeapp.ritual

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tpeapp.R
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.databinding.ActivityRitualChecklistBinding
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONObject

class RitualChecklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRitualChecklistBinding
    private lateinit var steps: MutableList<RitualStep>
    private val completedIds = mutableSetOf<String>()
    private var pendingPhotoStepId: String? = null

    private val photoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            pendingPhotoStepId?.let { id ->
                completedIds.add(id)
                adapter.notifyDataSetChanged()
                updateDoneButton()
            }
        }
        pendingPhotoStepId = null
    }

    private lateinit var adapter: StepAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRitualChecklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        steps = RitualRepository.getSteps(this).toMutableList()

        adapter = StepAdapter(
            steps, completedIds,
            onChecked = { step, checked ->
                if (!step.requiresPhoto) {
                    if (checked) completedIds.add(step.id) else completedIds.remove(step.id)
                    updateDoneButton()
                }
            },
            onPhotoClick = { step ->
                pendingPhotoStepId = step.id
                val uri = Uri.parse("content://com.tpeapp.ritual/photo/${step.id}")
                photoLauncher.launch(uri)
            }
        )

        binding.rvRitualSteps.layoutManager = LinearLayoutManager(this)
        binding.rvRitualSteps.adapter = adapter

        binding.btnAllDone.isEnabled = false
        binding.btnAllDone.setOnClickListener { onAllDone() }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        Toast.makeText(this, getString(R.string.ritual_back_blocked), Toast.LENGTH_SHORT).show()
    }

    private fun updateDoneButton() {
        binding.btnAllDone.isEnabled = steps.all { it.id in completedIds }
    }

    private fun onAllDone() {
        ConsequenceDispatcher.reward(this, "ritual_complete")
        dispatchWebhook()
        finish()
    }

    private fun dispatchWebhook() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        WebhookManager.dispatchEvent(url, token, JSONObject().apply {
            put("event", "ritual_complete")
            put("timestamp", System.currentTimeMillis())
        })
    }

    private inner class StepAdapter(
        private val steps: List<RitualStep>,
        private val completedIds: Set<String>,
        private val onChecked: (RitualStep, Boolean) -> Unit,
        private val onPhotoClick: (RitualStep) -> Unit
    ) : RecyclerView.Adapter<StepAdapter.StepViewHolder>() {

        inner class StepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.cbStepCheck)
            val title: TextView = view.findViewById(R.id.tvStepTitle)
            val description: TextView = view.findViewById(R.id.tvStepDescription)
            val photoBtn: MaterialButton = view.findViewById(R.id.btnCapturePhoto)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ritual_step, parent, false)
            return StepViewHolder(view)
        }

        override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
            val step = steps[position]
            holder.title.text = step.title
            holder.description.text = step.description
            holder.checkbox.isChecked = step.id in completedIds

            if (step.requiresPhoto) {
                holder.checkbox.visibility = View.GONE
                holder.photoBtn.visibility = View.VISIBLE
                if (step.id in completedIds) {
                    holder.photoBtn.text = "Photo Taken"
                    holder.photoBtn.isEnabled = false
                } else {
                    holder.photoBtn.text = getString(R.string.ritual_photo_btn)
                    holder.photoBtn.isEnabled = true
                    holder.photoBtn.setOnClickListener { onPhotoClick(step) }
                }
            } else {
                holder.checkbox.visibility = View.VISIBLE
                holder.photoBtn.visibility = View.GONE
                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.isChecked = step.id in completedIds
                holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    onChecked(step, isChecked)
                }
            }
        }

        override fun getItemCount() = steps.size
    }
}
