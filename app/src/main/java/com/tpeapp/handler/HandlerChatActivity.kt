package com.tpeapp.handler

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tpeapp.R
import com.tpeapp.databinding.ActivityHandlerChatBinding
import com.tpeapp.mqtt.PartnerMqttService
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
import com.tpeapp.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * HandlerChatActivity — the main launcher screen.
 *
 * Shows a chat interface with "Handler", the AI companion powered by an
 * OpenAI-compatible API.  If the device is not yet paired it redirects to
 * [PairingActivity] first.
 *
 * The overflow menu provides a shortcut to [MainActivity] (admin / settings)
 * behind the partner PIN.
 */
class HandlerChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandlerChatBinding
    private lateinit var adapter: ChatAdapter
    private val threadCanReply: MutableMap<String, Boolean> = mutableMapOf()
    private var activeThreadId: String = ChatRepository.DEFAULT_THREAD_ID
    private var applyingComplianceTransform = false
    private var cachedDictJson = ""
    private var cachedPolicyJson = ""
    private var cachedRules: List<Pair<Regex, String>> = emptyList()
    private var cachedPolicy = ReplacementPolicy()

    private val proxySmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            intent ?: return
            val eventType = intent.getStringExtra(PartnerMqttService.EXTRA_PROXY_SMS_EVENT_TYPE)
                ?: return
            when (eventType) {
                PartnerMqttService.EVENT_PROXY_SMS_INCOMING -> {
                    val threadId = intent.getStringExtra(PartnerMqttService.EXTRA_PROXY_SMS_THREAD_ID)
                        ?: ChatRepository.DEFAULT_THREAD_ID
                    val body = intent.getStringExtra(PartnerMqttService.EXTRA_PROXY_SMS_BODY).orEmpty()
                    val imageUrl = intent.getStringExtra(PartnerMqttService.EXTRA_PROXY_SMS_IMAGE_URL)
                    if (intent.hasExtra(PartnerMqttService.EXTRA_PROXY_SMS_CAN_REPLY)) {
                        threadCanReply[threadId] =
                            intent.getBooleanExtra(PartnerMqttService.EXTRA_PROXY_SMS_CAN_REPLY, false)
                    }
                    activeThreadId = threadId
                    val incoming = ChatRepository.newIncomingProxySmsMessage(
                        threadId = threadId,
                        text = body,
                        imageUrl = imageUrl,
                    )
                    val updated = ChatRepository.addMessage(this@HandlerChatActivity, incoming)
                    adapter.submitList(updated.toList())
                    scrollToBottom()
                    updateReplyAccessUi()
                }
                PartnerMqttService.EVENT_PROXY_SMS_CAN_REPLY_UPDATED -> {
                    val threadId = intent.getStringExtra(PartnerMqttService.EXTRA_PROXY_SMS_THREAD_ID)
                        ?: ChatRepository.DEFAULT_THREAD_ID
                    if (!intent.hasExtra(PartnerMqttService.EXTRA_PROXY_SMS_CAN_REPLY)) return
                    threadCanReply[threadId] =
                        intent.getBooleanExtra(PartnerMqttService.EXTRA_PROXY_SMS_CAN_REPLY, false)
                    if (threadId == activeThreadId) {
                        updateReplyAccessUi()
                    }
                }
            }
        }
    }

    private data class ReplacementPolicy(
        val defaultMode: String = "auto",
        val packageModes: Map<String, String> = emptyMap(),
        val prefixModes: Map<String, String> = emptyMap(),
    ) {
        fun allows(packageName: String): Boolean {
            val pkg = packageName.trim().lowercase()
            val effectiveMode = packageModes[pkg]
                ?: prefixModes.entries
                    .sortedByDescending { it.key.length }
                    .firstOrNull { pkg.startsWith(it.key) }
                    ?.value
                ?: defaultMode
            return effectiveMode !in setOf("off", "disabled", "none")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirect to PairingActivity if not yet paired.
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(PairingActivity.PREF_IS_PAIRED, false)
        ) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }

        binding = ActivityHandlerChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.handler_name)

        // Start FilterService (keeps monitoring alive while the chat screen is open)
        startForegroundService(Intent(this, FilterService::class.java))

        setupRecyclerView()
        loadHistory()
        bindComplianceHook()
        bindSendButton()
        registerReceiver(
            proxySmsReceiver,
            IntentFilter(PartnerMqttService.ACTION_PROXY_SMS_EVENT),
        )
        updateReplyAccessUi()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(proxySmsReceiver) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    //  Options menu
    // ------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_handler_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, MainActivity::class.java))
                true
            }
            R.id.action_clear_chat -> {
                ChatRepository.clearHistory(this)
                threadCanReply.clear()
                activeThreadId = ChatRepository.DEFAULT_THREAD_ID
                adapter.submitList(emptyList())
                updateReplyAccessUi()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------
    //  Setup
    // ------------------------------------------------------------------

    private fun setupRecyclerView() {
        adapter = ChatAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter
    }

    private fun loadHistory() {
        val history = ChatRepository.getHistory(this)
        history.forEach { msg ->
            if (!threadCanReply.containsKey(msg.threadId)) {
                threadCanReply[msg.threadId] = true
            }
        }
        activeThreadId = history.lastOrNull()?.threadId ?: ChatRepository.DEFAULT_THREAD_ID
        adapter.submitList(history)
        scrollToBottom()
    }

    private fun bindComplianceHook() {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (applyingComplianceTransform || !canReplyForActiveThread()) return
                val original = s?.toString().orEmpty()
                if (original.isEmpty()) return
                val transformed = applyTextReplacementPolicy(original)
                if (transformed == original) return

                applyingComplianceTransform = true
                val cursor = binding.etMessage.selectionStart.coerceAtLeast(0)
                binding.etMessage.setText(transformed)
                binding.etMessage.setSelection(cursor.coerceAtMost(transformed.length))
                applyingComplianceTransform = false
            }
        })
    }

    private fun bindSendButton() {
        val send = {
            if (!canReplyForActiveThread()) {
                updateReplyAccessUi()
            } else {
                val text = applyTextReplacementPolicy(
                    binding.etMessage.text?.toString()?.trim().orEmpty()
                )
                if (text.isNotEmpty()) {
                    binding.etMessage.setText("")
                    sendMessage(text)
                }
            }
        }
        binding.btnSend.setOnClickListener { send() }
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            send(); true
        }
    }

    // ------------------------------------------------------------------
    //  Send / receive
    // ------------------------------------------------------------------

    private fun sendMessage(text: String) {
        if (!canReplyForActiveThread()) return
        val userMsg = ChatRepository.newUserMessage(text, threadId = activeThreadId)
        val history = ChatRepository.addMessage(this, userMsg)
        adapter.submitList(history.toList())
        scrollToBottom()

        setInputEnabled(false)
        showTypingIndicator(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { ChatRepository.sendMessage(this@HandlerChatActivity, text) }

            withContext(Dispatchers.Main) {
                showTypingIndicator(false)
                setInputEnabled(canReplyForActiveThread())

                result.onSuccess { reply ->
                    val assistantMsg = ChatRepository.newAssistantMessage(
                        text = reply,
                        threadId = activeThreadId,
                    )
                    val updated = ChatRepository.addMessage(this@HandlerChatActivity, assistantMsg)
                    adapter.submitList(updated.toList())
                    scrollToBottom()
                }.onFailure { e ->
                    Toast.makeText(
                        this@HandlerChatActivity,
                        getString(R.string.handler_error, e.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.rvMessages.scrollToPosition(count - 1)
    }

    private fun setInputEnabled(enabled: Boolean) {
        binding.etMessage.isEnabled = enabled
        binding.btnSend.isEnabled   = enabled
    }

    private fun canReplyForActiveThread(): Boolean {
        return threadCanReply[activeThreadId] ?: true
    }

    private fun updateReplyAccessUi() {
        val canReply = canReplyForActiveThread()
        binding.inputBar.visibility = if (canReply) View.VISIBLE else View.GONE
        binding.tvPermissionRequired.visibility = if (canReply) View.GONE else View.VISIBLE
        setInputEnabled(canReply)
    }

    private fun applyTextReplacementPolicy(input: String): String {
        if (input.isBlank()) return input
        refreshComplianceCache()
        if (!cachedPolicy.allows(packageName)) return input

        var result = input
        cachedRules.forEach { (pattern, replacement) ->
            result = runCatching { result.replace(pattern, replacement) }.getOrDefault(result)
        }
        return result
    }

    private fun refreshComplianceCache() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val dictJson = prefs.getString(FilterService.PREF_TEXT_REPLACEMENT_DICT, "") ?: ""
        val policyJson = prefs.getString(FilterService.PREF_TEXT_REPLACEMENT_POLICY, "") ?: ""

        if (dictJson != cachedDictJson) {
            cachedDictJson = dictJson
            cachedRules = parseReplacementRules(dictJson)
        }
        if (policyJson != cachedPolicyJson) {
            cachedPolicyJson = policyJson
            cachedPolicy = parseReplacementPolicy(policyJson)
        }
    }

    private fun parseReplacementRules(json: String): List<Pair<Regex, String>> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val obj = JSONObject(json)
            val entries = mutableListOf<Pair<Regex, String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pattern = keys.next()
                val replacement = obj.optString(pattern)
                val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
                entries.add(regex to replacement)
            }
            entries.toList()
        }.getOrDefault(emptyList())
    }

    private fun parseReplacementPolicy(json: String): ReplacementPolicy {
        if (json.isBlank()) return ReplacementPolicy()
        return runCatching {
            val obj = JSONObject(json)
            val packageModes = mutableMapOf<String, String>()
            obj.optJSONObject("packages")?.let { packages ->
                val keys = packages.keys()
                while (keys.hasNext()) {
                    val key = keys.next().trim().lowercase()
                    if (key.isBlank()) continue
                    packageModes[key] = packages.optString(key, "auto").trim().lowercase()
                }
            }

            val prefixModes = mutableMapOf<String, String>()
            obj.optJSONObject("package_prefixes")?.let { prefixes ->
                val keys = prefixes.keys()
                while (keys.hasNext()) {
                    val key = keys.next().trim().lowercase()
                    if (key.isBlank()) continue
                    prefixModes[key] = prefixes.optString(key, "auto").trim().lowercase()
                }
            }

            ReplacementPolicy(
                defaultMode = obj.optString("default_mode", "auto").trim().lowercase(),
                packageModes = packageModes,
                prefixModes = prefixModes,
            )
        }.getOrDefault(ReplacementPolicy())
    }

    private fun showTypingIndicator(visible: Boolean) {
        binding.tvTypingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    //  RecyclerView adapter
    // ------------------------------------------------------------------

    private class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ViewHolder>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
                override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) = old.id == new.id
                override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) = old == new
            }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvContent: TextView = itemView.findViewById(R.id.tvMessageContent)
            val tvTimestamp: TextView = itemView.findViewById(R.id.tvMessageTimestamp)
            val ivImage: ImageView = itemView.findViewById(R.id.ivMessageImage)
        }

        override fun getItemViewType(position: Int): Int =
            if (getItem(position).isUser) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = if (viewType == 0)
                R.layout.item_chat_message_user
            else
                R.layout.item_chat_message_handler
            val view = android.view.LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = getItem(position)
            holder.tvContent.text = msg.content
            holder.tvContent.visibility = if (msg.content.isBlank()) View.GONE else View.VISIBLE
            if (msg.imageUrl.isNullOrBlank()) {
                holder.ivImage.visibility = View.GONE
                Glide.with(holder.ivImage).clear(holder.ivImage)
            } else {
                holder.ivImage.visibility = View.VISIBLE
                Glide.with(holder.ivImage)
                    .load(msg.imageUrl)
                    .centerCrop()
                    .into(holder.ivImage)
            }
            holder.tvTimestamp.text = android.text.format.DateFormat
                .getTimeFormat(holder.itemView.context)
                .format(java.util.Date(msg.timestamp))
        }
    }
}
