package com.geely.online.service

enum class LikeMode {
    VENDOR_RATING,
    CURRENT_PLAYER,
    SEND_INTENT
}

val Int.toLikeMode
    get() = when (this) {
        0 -> LikeMode.VENDOR_RATING
        1 -> LikeMode.CURRENT_PLAYER
        else -> LikeMode.SEND_INTENT
    }


val LikeMode.toIntMode
    get() = when (this) {
        LikeMode.VENDOR_RATING -> 0
        LikeMode.CURRENT_PLAYER -> 1
        LikeMode.SEND_INTENT -> 2
    }
