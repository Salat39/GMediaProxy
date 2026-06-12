package com.geely.online.service

import android.util.Log

object MediaTransmissionLog {
    private const val TAG = "GMediaProxyLog"

    private var lastSnapshotKey: String? = null

    fun logSnapshotIfChanged(
        title: String,
        artist: String,
        album: String,
        hasCover: Boolean,
        isPlayed: Boolean,
        isFavor: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val key = "$isPlayed|$isFavor|$title|$artist|$album|$hasCover"
        if (key == lastSnapshotKey) return
        lastSnapshotKey = key
        Log.d(
            TAG,
            "TARGET: isPlayed=${if (isPlayed) "▶\uFE0F" else "\uD83D\uDED1"} " +
                    "isFavor=${if (isFavor) "✅" else "❌"} " +
                    "hasCover=${if (hasCover) "✅" else "❌"} " +
                    "title=${truncate(title)} " +
                    "artist=${truncate(artist)} " +
                    "album=${truncate(album)}"
        )
    }

    private fun truncate(text: String, maxLen: Int = 48): String {
        return if (text.length <= maxLen) text else text.take(maxLen) + "…"
    }
}
