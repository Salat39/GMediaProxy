package com.geely.online.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OnlineMusicUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        /* if (ACTION_CLEAR == action) {
            AppLogger.log { "Rx: onReceive CLEAR" }
            OnlineMusicState.get().clear(context)
            return
        } */

        if (ACTION_DISABLE == action) {
            OnlineMusicState.bumpExternalUpdateEpoch()
            AppLogger.log { "Rx: onReceive DISABLE" }
            OnlineMusicState.get().freezeDisplayOverride(context)
            return
        }

        if (ACTION_ENABLE == action) {
            OnlineMusicState.bumpExternalUpdateEpoch()
            AppLogger.log { "Rx: onReceive ENABLE" }
            OnlineMusicState.get().clearDisplayOverride(context)
            MediaSessionPipeline.get().refresh()
            return
        }

        if (ACTION_UPDATE != action) return

        val cover = intent.getStringExtra(EXTRA_COVER)
        val requestVersion = OnlineMusicState.bumpExternalUpdateEpoch()
        AppLogger.log { "Rx: onReceive UPDATE titleLen=${intent.getStringExtra(EXTRA_TITLE)?.length ?: 0} artistLen=${intent.getStringExtra(EXTRA_ARTIST)?.length ?: 0} albumLen=${intent.getStringExtra(EXTRA_ALBUM)?.length ?: 0} coverLen=${cover?.length ?: 0} isPlaying=${intent.getBooleanExtra(EXTRA_IS_PLAYING, false)} progressMs=${intent.getLongExtra(EXTRA_PROGRESS_MS, 0L)} durationMs=${intent.getLongExtra(EXTRA_DURATION_MS, 0L)} forceMetadata=${intent.getBooleanExtra(EXTRA_FORCE_METADATA, false)}" }

        val pendingResult = goAsync()
        ExternalCoverPreparer.prepare(context, cover) { preparedCover ->
            try {
                if (!OnlineMusicState.isExternalUpdateEpoch(requestVersion)) return@prepare
                OnlineMusicState.get().updateOverride(
                    context,
                    intent.getStringExtra(EXTRA_TITLE),
                    intent.getStringExtra(EXTRA_ARTIST),
                    intent.getStringExtra(EXTRA_ALBUM),
                    preparedCover,
                    intent.getLongExtra(EXTRA_PROGRESS_MS, 0L),
                    intent.getLongExtra(EXTRA_DURATION_MS, 0L),
                    intent.getBooleanExtra(EXTRA_FORCE_METADATA, false)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_UPDATE: String = "com.salat.gmproxy.MEDIA_UPDATE"
        // private const val ACTION_CLEAR: String = "com.salat.gmproxy.MEDIA_CLEAR"
        private const val ACTION_ENABLE: String = "com.salat.gmproxy.ENABLE"
        private const val ACTION_DISABLE: String = "com.salat.gmproxy.DISABLE"
        private const val EXTRA_TITLE: String = "title"
        private const val EXTRA_ARTIST: String = "artist"
        private const val EXTRA_ALBUM: String = "album"
        private const val EXTRA_COVER: String = "cover"
        private const val EXTRA_IS_PLAYING: String = "isPlaying"
        private const val EXTRA_PROGRESS_MS: String = "progressMs"
        private const val EXTRA_DURATION_MS: String = "durationMs"
        private const val EXTRA_FORCE_METADATA: String = "forceMetadata"
    }
}
