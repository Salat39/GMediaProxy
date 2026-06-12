package com.geely.online.service

import android.service.notification.NotificationListenerService

class MediaSessionNotificationListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.log { "NLS: onCreate" }
        MediaSessionPipeline.get().start(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.log { "NLS: onListenerConnected" }
        MediaSessionPipeline.get().start(this)
        MediaSessionPipeline.get().refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLogger.log { "NLS: onListenerDisconnected" }
        MediaSessionPipeline.get().releaseSessionsListener()
    }
}
