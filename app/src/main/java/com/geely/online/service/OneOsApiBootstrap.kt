package com.geely.online.service

import android.content.Context
import com.geely.lib.oneosapi.OneOSApiManager
import com.geely.lib.oneosapi.listener.ServiceConnectionListener

object OneOsApiBootstrap {
    private var listenerRegistered: Boolean = false

    private val connectionListener: ServiceConnectionListener =
        object : ServiceConnectionListener {
            override fun onServiceConnectionChanged(connected: Boolean) {
                AppLogger.log { "OneOS: onServiceConnectionChanged connected=$connected" }
            }

            override fun onServiceBinderUpdated(code: Int) {
                if (code == 3) {
                    AppLogger.log { "OneOS: onServiceBinderUpdated MediaCenter (code=3)" }
                }
            }
        }

    fun initialize(applicationContext: Context) {
        val ctx = applicationContext.applicationContext

        val api = OneOSApiManager.getInstance(ctx)
        if (!listenerRegistered) {
            api.registerServiceConnectionListener(connectionListener)
            listenerRegistered = true
        }
        runCatching { api.init() }.onFailure { e ->
            AppLogger.log { "OneOS: init() threw ${e.message}" }
        }
        AppLogger.log { "OneOS: init() invoked" }
    }
}
