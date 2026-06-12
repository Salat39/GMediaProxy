@file:Suppress("SameParameterValue", "unused")

package com.geely.online.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

class MediaSessionPipeline private constructor() {
    private val lock = Any()
    private val callbacks =
        mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val coverBufferLock = Any()
    private val coverBufferList = mutableListOf<CoverBuffer>()
    private val audioSourceGate = OnlineAudioSourceGate()
    private val favoriteRatingController = FavoriteRatingController()
    private val playbackStateByToken = mutableMapOf<MediaSession.Token, Int?>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var coverThread: HandlerThread? = null
    private var coverHandler: Handler? = null
    private var listenerRegistered = false
    private var lastMediaSessionPackage = ""
    private var lastControlMediaSessionPackage = ""
    private var lastTrack: TrackSnapshot? = null
    private var lastProgressMs = 0L
    private var lastDurationMs = 0L
    private var lastProgressUpdatedAt = 0L
    private var metadataSettleToken: MediaSession.Token? = null
    private var metadataSettleAttempts = 0
    private var handlerThreadCreatedInLastStart = false
    private var bootPollAttemptsRemaining: Int = 0
    private var noControllerLogged: Boolean = false
    private var emptyMediaClearScheduled: Boolean = false
    private var favoritePollingSerial: Int = 0
    private var favoritePollingToken: MediaSession.Token? = null
    private var favoritePollingWantedLike: Boolean? = null
    private var favoritePollingTrackKey: String? = null
    private var lastFavoriteTrackKey: String? = null
    private var favoriteSettleSerial: Int = 0
    private var favoriteSettleToken: MediaSession.Token? = null
    private var favoriteSettleTrackKey: String? = null
    private var favoriteSettlePending: Boolean = false
    private var favoriteNotificationRefreshSerial: Int = 0
    private var lastMurglarTrackKey:String? = null
    private var onlineSourceActive = false

    private val sourceExitRepauseRunnable = Runnable {
        if (!onlineSourceActive) {
            pauseAppMediaSessionsForSourceExit("source exit verify")
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            workerHandler?.post {
                handleActiveSessionsChanged(controllers.orEmpty())
            }
        }

    private val progressRunnable = object : Runnable {
        override fun run() {
            publishProgressTick()
            val handler = workerHandler ?: return
            if ((lastTrack?.isPlaying == true || isBurstSyncPackage(lastMediaSessionPackage)) &&
                audioSourceGate.canPublishOnlineMedia()
            ) {
                handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val metadataSettleRunnable = object : Runnable {
        override fun run() {
            if (metadataSettleToken == null) {
                return
            }
            if (metadataSettleAttempts >= METADATA_SETTLE_MAX_ATTEMPTS) {
                AppLogger.log { "Pipeline: metadata settle stopped at maxAttempts=$METADATA_SETTLE_MAX_ATTEMPTS" }
                return
            }
            metadataSettleAttempts++
            refreshMediaInfo(true)
        }
    }

    private val emptyMediaClearRunnable = Runnable {
        emptyMediaClearScheduled = false
        clearActiveMediaData("empty media timeout")
    }

    private val bootPollRunnable = object : Runnable {
        override fun run() {
            if (lastTrack != null) {
                return
            }
            refreshControllers()
            refreshMediaInfo(true)
            if (lastTrack != null) {
                AppLogger.log { "Pipeline: bootPoll succeeded attemptsLeft=$bootPollAttemptsRemaining" }
                return
            }
            bootPollAttemptsRemaining -= 1
            if (bootPollAttemptsRemaining > 0) {
                workerHandler?.postDelayed(this, BOOT_POLL_INTERVAL_MS)
            } else {
                AppLogger.log { "Pipeline: bootPoll exhausted, relying on activeSessionsChanged" }
            }
        }
    }

    fun start(context: Context?) {
        if (context == null) {
            AppLogger.log { "Pipeline: start skipped context=null" }
            return
        }
        handlerThreadCreatedInLastStart = false
        synchronized(lock) {
            appContext = context.applicationContext
            if (workerThread == null) {
                handlerThreadCreatedInLastStart = true
                workerThread = HandlerThread("ProxyMediaSessionPipeline").also { thread ->
                    thread.start()
                    workerHandler = Handler(thread.looper)
                }
            }
            if (coverThread == null) {
                coverThread = HandlerThread("ProxyCoverPipeline").also { thread ->
                    thread.start()
                    coverHandler = Handler(thread.looper)
                }
            }
            if (mediaSessionManager == null) {
                mediaSessionManager =
                    appContext?.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                componentName = appContext?.let {
                    ComponentName(it, MediaSessionNotificationListenerService::class.java)
                }
            }
        }
        if (handlerThreadCreatedInLastStart) {
            AppLogger.log { "Pipeline: HandlerThread started" }
        }
        val posted = workerHandler?.post {
            AppLogger.log { "Pipeline: worker bootstrap run (gate, registerSessionsListener, refresh)" }
            val handler = workerHandler ?: return@post
            audioSourceGate.start(
                appContext,
                handler,
                onOnlineSourceChanged = {
                    onlineSourceActive = true
                    workerHandler?.removeCallbacks(sourceExitRepauseRunnable)
                    AppLogger.log { "Pipeline: gate became online -> refreshControllers + refreshMediaInfo" }
                    refreshControllers()
                    refreshMediaInfo(true)
                },
                onOnlineSourceExited = {
                    onlineSourceActive = false
                    pauseAppMediaSessionsForSourceExit("source exit")
                    workerHandler?.removeCallbacks(sourceExitRepauseRunnable)
                    workerHandler?.postDelayed(
                        sourceExitRepauseRunnable,
                        SOURCE_EXIT_REPAUSE_DELAY_MS
                    )
                }
            )
            registerSessionsListener()
            refreshControllers()
            refreshMediaInfo(true)
            bootPollAttemptsRemaining = BOOT_POLL_MAX_ATTEMPTS
            workerHandler?.removeCallbacks(bootPollRunnable)
            workerHandler?.postDelayed(bootPollRunnable, BOOT_POLL_INTERVAL_MS)
            AppLogger.log { "Pipeline: bootPoll scheduled attempts=$BOOT_POLL_MAX_ATTEMPTS interval=${BOOT_POLL_INTERVAL_MS}ms" }
        } ?: false
        if (!posted) {
            AppLogger.log { "Pipeline: start could not post to workerHandler=null" }
        }
    }

    fun releaseSessionsListener() {
        workerHandler?.post {
            AppLogger.log { "Pipeline: releaseSessionsListener begin" }
            workerHandler?.removeCallbacks(bootPollRunnable)
            workerHandler?.removeCallbacks(sourceExitRepauseRunnable)
            cancelFavoriteConfirmationPolling()
            favoriteNotificationRefreshSerial += 1
            cancelEmptyMediaClear()
            runCatching {
                if (listenerRegistered) {
                    mediaSessionManager?.removeOnActiveSessionsChangedListener(
                        activeSessionsListener
                    )
                    listenerRegistered = false
                    AppLogger.log { "Pipeline: active sessions listener removed" }
                }
            }.onFailure { e ->
                AppLogger.log { "Pipeline: remove listener failed ${e.message}" }
            }
            sleepMediaPublication()
            releaseControllerCallbacks()
            audioSourceGate.release()
            AppLogger.log { "Pipeline: releaseSessionsListener done" }
        } ?: AppLogger.log { "Pipeline: releaseSessionsListener skipped workerHandler=null" }
    }

    fun play() {
        executeControl(KeyEvent.KEYCODE_MEDIA_PLAY) { controller ->
            controller.transportControls.play()
        }
    }

    fun pause() {
        executeControl(KeyEvent.KEYCODE_MEDIA_PAUSE) { controller ->
            controller.transportControls.pause()
        }
    }

    fun next() {
        executeControl(KeyEvent.KEYCODE_MEDIA_NEXT) { controller ->
            controller.transportControls.skipToNext()
        }
    }

    fun previous() {
        executeControl(KeyEvent.KEYCODE_MEDIA_PREVIOUS) { controller ->
            controller.transportControls.skipToPrevious()
        }
    }

    fun seekTo(positionMs: Long) {
        workerHandler?.post {
            getPreferredController()?.transportControls?.seekTo(max(positionMs, 0L))
        }
    }

    fun refresh() {
        val posted = workerHandler?.post {
            refreshControllers()
            refreshMediaInfo(true)
        } ?: false
        if (!posted) {
            AppLogger.log { "Pipeline: refresh() skipped workerHandler=null" }
        }
    }

    fun lastKnownSessionPackage(): String = lastMediaSessionPackage

    fun setFavorite(isFavorite: Boolean) {
        val posted = workerHandler?.post {
            val controller = getPreferredController()
            val result = favoriteRatingController.apply(controller, isFavorite)
            if (result.handled && controller != null) {
                startFavoriteConfirmationPolling(
                    controller.sessionToken,
                    result.wantedLike,
                    currentTrackKey(controller)
                )
                AppLogger.log { "Pipeline: setFavorite pkg=${controller.packageName} wanted=${result.wantedLike} handled=true" }
            } else {
                cancelFavoriteConfirmationPolling()
                showFavoriteChangeFailedToast()
                refreshMediaInfo(true)
                AppLogger.log { "Pipeline: setFavorite pkg=${controller?.packageName} wanted=${result.wantedLike} handled=false" }
            }
        } ?: false
        if (!posted) {
            AppLogger.log { "Pipeline: setFavorite skipped workerHandler=null" }
        }
    }

    fun toggleFavorite() {
        val posted = workerHandler?.post {
            val controller = getPreferredController()
            val result = favoriteRatingController.apply(controller, null)
            if (result.handled && controller != null) {
                startFavoriteConfirmationPolling(
                    controller.sessionToken,
                    result.wantedLike,
                    currentTrackKey(controller)
                )
            } else {
                cancelFavoriteConfirmationPolling()
                showFavoriteChangeFailedToast()
                refreshMediaInfo(true)
            }
            AppLogger.log { "Pipeline: toggleFavorite pkg=${controller?.packageName} wanted=${result.wantedLike} handled=${result.handled}" }
        } ?: false
        if (!posted) {
            AppLogger.log { "Pipeline: toggleFavorite skipped workerHandler=null" }
        }
    }

    fun startLastMediaSessionApp() = runCatching {
        val posted = workerHandler?.post {
            val context = appContext ?: run {
                AppLogger.log { "Pipeline: startLastMediaSessionApp no appContext" }
                return@post
            }
            val controller = getControllerForLaunch()

            if (controller?.sessionActivity != null && sendSessionActivity(controller.sessionActivity)) {
                AppLogger.log { "Pipeline: startLastMediaSessionApp sessionActivity sent pkg=${controller.packageName}" }
                return@post
            }

            val packageName = controller?.packageName
                ?: lastControlMediaSessionPackage.takeIf { it.isNotBlank() }
                ?: lastMediaSessionPackage.takeIf {
                    it.isNotBlank() && !isBurstSyncPackage(it)
                }
                ?: run {
                    AppLogger.log { "Pipeline: startLastMediaSessionApp no packageName" }
                    return@post
                }

            AppLogger.log { "Pipeline: startLastMediaSessionApp launchApp pkg=$packageName" }
            context.launchApp(packageName)
        } ?: false
        if (!posted) {
            AppLogger.log { "Pipeline: startLastMediaSessionApp skipped workerHandler=null" }
        }
    }

    private fun getControllerForLaunch(): MediaController? {
        refreshControllers()
        val controllers = getActiveControllers()

        if (isBurstSyncPackage(lastMediaSessionPackage)) {
            return selectControlMediaController(controllers)
        }

        if (lastMediaSessionPackage.isNotEmpty()) {
            controllers.firstOrNull { controller ->
                controller.packageName == lastMediaSessionPackage
            }?.let { return it }
        }

        return getPreferredController()
    }

    private fun sendSessionActivity(sessionActivity: PendingIntent?): Boolean {
        val context = appContext ?: return false
        if (sessionActivity == null) {
            return false
        }

        return runCatching {
            sessionActivity.send(
                context,
                0,
                null,
                null,
                null,
                null,
                createLaunchOptionsBundle()
            )
            true
        }.getOrDefault(false)
    }

    @SuppressLint("NewApi")
    private fun createLaunchOptionsBundle(): android.os.Bundle? {
        val displayId = OnlineMusicState.get().currentDisplayId().toIntOrNull() ?: return null
        return ActivityOptions.makeBasic()
            .setLaunchDisplayId(displayId)
            .toBundle()
    }

    private fun registerSessionsListener() {
        if (listenerRegistered) {
            return
        }
        val manager = mediaSessionManager ?: run {
            AppLogger.log { "Pipeline: registerSessionsListener skip mediaSessionManager=null" }
            return
        }
        val component = componentName ?: run {
            AppLogger.log { "Pipeline: registerSessionsListener skip componentName=null" }
            return
        }
        runCatching {
            val handler = workerHandler
            if (handler != null) {
                manager.addOnActiveSessionsChangedListener(
                    activeSessionsListener,
                    component,
                    handler
                )
            } else {
                AppLogger.log { "Pipeline: registerSessionsListener workerHandler=null using default looper" }
                manager.addOnActiveSessionsChangedListener(activeSessionsListener, component)
            }
            listenerRegistered = true
            AppLogger.log { "Pipeline: active sessions listener registered" }
        }.onFailure { e ->
            AppLogger.log { "Pipeline: registerSessionsListener failed ${e.message}" }
        }
    }

    private fun handleActiveSessionsChanged(controllers: List<MediaController>) {
        val pkgs = controllers.take(5).joinToString(",") { it.packageName }
        val suffix = if (controllers.size > 5) "+${controllers.size - 5}more" else ""
        AppLogger.log { "Pipeline: activeSessionsChanged count=${controllers.size} pkgs=[$pkgs]$suffix" }
        updateCallbacks(controllers)
        refreshMediaInfo(true)
    }

    private fun refreshControllers() {
        updateCallbacks(getActiveControllers())
    }

    private fun getActiveControllers(): List<MediaController> {
        val manager = mediaSessionManager ?: return emptyList()
        val component = componentName ?: return emptyList()
        return runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
    }

    private fun updateCallbacks(controllers: List<MediaController>) {
        val activeTokens = controllers.map { it.sessionToken }.toSet()
        callbacks.keys.toList().forEach { token ->
            if (token !in activeTokens) {
                callbacks.remove(token)?.let { registration ->
                    playbackStateByToken.remove(token)
                    clearFavoriteSettleForToken(token)
                    runCatching { registration.first.unregisterCallback(registration.second) }
                    AppLogger.log { "Pipeline: callback unregistered pkg=${registration.first.packageName}" }
                }
            }
        }

        controllers.forEach { controller ->
            if (callbacks.containsKey(controller.sessionToken)) {
                return@forEach
            }
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    refreshMediaInfo(true)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    handlePlaybackStateChanged(controller, state)
                }

                override fun onSessionDestroyed() {
                    AppLogger.log { "Pipeline: onSessionDestroyed pkg=${controller.packageName}" }
                    callbacks.remove(controller.sessionToken)
                    playbackStateByToken.remove(controller.sessionToken)
                    clearFavoriteSettleForToken(controller.sessionToken)
                    refreshMediaInfo(true)
                }
            }
            playbackStateByToken[controller.sessionToken] = controller.playbackState?.state
            callbacks[controller.sessionToken] = controller to callback
            val handler = workerHandler
            if (handler != null) {
                controller.registerCallback(callback, handler)
            } else {
                controller.registerCallback(callback)
            }
            AppLogger.log { "Pipeline: callback registered pkg=${controller.packageName} tokenHash=${controller.sessionToken.hashCode()}" }
        }
    }

    private fun handlePlaybackStateChanged(controller: MediaController, state: PlaybackState?) {
        val token = controller.sessionToken
        val previousState = playbackStateByToken.put(token, state?.state)
        val isBurstSession = isBurstSyncPackage(controller.packageName)
        if (!isBurstSession) {
            rememberControlController(controller)
        }
        if (!isBurstSession && state?.state == PlaybackState.STATE_PLAYING && previousState != PlaybackState.STATE_PLAYING) {
            if (!audioSourceGate.isNativeAudioSourcePackage(controller.packageName)) {
                val selectedController = selectControlMediaController(
                    getActiveControllers().filterNot { activeController ->
                        audioSourceGate.isNativeAudioSourcePackage(activeController.packageName)
                    }
                )
                if (selectedController?.sessionToken == token) {
                    audioSourceGate.requestOnlineAudioSourceIfNeeded(controller.packageName)
                }
            }
        }
        refreshMediaInfo(true)
    }

    private fun startFavoriteConfirmationPolling(
        token: MediaSession.Token,
        wantedLike: Boolean,
        trackKey: String?
    ) {
        val handler = workerHandler ?: return
        val serial = nextFavoritePollingSerial()
        val startedAt = SystemClock.elapsedRealtime()
        favoritePollingToken = token
        favoritePollingWantedLike = wantedLike
        favoritePollingTrackKey = trackKey
        handler.postDelayed(
            { pollFavoriteConfirmation(serial, token, wantedLike, trackKey, startedAt) },
            FAVORITE_POLL_INTERVAL_MS
        )
    }

    private fun pollFavoriteConfirmation(
        serial: Int,
        token: MediaSession.Token,
        wantedLike: Boolean,
        trackKey: String?,
        startedAt: Long
    ) {
        if (serial != favoritePollingSerial) {
            return
        }
        val handler = workerHandler ?: return
        val controller = getActiveControllers().firstOrNull { it.sessionToken == token }
        if (trackKey != currentTrackKey(controller)) {
            clearFavoriteConfirmationPolling(serial)
            refreshMediaInfo(true)
            AppLogger.log { "Pipeline: favorite confirmation cancelled because track changed pkg=${controller?.packageName}" }
            return
        }
        val status = favoriteRatingController.read(controller)
        if (status?.known == true && status.liked == wantedLike) {
            clearFavoriteConfirmationPolling(serial)
            OnlineMusicState.get().setFavor(status.liked)
            refreshMediaInfo(true)
            AppLogger.log { "Pipeline: favorite confirmed pkg=${controller?.packageName} liked=${status.liked}" }
            return
        }
        if (SystemClock.elapsedRealtime() - startedAt >= FAVORITE_CONFIRM_TIMEOUT_MS) {
            clearFavoriteConfirmationPolling(serial)
            refreshMediaInfo(true)
            showFavoriteChangeFailedToast()
            AppLogger.log { "Pipeline: favorite confirmation timeout wanted=$wantedLike pkg=${controller?.packageName}" }
            return
        }
        handler.postDelayed(
            { pollFavoriteConfirmation(serial, token, wantedLike, trackKey, startedAt) },
            FAVORITE_POLL_INTERVAL_MS
        )
    }

    private fun nextFavoritePollingSerial(): Int {
        favoritePollingSerial += 1
        return favoritePollingSerial
    }

    private fun confirmFavoritePollingIfMatched(
        token: MediaSession.Token,
        status: FavoriteRatingController.FavoriteStatus?
    ) {
        val wantedLike = favoritePollingWantedLike ?: return
        if (favoritePollingToken != token || favoritePollingTrackKey != currentTrackKey(token) || status?.known != true || status.liked != wantedLike) {
            return
        }
        clearFavoriteConfirmationPolling(favoritePollingSerial)
        AppLogger.log { "Pipeline: favorite confirmed from callback liked=${status.liked}" }
    }

    private fun cancelFavoriteConfirmationPolling() {
        favoritePollingSerial += 1
        favoritePollingToken = null
        favoritePollingWantedLike = null
        favoritePollingTrackKey = null
    }

    private fun clearFavoriteConfirmationPolling(serial: Int) {
        if (serial == favoritePollingSerial) {
            favoritePollingToken = null
            favoritePollingWantedLike = null
            favoritePollingTrackKey = null
            favoritePollingSerial += 1
        }
    }

    private fun showFavoriteChangeFailedToast() {
        val context = appContext ?: return
        mainHandler.post {
            Toast.makeText(context, R.string.failed_to_confirm_like, Toast.LENGTH_SHORT).show()
        }
    }

    private fun releaseControllerCallbacks() {
        val n = callbacks.size
        callbacks.forEach { (_, registration) ->
            runCatching { registration.first.unregisterCallback(registration.second) }
        }
        callbacks.clear()
        playbackStateByToken.clear()
        clearFavoriteSettle()
        if (n > 0) {
            AppLogger.log { "Pipeline: releaseControllerCallbacks cleared n=$n" }
        }
    }

    private fun debugLogSessionSnapshotIfPossible() {
        val controller = selectMediaController(getActiveControllers()) ?: return
        val playbackState = controller.playbackState
        val metadata = controller.metadata
        if (playbackState == null || metadata == null || !metadata.hasDisplayableMediaData()) {
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: "Unknown Title"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: "Unknown Artist"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "Unknown Album"
        val hasCover = !metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).isNullOrBlank() ||
                !metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).isNullOrBlank() ||
                !metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).isNullOrBlank() ||
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
        val isPlayed = playbackState.state == PlaybackState.STATE_PLAYING
        val isFavor = favoriteRatingController.read(controller)?.takeIf { it.known }?.liked ?: false
        MediaTransmissionLog.logSnapshotIfChanged(
            title,
            artist,
            album,
            hasCover,
            isPlayed = isPlayed,
            isFavor = isFavor,
        )
    }

    private fun refreshMediaInfo(forceMetadata: Boolean) {
        val context = appContext ?: run {
            AppLogger.log { "Pipeline: refreshMediaInfo skip appContext=null forceMetadata=$forceMetadata" }
            return
        }
        val canPublish = audioSourceGate.canPublishOnlineMedia()
        if (!canPublish) {
            if (BuildConfig.DEBUG) {
                debugLogSessionSnapshotIfPossible()
            }
            sleepMediaPublication()
            return
        }
        val sessions = getActiveControllers()
        val controller = selectDisplayMediaController(sessions)
        if (controller != null) {
            noControllerLogged = false
        }
        if (controller == null) {
            if (lastTrack != null) {
                sleepMediaPublication()
                scheduleEmptyMediaClear("no controller")
                if (!noControllerLogged) {
                    AppLogger.log { "Pipeline: no controller, keeping last track activeSessions=${sessions.size} title=${logTruncate(lastTrack?.title)}" }
                    noControllerLogged = true
                }
                return
            }
            clearLocalMediaSnapshot()
            sleepMediaPublication()
            if (!noControllerLogged) {
                AppLogger.log { "Pipeline: no controller, no prior track activeSessions=${sessions.size} -> wait silently" }
                noControllerLogged = true
            }
            return
        }

        val sourcePackageName = controller.packageName
        val isBurstDisplaySession = isBurstSyncPackage(sourcePackageName)
        lastMediaSessionPackage = sourcePackageName
        if (!isBurstDisplaySession) {
            rememberControlController(controller)
        }
        if (OnlineMusicState.get()
                .clearDisplayOverrideIfSessionPackageChanged(context, sourcePackageName)
        ) {
            AppLogger.log { "Pipeline: override cleared sessionPackage=$sourcePackageName" }
        }
        val playbackState = controller.playbackState
        val metadata = controller.metadata

        if (playbackState == null || metadata == null || !metadata.hasDisplayableMediaData()) {
            if (lastTrack != null) {
                sleepMediaPublication()
                scheduleEmptyMediaClear("empty metadata")
            }
            if (isActivePlaybackState(playbackState?.state)) {
                scheduleMetadataSettle(controller)
            }
            return
        }
        resetMetadataSettle()
        cancelEmptyMediaClear()

        val title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: "Unknown Title"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: "Unknown Artist"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "Unknown Album"
        val durationMs = max(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), 0L)
        val positionMs = max(playbackState.position, 0L)
        val controlController = if (isBurstDisplaySession) {
            selectControlMediaController(sessions)
        } else {
            controller
        }
        val effectivePlaybackState = controlController?.playbackState ?: playbackState
        val isPlaying = effectivePlaybackState.state == PlaybackState.STATE_PLAYING
        val playState = effectivePlaybackState.toOnlinePlayState()
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val uniqueSign = if (!mediaId.isNullOrBlank()) mediaId else title + artist + album
        val trackId = uniqueSign.generateFileId()
        val trackKey = sourcePackageName + ":" + trackId
        val favoriteController = controlController ?: controller
        val favoriteTrackKey = if (favoriteController.sessionToken == controller.sessionToken) {
            trackKey
        } else {
            currentTrackKey(favoriteController) ?: trackKey
        }
        val favoriteStatus = readFavoriteStatus(favoriteController, favoriteTrackKey)
        confirmFavoritePollingIfMatched(favoriteController.sessionToken, favoriteStatus)
        val cover = prepareCover(trackId, sourcePackageName, metadata)

        // Try reinit murglar playback hack
        if (!isBurstDisplaySession) {
            tryMurglarPlaybackHack(sourcePackageName, trackKey)
        }

        lastTrack = TrackSnapshot(title, artist, album, cover?.toString().orEmpty(), isPlaying)
        lastProgressMs = positionMs
        lastDurationMs = durationMs
        lastProgressUpdatedAt = SystemClock.elapsedRealtime()

        OnlineMusicState.get().update(
            context,
            title,
            artist,
            album,
            cover?.toString().orEmpty(),
            isPlaying,
            positionMs,
            durationMs,
            forceMetadata,
            playState,
            favoriteStatus?.takeIf { it.known }?.liked,
            forceProgress = isBurstDisplaySession
        )
        updateProgressTicker(isPlaying || isBurstDisplaySession)
    }

    private fun readFavoriteStatus(
        controller: MediaController,
        trackKey: String
    ): FavoriteRatingController.FavoriteStatus? {
        val packageName = controller.packageName
        if (!favoriteRatingController.needsTrackChangeSettle(packageName)) {
            lastFavoriteTrackKey = trackKey
            clearFavoriteSettle()
            return favoriteRatingController.read(controller)
        }

        if (lastFavoriteTrackKey != trackKey) {
            lastFavoriteTrackKey = trackKey
            cancelFavoriteConfirmationPolling()
            scheduleFavoriteSettle(controller, trackKey)
            AppLogger.log { "Pipeline: favorite settle scheduled pkg=$packageName" }
            return null
        }

        if (favoriteSettlePending && favoriteSettleToken == controller.sessionToken && favoriteSettleTrackKey == trackKey) {
            return null
        }

        return favoriteRatingController.read(controller)
    }

    private fun scheduleFavoriteSettle(controller: MediaController, trackKey: String) {
        val handler = workerHandler ?: return
        favoriteSettleSerial += 1
        val serial = favoriteSettleSerial
        favoriteSettleToken = controller.sessionToken
        favoriteSettleTrackKey = trackKey
        favoriteSettlePending = true
        handler.postDelayed(
            { runFavoriteSettle(serial, controller.sessionToken, trackKey) },
            FAVORITE_TRACK_CHANGE_SETTLE_MS
        )
    }

    private fun runFavoriteSettle(serial: Int, token: MediaSession.Token, trackKey: String) {
        if (serial != favoriteSettleSerial || favoriteSettleToken != token || favoriteSettleTrackKey != trackKey) {
            return
        }
        favoriteSettlePending = false
        refreshMediaInfo(true)
    }

    private fun clearFavoriteSettleForToken(token: MediaSession.Token) {
        if (favoriteSettleToken == token) {
            clearFavoriteSettle()
        }
    }

    private fun clearFavoriteSettle() {
        favoriteSettleSerial += 1
        favoriteSettleToken = null
        favoriteSettleTrackKey = null
        favoriteSettlePending = false
    }

    private fun currentTrackKey(token: MediaSession.Token): String? {
        val controller = getActiveControllers().firstOrNull { it.sessionToken == token } ?: return null
        return currentTrackKey(controller)
    }

    private fun currentTrackKey(controller: MediaController?): String? {
        if (controller == null) {
            return null
        }
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: "Unknown Title"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: "Unknown Artist"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "Unknown Album"
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val uniqueSign = if (!mediaId.isNullOrBlank()) mediaId else title + artist + album
        return controller.packageName + ":" + uniqueSign.generateFileId()
    }

    private fun selectDisplayMediaController(controllers: List<MediaController>): MediaController? {
        var activeController: MediaController? = null
        var dataController: MediaController? = null
        controllers.forEach { controller ->
            val playbackState = controller.playbackState
            val metadata = controller.metadata
            val active = isActivePlaybackState(playbackState?.state)
            if (controller.packageName in AuxiliaryPackages.BURST_SYNC_MEDIA_SESSION_PACKAGES &&
                active &&
                metadata?.hasDisplayableMediaData() == true
            ) {
                return controller
            }
            if (activeController == null && active) {
                activeController = controller
            }
            if (dataController == null && metadata != null && playbackState != null) {
                dataController = controller
            }
        }
        return activeController ?: dataController
    }

    private fun selectMediaController(controllers: List<MediaController>): MediaController? {
        return controllers.firstOrNull { controller ->
            isActivePlaybackState(controller.playbackState?.state)
        } ?: controllers.firstOrNull { controller ->
            controller.metadata != null && controller.playbackState != null
        }
    }

    private fun selectControlMediaController(controllers: List<MediaController>): MediaController? {
        val controlControllers = controllers.filterNot { controller ->
            isBurstSyncPackage(controller.packageName)
        }
        if (lastControlMediaSessionPackage.isNotEmpty()) {
            controlControllers.firstOrNull { controller ->
                controller.packageName == lastControlMediaSessionPackage &&
                        controller.metadata != null &&
                        controller.playbackState != null
            }?.let { controller ->
                rememberControlController(controller)
                return controller
            }
        }
        controlControllers.firstOrNull { controller ->
            isActivePlaybackState(controller.playbackState?.state)
        }?.let { controller ->
            rememberControlController(controller)
            return controller
        }
        return controlControllers.firstOrNull { controller ->
            controller.metadata != null && controller.playbackState != null
        }?.also { controller ->
            rememberControlController(controller)
        }
    }

    private fun rememberControlController(controller: MediaController?) {
        val packageName = controller?.packageName ?: return
        if (!isBurstSyncPackage(packageName)) {
            lastControlMediaSessionPackage = packageName
        }
    }

    private fun isBurstSyncPackage(packageName: String?): Boolean {
        return packageName in AuxiliaryPackages.BURST_SYNC_MEDIA_SESSION_PACKAGES
    }

    private fun scheduleMetadataSettle(controller: MediaController) {
        val handler = workerHandler ?: run {
            AppLogger.log { "Pipeline: scheduleMetadataSettle skip workerHandler=null pkg=${controller.packageName}" }
            return
        }
        if (metadataSettleToken != controller.sessionToken) {
            handler.removeCallbacks(metadataSettleRunnable)
            metadataSettleToken = controller.sessionToken
            metadataSettleAttempts = 0
        }
        if (metadataSettleAttempts >= METADATA_SETTLE_MAX_ATTEMPTS) {
            AppLogger.log { "Pipeline: scheduleMetadataSettle give up maxAttempts=$METADATA_SETTLE_MAX_ATTEMPTS pkg=${controller.packageName}" }
            return
        }
        handler.removeCallbacks(metadataSettleRunnable)
        handler.postDelayed(metadataSettleRunnable, METADATA_SETTLE_DELAY_MS)
    }

    private fun resetMetadataSettle() {
        workerHandler?.removeCallbacks(metadataSettleRunnable)
        metadataSettleToken = null
        metadataSettleAttempts = 0
    }

    private fun isActivePlaybackState(state: Int?): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING
    }

    private fun PlaybackState.toOnlinePlayState(): Int {
        return when (state) {
            PlaybackState.STATE_PLAYING -> OnlineMusicState.PLAY_STATE_PLAY
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> OnlineMusicState.PLAY_STATE_STOP
            else -> OnlineMusicState.PLAY_STATE_PAUSE
        }
    }

    private fun MediaMetadata.hasDisplayableMediaData(): Boolean {
        return !getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNullOrBlank() ||
                !getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
                !getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).isNullOrBlank() ||
                !getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank() ||
                !getString(MediaMetadata.METADATA_KEY_ALBUM).isNullOrBlank() ||
                getLong(MediaMetadata.METADATA_KEY_DURATION) > 0L ||
                !getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).isNullOrBlank() ||
                !getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).isNullOrBlank() ||
                !getString(MediaMetadata.METADATA_KEY_ART_URI).isNullOrBlank() ||
                getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
                getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
    }

    private fun prepareCover(trackId: String, packageName: String, metadata: MediaMetadata): Uri? {
        val cachedBufferCandidate = getCoverBuffer(trackId)
        val cachedBufferFileUnavailable = isReadyCoverBufferFileUnavailable(cachedBufferCandidate)
        if (cachedBufferFileUnavailable) {
            invalidateCoverBuffer(trackId)
        }
        var cachedBuffer = if (cachedBufferFileUnavailable) null else cachedBufferCandidate

        if (cachedBuffer?.id == trackId && cachedBuffer.status == LoadCoverStatus.LOADING) {
            return getPreparedCoverUri(cachedBuffer.takeIf { it.id == trackId })
        }

        if (packageName == AuxiliaryPackages.HAVA_YM) {
            val albumArtUriStr =
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: ""

            if (cachedBuffer != null && cachedBuffer.mediaUri != albumArtUriStr) {
                val oldArtFileName = cachedBuffer.mediaUri.generateUuid()
                val oldFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "$TEMP_FOLDER/$oldArtFileName$FILE_TYPE"
                )
                if (oldFile.exists()) {
                    runCatching { oldFile.delete() }
                }
                cachedBuffer = null
            }

            if (albumArtUriStr.isNotBlank()) {
                val baseArtFileName = albumArtUriStr.generateUuid()
                val artFileName = if (cachedBufferFileUnavailable) {
                    "${baseArtFileName}_${System.currentTimeMillis()}"
                } else {
                    baseArtFileName
                }
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "$TEMP_FOLDER/$artFileName$FILE_TYPE"
                )

                if (file.exists()) {
                    if (cachedBuffer == null) {
                        val newBuffer = CoverBuffer(
                            id = trackId,
                            mediaUri = albumArtUriStr,
                            fileUri = Uri.fromFile(file),
                            bitmap = null,
                            status = LoadCoverStatus.READY
                        )
                        addCoverBuffer(newBuffer)
                        cachedBuffer = newBuffer
                    }
                } else if (cachedBuffer == null || cachedBuffer.status == LoadCoverStatus.ERROR) {
                    val newBuffer = CoverBuffer(
                        id = trackId,
                        mediaUri = albumArtUriStr,
                        fileUri = null,
                        bitmap = null,
                        status = LoadCoverStatus.LOADING
                    )
                    addCoverBuffer(newBuffer)
                    postCoverTask {
                        clearCachedArtworkFolder()
                        resolveAndExportArtworkHttpUri(
                            albumArtUriStr.toUri(),
                            newBuffer,
                            artFileName
                        )
                    }
                }
            }
        } else {
            val albumArtUriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: ""
            val artBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            val needCoverReload = cachedBuffer?.status == LoadCoverStatus.READY &&
                    cachedBuffer.bitmap != null &&
                    hasBitmapChanged(cachedBuffer.bitmap, artBitmap)
            if (needCoverReload) {
                cachedBuffer = null
            }

            if (artBitmap != null && (cachedBuffer == null || cachedBuffer.id != trackId)) {
                val newBuffer = CoverBuffer(
                    id = trackId,
                    mediaUri = albumArtUriStr,
                    fileUri = null,
                    bitmap = artBitmap,
                    status = LoadCoverStatus.LOADING
                )
                addCoverBuffer(newBuffer)
                postCoverTask {
                    clearCachedArtworkFolder()
                    resolveAndExportArtworkBitmap(artBitmap, newBuffer)
                }
            }
        }

        return getPreparedCoverUri(cachedBuffer?.takeIf { it.id == trackId })
    }

    private fun resolveAndExportArtworkBitmap(bitmap: Bitmap, coverData: CoverBuffer) {
        try {
            if (coverData.status != LoadCoverStatus.LOADING) {
                updateCoverBuffer(coverData.copy(status = LoadCoverStatus.LOADING))
            }
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                TEMP_FOLDER
            ).apply { mkdirs() }
            val artworkFile = File(downloadsDir, "${UUID.randomUUID()}$FILE_TYPE")
            FileOutputStream(artworkFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (getCoverBuffer(coverData.id) != null) {
                updateCoverBuffer(
                    coverData.copy(
                        fileUri = Uri.fromFile(artworkFile),
                        status = LoadCoverStatus.READY
                    )
                )
                AppLogger.log { "Pipeline: cover bitmap ready id=${logTruncate(coverData.id, 16)} -> refreshMediaInfo" }
                workerHandler?.post { refreshMediaInfo(true) }
                return
            }
        } catch (e: Exception) {
            AppLogger.log { "Pipeline: cover bitmap export error ${e.message}" }
        }
        updateCoverBuffer(coverData.copy(status = LoadCoverStatus.ERROR))
    }

    private fun resolveAndExportArtworkHttpUri(
        mediaUri: Uri,
        coverData: CoverBuffer,
        fileName: String
    ) {
        try {
            if (coverData.status != LoadCoverStatus.LOADING) {
                updateCoverBuffer(coverData.copy(status = LoadCoverStatus.LOADING))
            }
            val bitmap = URL(mediaUri.toString()).openConnection().let { connection ->
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
                connection.getInputStream().use { input -> BitmapFactory.decodeStream(input) }
            } ?: throw IllegalStateException()
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                TEMP_FOLDER
            ).apply { mkdirs() }
            val artworkFile = File(downloadsDir, "$fileName$FILE_TYPE")
            FileOutputStream(artworkFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (getCoverBuffer(coverData.id) != null) {
                updateCoverBuffer(
                    coverData.copy(
                        fileUri = Uri.fromFile(artworkFile),
                        status = LoadCoverStatus.READY
                    )
                )
                AppLogger.log { "Pipeline: cover http ready id=${logTruncate(coverData.id, 16)} -> refreshMediaInfo" }
                workerHandler?.post { refreshMediaInfo(true) }
                return
            }
        } catch (e: Exception) {
            AppLogger.log { "Pipeline: cover http export error ${e.message}" }
        }
        updateCoverBuffer(coverData.copy(status = LoadCoverStatus.ERROR))
    }

    private fun clearCachedArtworkFolder() {
        val tempDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            TEMP_FOLDER
        )
        if (!tempDir.exists() || !tempDir.isDirectory) {
            return
        }
        val files = tempDir.listFiles { file -> file.isFile }
            ?.sortedBy { it.lastModified() }
            ?: return
        if (files.size > CACHE_FILE_BUFFER) {
            files.take(files.size - CACHE_FILE_BUFFER).forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private fun getPreparedCoverUri(buffer: CoverBuffer?): Uri? {
        if (buffer?.status != LoadCoverStatus.READY) {
            return null
        }
        if (isReadyCoverBufferFileUnavailable(buffer)) {
            invalidateCoverBuffer(buffer.id)
            return null
        }
        return buffer.fileUri
    }

    private fun isReadyCoverBufferFileUnavailable(buffer: CoverBuffer?): Boolean {
        if (buffer?.status != LoadCoverStatus.READY) {
            return false
        }
        val uri = buffer.fileUri ?: return false
        if (uri.scheme != null && uri.scheme != "file") {
            return false
        }
        val path = uri.path ?: return true
        val file = File(path)
        return !file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L
    }

    private fun invalidateCoverBuffer(id: String) {
        synchronized(coverBufferLock) {
            coverBufferList.removeAll { it.id == id }
        }
    }

    private fun getCoverBuffer(id: String): CoverBuffer? {
        return synchronized(coverBufferLock) {
            coverBufferList.find { it.id == id }
        }
    }

    private fun addCoverBuffer(newBuffer: CoverBuffer) {
        synchronized(coverBufferLock) {
            coverBufferList.removeAll { it.id == newBuffer.id }
            if (coverBufferList.size >= CACHE_FILE_BUFFER + 1) {
                coverBufferList.removeAt(0)
            }
            coverBufferList.add(newBuffer)
        }
    }

    private fun updateCoverBuffer(updated: CoverBuffer) {
        synchronized(coverBufferLock) {
            val index = coverBufferList.indexOfFirst { it.id == updated.id }
            if (index >= 0) {
                coverBufferList[index] = updated
            }
        }
    }

    private fun postCoverTask(action: () -> Unit) {
        val posted = coverHandler?.post(action) ?: false
        if (!posted) {
            AppLogger.log { "Pipeline: cover task skipped coverHandler=null" }
        }
    }

    private fun scheduleEmptyMediaClear(reason: String) {
        val handler = workerHandler ?: return
        if (emptyMediaClearScheduled) {
            return
        }
        emptyMediaClearScheduled = true
        handler.postDelayed(emptyMediaClearRunnable, EMPTY_MEDIA_CLEAR_DELAY_MS)
        AppLogger.log { "Pipeline: empty media clear scheduled reason=$reason delay=${EMPTY_MEDIA_CLEAR_DELAY_MS}ms" }
    }

    private fun cancelEmptyMediaClear() {
        workerHandler?.removeCallbacks(emptyMediaClearRunnable)
        emptyMediaClearScheduled = false
    }

    private fun clearActiveMediaData(reason: String) {
        val context = appContext ?: return
        if (lastTrack == null) {
            clearLocalMediaSnapshot()
            return
        }
        AppLogger.log { "Pipeline: clear active media reason=$reason title=${logTruncate(lastTrack?.title)}" }
        clearLocalMediaSnapshot()
        sleepMediaPublication()
        OnlineMusicState.get().clear(context)
    }

    private fun clearLocalMediaSnapshot() {
        lastTrack = null
        lastMediaSessionPackage = ""
        lastControlMediaSessionPackage = ""
        lastProgressMs = 0L
        lastDurationMs = 0L
        lastProgressUpdatedAt = SystemClock.elapsedRealtime()
    }

    private fun sleepMediaPublication() {
        workerHandler?.removeCallbacks(progressRunnable)
        resetMetadataSettle()
    }

    private fun updateProgressTicker(isPlaying: Boolean) {
        val handler = workerHandler ?: return
        handler.removeCallbacks(progressRunnable)
        if (isPlaying &&
            audioSourceGate.canPublishOnlineMedia()
        ) {
            handler.postDelayed(progressRunnable, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private fun publishProgressTick() {
        val context = appContext ?: return
        if (!audioSourceGate.canPublishOnlineMedia()) {
            sleepMediaPublication()
            return
        }
        val track = lastTrack ?: run {
            AppLogger.log { "Pipeline: publishProgressTick anomaly lastTrack=null" }
            return
        }
        val isBurstDisplaySession = isBurstSyncPackage(lastMediaSessionPackage)
        if ((!track.isPlaying && !isBurstDisplaySession) || track.title.isEmpty() && track.artist.isEmpty()) {
            return
        }
        val currentProgress: Long
        val currentDuration: Long
        if (lastMediaSessionPackage in AuxiliaryPackages.BURST_SYNC_MEDIA_SESSION_PACKAGES) {
            val progress = readBurstProgress()
            if (progress != null) {
                currentProgress = progress.first
                currentDuration = progress.second
            } else {
                val elapsed = SystemClock.elapsedRealtime() - lastProgressUpdatedAt
                currentProgress = if (lastDurationMs > 0L) {
                    (lastProgressMs + elapsed).coerceAtMost(lastDurationMs)
                } else {
                    lastProgressMs + elapsed
                }
                currentDuration = lastDurationMs
            }
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastProgressUpdatedAt
            currentProgress = if (lastDurationMs > 0L) {
                (lastProgressMs + elapsed).coerceAtMost(lastDurationMs)
            } else {
                lastProgressMs + elapsed
            }
            currentDuration = lastDurationMs
        }
        OnlineMusicState.get().update(
            context,
            track.title,
            track.artist,
            track.album,
            track.cover,
            track.isPlaying,
            currentProgress,
            currentDuration,
            false,
            forceProgress = isBurstDisplaySession
        )
    }

    private fun readBurstProgress(): Pair<Long, Long>? {
        val controller = selectDisplayMediaController(getActiveControllers()) ?: return null
        if (controller.packageName != lastMediaSessionPackage) {
            return null
        }
        val playbackState = controller.playbackState ?: return null
        val metadata = controller.metadata ?: return null
        val positionMs = max(playbackState.position, 0L)
        val durationMs = max(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), 0L)
        lastProgressMs = positionMs
        lastDurationMs = durationMs
        lastProgressUpdatedAt = SystemClock.elapsedRealtime()
        return positionMs to durationMs
    }

    private fun executeControl(keyCode: Int, action: (MediaController) -> Unit) {
        workerHandler?.post {
            val controller = getPreferredController()
            if (controller != null) {
                if (!sendControllerMediaButton(controller, keyCode)) {
                    runCatching { action(controller) }
                }
            } else if (!isBurstSyncPackage(lastMediaSessionPackage)) {
                sendGlobalMediaButton(keyCode)
            } else {
                AppLogger.log { "Pipeline: control skipped for burst display because control controller is unavailable" }
            }
        }
    }

    private fun getPreferredController(): MediaController? {
        refreshControllers()
        val controllers = getActiveControllers()
        if (isBurstSyncPackage(lastMediaSessionPackage)) {
            return selectControlMediaController(controllers)
        }
        if (lastMediaSessionPackage.isNotEmpty()) {
            controllers.firstOrNull { controller ->
                controller.packageName == lastMediaSessionPackage &&
                        controller.metadata != null &&
                        controller.playbackState != null
            }?.let { return it }
        }
        controllers.firstOrNull { controller ->
            isActivePlaybackState(controller.playbackState?.state)
        }?.let { return it }
        return controllers.firstOrNull { controller ->
            controller.metadata != null && controller.playbackState != null
        }
    }

    private fun pauseAppMediaSessionsForSourceExit(reason: String) {
        refreshControllers()
        val pausedPackages = mutableListOf<String>()
        getActiveControllers()
            .filterNot { controller ->
                audioSourceGate.isNativeAudioSourcePackage(controller.packageName) ||
                        isBurstSyncPackage(controller.packageName)
            }
            .forEach { controller ->
                if (!isActivePlaybackState(controller.playbackState?.state)) {
                    return@forEach
                }
                if (!sendControllerMediaButton(controller, KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                    runCatching { controller.transportControls.pause() }
                }
                pausedPackages.add(controller.packageName)
            }
        if (pausedPackages.isNotEmpty()) {
            AppLogger.log {
                "Pipeline: pause app sessions on $reason pkgs=${pausedPackages.distinct().joinToString(",")}"
            }
        }
    }

    private fun sendControllerMediaButton(controller: MediaController, keyCode: Int): Boolean {
        val down = runCatching {
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }.getOrDefault(false)
        val up = runCatching {
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.getOrDefault(false)
        return down || up
    }

    private fun sendGlobalMediaButton(keyCode: Int) {
        val audioManager =
            appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun hasBitmapChanged(oldBitmap: Bitmap?, newBitmap: Bitmap?): Boolean {
        if (oldBitmap === newBitmap) {
            return false
        }
        if (oldBitmap == null || newBitmap == null) {
            return true
        }
        if (oldBitmap.width != newBitmap.width || oldBitmap.height != newBitmap.height || oldBitmap.config != newBitmap.config) {
            return true
        }
        val positions = listOf(
            0 to 0,
            oldBitmap.width - 1 to 0,
            0 to oldBitmap.height - 1,
            oldBitmap.width - 1 to oldBitmap.height - 1,
            oldBitmap.width / 2 to oldBitmap.height / 2
        )
        return positions.any { position ->
            oldBitmap.getPixel(
                position.first,
                position.second
            ) != newBitmap.getPixel(position.first, position.second)
        }
    }

    private fun logTruncate(text: String?, maxLen: Int = 48): String {
        val s = text ?: ""
        return if (s.length <= maxLen) s else s.take(maxLen) + "…"
    }

    private fun String.generateFileId(): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(toByteArray())
        return hashBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun String.generateUuid(): String {
        return UUID.nameUUIDFromBytes(toByteArray(Charsets.UTF_8)).toString()
    }

    // For Murglar, playback state ping-pong.
    // Because the data from the "like" is carried over to the next track.
    // Because the author of "Murglar" decided to reinvent the wheel.
    private fun tryMurglarPlaybackHack(packageName: String, trackKey: String) {
        if (packageName != AuxiliaryPackages.MURGLAR) return
        val previousTrackKey = lastMurglarTrackKey
        lastMurglarTrackKey = trackKey
        if (previousTrackKey == null || previousTrackKey == trackKey) return

        // direct sync
        getPreferredController()?.let { controller ->
            controller.playbackState?.position?.let { pos ->
                controller.transportControls.seekTo(pos)
            }
        }

        // extra sync
        (appContext as? App)?.applicationScope?.launch {
            delay(150L)
            withContext(Dispatchers.Main) {
                getPreferredController()?.let { controller ->
                    controller.playbackState?.position?.let { pos ->
                        controller.transportControls.seekTo(pos)
                    }
                }
            }
        }
    }

    private data class TrackSnapshot(
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val isPlaying: Boolean
    )

    private data class CoverBuffer(
        val id: String,
        val mediaUri: String,
        val fileUri: Uri?,
        val bitmap: Bitmap?,
        val status: LoadCoverStatus
    )

    private enum class LoadCoverStatus {
        LOADING,
        READY,
        ERROR
    }

    companion object {
        private const val TEMP_FOLDER = ".gmp_temp"
        private const val FILE_TYPE = ".jpg"
        private const val CACHE_FILE_BUFFER = 7
        private const val JPEG_QUALITY = 90
        private const val NETWORK_TIMEOUT_MS = 3000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1000L
        private const val FAVORITE_POLL_INTERVAL_MS = 300L
        private const val FAVORITE_CONFIRM_TIMEOUT_MS = 3000L
        private const val FAVORITE_TRACK_CHANGE_SETTLE_MS = 500L
        private const val METADATA_SETTLE_DELAY_MS = 700L
        private const val METADATA_SETTLE_MAX_ATTEMPTS = 20
        private const val BOOT_POLL_INTERVAL_MS = 1000L
        private const val BOOT_POLL_MAX_ATTEMPTS = 30
        private const val EMPTY_MEDIA_CLEAR_DELAY_MS = 60_000L
        private const val SOURCE_EXIT_REPAUSE_DELAY_MS = 700L
        private val INSTANCE = MediaSessionPipeline()

        fun get(): MediaSessionPipeline = INSTANCE
    }
}
