package com.hound.controller.util

import kotlin.random.Random

enum class CensorshipType {
    SOLID_COLOR,
    PIXELATE,
    BLUR,
    VHS_GLITCH;

    companion object {
        fun getRandomType(): CensorshipType {
            val values = entries
            return values[Random.nextInt(values.size)]
        }
    }
}
