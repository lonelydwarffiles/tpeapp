package com.hound.controller.ritual

data class RitualStep(
    val id: String,
    val title: String,
    val description: String,
    val requiresPhoto: Boolean
)
