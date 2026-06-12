package com.geely.online.service

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.geely.lib.oneosapi.OneOSApiManager
import com.geely.lib.oneosapi.mediacenter.MediaCenterManager
import com.geely.lib.oneosapi.mediacenter.constant.MediaCenterConstant
import com.geely.lib.oneosapi.mediacenter.listener.SourceStateListener

class OnlineAudioSourceGate {
    private val lock = Any()
    private var appContext: Context? = null
    private var manager: MediaCenterManager? = null
    private var listener: SourceStateListener? = null
    private var currentSource: MediaCenterConstant.AudioSource? = null
    private var handler: Handler? = null
    private var onOnlineSourceChanged: (() -> Unit)? = null
    private var onOnlineSourceExited: (() -> Unit)? = null
    private var sourceListenerBound = false
    private var lastLoggedAvailability: SourceAvailability? = null
    private var lastLoggedSource: MediaCenterConstant.AudioSource? = null
    private var lastNullManagerLogMs: Long = 0L
    private var pendingEnsureRetry: Boolean = false
    private var lastOnlineRequestMs: Long = 0L
    private var lastOnlineAppRequestMs: Long = 0L
    private var lastOnlineExitMs: Long = 0L

    fun start(
        context: Context?,
        handler: Handler,
        onOnlineSourceChanged: () -> Unit,
        onOnlineSourceExited: () -> Unit
    ) {
        if (context == null) {
            AppLogger.log { "Gate: start skipped context=null" }
            return
        }
        synchronized(lock) {
            appContext = context.applicationContext
            this.handler = handler
            this.onOnlineSourceChanged = onOnlineSourceChanged
            this.onOnlineSourceExited = onOnlineSourceExited
            ensureManagerLocked()
        }
        AppLogger.log { "Gate: start" }
    }

    fun release() {
        synchronized(lock) {
            runCatching {
                listener?.let { sourceListener ->
                    if (sourceListenerBound) {
                        manager?.removeSourceStateListener(sourceListener)
                    }
                }
            }
            appContext = null
            manager = null
            listener = null
            currentSource = null
            handler = null
            onOnlineSourceChanged = null
            onOnlineSourceExited = null
            sourceListenerBound = false
            lastLoggedAvailability = null
            lastLoggedSource = null
            lastNullManagerLogMs = 0L
            pendingEnsureRetry = false
            lastOnlineRequestMs = 0L
            lastOnlineAppRequestMs = 0L
            lastOnlineExitMs = 0L
        }
        AppLogger.log { "Gate: release" }
    }

    fun canPublishOnlineMedia(): Boolean {
        return synchronized(lock) {
            if (BuildConfig.DEBUG) return true
            val availability = readAvailabilityLocked()
            maybeLogGateState(availability, currentSource)
            availability == SourceAvailability.ONLINE
        }
    }

    fun isNativeAudioSourcePackage(packageName: String?) =
        packageName in AuxiliaryPackages.NATIVE_AUDIO_SESSION_PACKAGES

    fun requestOnlineAudioSourceIfNeeded(triggerPackageName: String? = null): Boolean {
        if (isNativeAudioSourcePackage(triggerPackageName)) {
            AppLogger.log { "Gate: skip ONLINE request for native session pkg=$triggerPackageName" }
            return false
        }
        val request = synchronized(lock) {
            val activeManager = ensureManagerLocked() ?: return false
            readCurrentSourceLocked(activeManager)
            val oldSource = currentSource
            val appSource = resolveStoredDisplayKey(OnlineMusicState.SOURCE).second
            val now = SystemClock.elapsedRealtime()
            if (oldSource == ONLINE_SOURCE) {
                val currentAppSource = readCurrentAppSourceLocked(activeManager) ?: return false
                if (currentAppSource == appSource) {
                    return false
                }
                if (now - lastOnlineAppRequestMs < ONLINE_REQUEST_MIN_INTERVAL_MS) {
                    return false
                }
                lastOnlineAppRequestMs = now
                OnlineSourceRequest(activeManager, oldSource, appSource, false)
            } else {
                if (now - lastOnlineExitMs < ONLINE_RESTORE_AFTER_EXIT_BLOCK_MS) {
                    AppLogger.log { "Gate: skip ONLINE request after ONLINE exit pkg=$triggerPackageName source=$oldSource" }
                    return false
                }
                if (now - lastOnlineRequestMs < ONLINE_REQUEST_MIN_INTERVAL_MS) {
                    return false
                }
                lastOnlineRequestMs = now
                OnlineSourceRequest(activeManager, oldSource, appSource, true)
            }
        }
        if (request.pauseBeforeSwitch) {
            runCatching {
                pauseOldSourceBeforeSwitch(request.manager, request.oldSource)
            }.onFailure { e ->
                AppLogger.log { "Gate: pause old source before ONLINE switch failed ${e.message}" }
            }
        }
        return runCatching {
            request.manager.requestAudioSource(
                ONLINE_SOURCE,
                request.appSource
            )
            AppLogger.log { "Gate: requestAudioSource ONLINE for playing media session pkg=$triggerPackageName" }
            true
        }.onFailure { e ->
            AppLogger.log { "Gate: requestAudioSource ONLINE failed ${e.message}" }
        }.getOrDefault(false)
    }

    private fun readAvailabilityLocked(): SourceAvailability {
        val activeManager = ensureManagerLocked() ?: return SourceAvailability.UNKNOWN
        readCurrentSourceLocked(activeManager)
        val source = currentSource ?: return SourceAvailability.UNKNOWN
        return if (source == ONLINE_SOURCE) SourceAvailability.ONLINE else SourceAvailability.OFFLINE
    }

    private fun maybeLogGateState(
        availability: SourceAvailability,
        source: MediaCenterConstant.AudioSource?
    ) {
        if (availability != lastLoggedAvailability || source != lastLoggedSource) {
            lastLoggedAvailability = availability
            lastLoggedSource = source
            AppLogger.log { "Gate: state availability=$availability source=$source" }
        }
    }

    private fun scheduleEnsureRetryLocked() {
        val h = handler ?: return
        if (pendingEnsureRetry) return
        pendingEnsureRetry = true
        h.postDelayed({
            synchronized(lock) {
                pendingEnsureRetry = false
                ensureManagerLocked()
            }
        }, MANAGER_RETRY_DELAY_MS)
    }

    private fun maybeLogNullManager() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNullManagerLogMs < NULL_MANAGER_LOG_INTERVAL_MS) return
        lastNullManagerLogMs = now
        AppLogger.log { "Gate: ensureManager OneOS mediaCenterManager null" }
    }

    private fun ensureManagerLocked(): MediaCenterManager? {
        manager?.takeIf { it.isAlive }?.let { activeManager ->
            readCurrentSourceLocked(activeManager)
            pendingEnsureRetry = false
            return activeManager
        }
        val context = appContext ?: run {
            AppLogger.log { "Gate: ensureManager skip appContext=null" }
            return null
        }
        val rawManager = runCatching {
            OneOSApiManager.getInstance(context).mediaCenterManager
        }.getOrNull()
        if (rawManager == null) {
            maybeLogNullManager()
            scheduleEnsureRetryLocked()
            return null
        }
        if (!rawManager.isAlive) {
            AppLogger.log { "Gate: ensureManager MediaCenterManager not alive" }
            scheduleEnsureRetryLocked()
            return null
        }
        pendingEnsureRetry = false
        val previousManager = manager
        val newManager = rawManager
        val created = previousManager == null || previousManager !== newManager
        if (created) {
            runCatching {
                if (sourceListenerBound) {
                    listener?.let { previousManager?.removeSourceStateListener(it) }
                }
            }
            sourceListenerBound = false
            manager = newManager
            AppLogger.log { "Gate: ensureManager using MediaCenterManager instance" }
        }
        if (listener == null) {
            listener = SourceStateListener { source, _ ->
                val becameOnline = synchronized(lock) {
                    val wasOnline = currentSource == ONLINE_SOURCE
                    updateCurrentSourceLocked(source)
                    !wasOnline && source == ONLINE_SOURCE
                }
                if (becameOnline) {
                    AppLogger.log { "Gate: becameOnline -> post refresh callback" }
                    this.handler?.post { this.onOnlineSourceChanged?.invoke() }
                }
            }
        }
        if (!sourceListenerBound) {
            listener?.let { sourceListener ->
                runCatching {
                    newManager.addSourceStateListener(sourceListener)
                    sourceListenerBound = true
                    AppLogger.log { "Gate: addSourceStateListener registered" }
                }.onFailure { e ->
                    AppLogger.log { "Gate: addSourceStateListener failed ${e.message}" }
                }
            }
        }
        readCurrentSourceLocked(newManager)
        return newManager
    }

    private fun pauseOldSourceBeforeSwitch(
        activeManager: MediaCenterManager,
        oldSource: MediaCenterConstant.AudioSource?
    ) {
        when (oldSource) {
            MediaCenterConstant.AudioSource.AUDIO_SOURCE_RADIO -> {
                activeManager.radioManager?.pause()
                AppLogger.log { "Gate: pause old RADIO before ONLINE switch" }
            }

            MediaCenterConstant.AudioSource.AUDIO_SOURCE_BT,
            MediaCenterConstant.AudioSource.AUDIO_SOURCE_USB,
            MediaCenterConstant.AudioSource.AUDIO_SOURCE_CPAA -> {
                activeManager.musicAdapterManager?.pause()
                AppLogger.log { "Gate: pause old BT/USB/CPAA before ONLINE switch" }
            }

            else -> Unit
        }
    }

    private fun updateCurrentSourceLocked(source: MediaCenterConstant.AudioSource?) {
        val previousSource = currentSource
        if (source == null || previousSource == source) {
            return
        }
        AppLogger.log { "Gate: source listener $previousSource -> $source" }
        if (previousSource == ONLINE_SOURCE) {
            lastOnlineExitMs = SystemClock.elapsedRealtime()
            handler?.post { onOnlineSourceExited?.invoke() }
        }
        currentSource = source
    }

    private fun readCurrentSourceLocked(activeManager: MediaCenterManager) {
        val source = runCatching { activeManager.currentAudioSource }
            .onFailure { e -> AppLogger.log { "Gate: currentAudioSource failed ${e.message}" } }
            .getOrNull()
        updateCurrentSourceLocked(source)
    }

    private fun readCurrentAppSourceLocked(activeManager: MediaCenterManager): MediaCenterConstant.AppSource? {
        return runCatching { activeManager.currentAppSource }
            .onFailure { e -> AppLogger.log { "Gate: currentAppSource failed ${e.message}" } }
            .getOrNull()
    }

    private data class OnlineSourceRequest(
        val manager: MediaCenterManager,
        val oldSource: MediaCenterConstant.AudioSource?,
        val appSource: MediaCenterConstant.AppSource,
        val pauseBeforeSwitch: Boolean
    )

    private enum class SourceAvailability {
        ONLINE,
        OFFLINE,
        UNKNOWN
    }

    companion object {
        private val ONLINE_SOURCE = MediaCenterConstant.AudioSource.AUDIO_SOURCE_ONLINE

        private fun resolveStoredDisplayKey(rawInput: String): Pair<String, MediaCenterConstant.AppSource> {
            val raw = rawInput.trim()
            if (raw.isEmpty()) return "flow" to MediaCenterConstant.AppSource.WECARFLOW
            return when (raw) {
                "flow" -> raw to MediaCenterConstant.AppSource.WECARFLOW
                "net_easy_cloud" -> raw to MediaCenterConstant.AppSource.NETEASE_CLOUD
                "kugou" -> raw to MediaCenterConstant.AppSource.KUGOU
                "kuwo" -> raw to MediaCenterConstant.AppSource.KUWO
                "fandeng" -> raw to MediaCenterConstant.AppSource.FANDENG
                "jinri" -> raw to MediaCenterConstant.AppSource.JINRI
                "sohu" -> raw to MediaCenterConstant.AppSource.SOHU
                "xmly" -> raw to MediaCenterConstant.AppSource.XMLY
                "aiqiyi" -> raw to MediaCenterConstant.AppSource.QYS
                "G-C" -> raw to MediaCenterConstant.AppSource.GC
                "tencent_video" -> raw to MediaCenterConstant.AppSource.TENCENT_VIDEO
                "bilibili" -> raw to MediaCenterConstant.AppSource.BILIBILI
                "huoshan" -> raw to MediaCenterConstant.AppSource.HUOSHAN
                "thunder_voice" -> raw to MediaCenterConstant.AppSource.THUNDER_VOICE
                "cmvideo" -> raw to MediaCenterConstant.AppSource.CMVIDEO_VOICE
                else -> "flow" to MediaCenterConstant.AppSource.WECARFLOW
            }
        }

        private const val MANAGER_RETRY_DELAY_MS = 2000L
        private const val ONLINE_REQUEST_MIN_INTERVAL_MS = 1200L
        private const val ONLINE_RESTORE_AFTER_EXIT_BLOCK_MS = 1500L
        private const val NULL_MANAGER_LOG_INTERVAL_MS = 5000L
    }
}
