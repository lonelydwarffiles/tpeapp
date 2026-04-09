package com.tpeapp.handler

/**
 * A single message in the conversation with "Handler", the AI companion.
 *
 * @param role      "user" or "assistant" — matches the OpenAI Chat Completions role field.
 * @param content   The message body.
 * @param timestamp Unix epoch ms when the message was created.
 */
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long
) {
    val isUser: Boolean get() = role == "user"
}
