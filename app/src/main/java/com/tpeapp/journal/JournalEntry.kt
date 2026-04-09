package com.tpeapp.journal

data class JournalEntry(
    val id: String,
    val timestamp: Long,
    val mood: Int,
    val violations: String,
    val gratitude: String,
    val notes: String
)

data class InfractionEntry(
    val id: String,
    val timestamp: Long,
    val description: String,
    val category: String
)
