package com.tpeapp.handler

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tpeapp.R
import com.tpeapp.databinding.ActivityHandlerChatBinding
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
import com.tpeapp.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.preference.PreferenceManager

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
        bindSendButton()
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
                adapter.submitList(emptyList())
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
        adapter.submitList(history)
        scrollToBottom()
    }

    private fun bindSendButton() {
        val send = {
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                binding.etMessage.setText("")
                sendMessage(text)
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
        val userMsg = ChatRepository.newUserMessage(text)
        val history = ChatRepository.addMessage(this, userMsg)
        adapter.submitList(history.toList())
        scrollToBottom()

        setInputEnabled(false)
        showTypingIndicator(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { ChatRepository.sendMessage(this@HandlerChatActivity, text) }

            withContext(Dispatchers.Main) {
                showTypingIndicator(false)
                setInputEnabled(true)

                result.onSuccess { reply ->
                    val assistantMsg = ChatRepository.newAssistantMessage(reply)
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
            holder.tvTimestamp.text = android.text.format.DateFormat
                .getTimeFormat(holder.itemView.context)
                .format(java.util.Date(msg.timestamp))
        }
    }
}
