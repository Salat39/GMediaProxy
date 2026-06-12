package com.geely.online.service

import android.content.Context
import android.os.RemoteCallbackList
import android.os.RemoteException
import com.geely.online.music.IMusicInfoListener
import com.geely.online.music.IMusicStateListener
import com.geely.online.music.IMusicUserInfoListener
import com.geely.online.music.bean.MusicInfo
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.JvmStatic
import kotlin.jvm.Volatile
import kotlin.math.abs
import kotlin.math.max

class OnlineMusicState private constructor() {
    private val lock = Any()
    private val stateListeners = RemoteCallbackList<IMusicStateListener>()
    private val infoListeners = RemoteCallbackList<IMusicInfoListener>()
    private val userInfoListeners = RemoteCallbackList<IMusicUserInfoListener>()
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var realInfo: MusicInfo = createEmptyInfo()
    private var realPlayState: Int = PLAY_STATE_STOP
    private var realPositionMs: Long = 0L
    private var overrideInfo: MusicInfo? = null
    private var overrideSessionPackage: String? = null
    private var overrideTracksSessionPackage: Boolean = false
    private var displayId: String = DEFAULT_DISPLAY_ID
    private var realIsFavor: Boolean = false

    private var lastLogMediaInfoKey: String? = null
    private var lastLogPlayStateKey: String? = null
    private var lastLogProgressMetaKey: String? = null
    private var lastLogProgressPos: Long = Long.MIN_VALUE
    private var lastLogSourceKey: String? = null
    private var lastLogPlayListKey: String? = null
    private var hasRealDataBeenPublished: Boolean = false
    private var initialized: Boolean = false

    @Volatile
    private var unknownArtistStub: Boolean = true

    fun initialize(context: Context?) {
        if (context == null) {
            return
        }
        synchronized(lock) {
            if (initialized) {
                return
            }
            initialized = true
            realInfo = createEmptyInfo()
            realIsFavor = false
            realPositionMs = 0L
            realPlayState = PLAY_STATE_STOP
            overrideInfo = null
            overrideSessionPackage = null
            overrideTracksSessionPackage = false
            displayId = DEFAULT_DISPLAY_ID
            AppLogger.log { "State: initialize first setup" }
        }
    }

    fun setSource(source: String?, displayId: String?) {
        if (!source.isNullOrEmpty() && SOURCE != source) return
        setDisplayId(displayId)
        requestSource()
    }

    fun setDisplayId(displayId: String?) {
        if (displayId.isNullOrEmpty()) return
        synchronized(lock) { this.displayId = displayId }
    }

    fun currentDisplayId() = synchronized(lock) { displayId }

    fun isMediaSessionToBinderForwardingEnabled(): Boolean = true

    fun setMediaSessionToBinderForwardingEnabled(enabled: Boolean) {
    }

    fun update(
        context: Context?,
        title: String?,
        artist: String?,
        album: String?,
        cover: String?,
        isPlaying: Boolean,
        progressMs: Long,
        durationMs: Long,
        forceMetadata: Boolean,
        actualPlayState: Int? = null,
        isFavor: Boolean? = null,
        forceProgress: Boolean = false
    ) {
        initialize(context)
        val trackId = buildId(title, normalizeMusicAuthor(artist), album)
        val favor = synchronized(lock) { isFavor ?: if (trackId == realInfo.id) realIsFavor else false }
        val info = createInfo(title, artist, album, cover, progressMs, durationMs, favor)
        val fallbackPlayState = if (isPlaying) PLAY_STATE_PLAY else PLAY_STATE_PAUSE
        val newPlayState: Int
        val infoChanged: Boolean
        val stateChanged: Boolean
        val progressChanged: Boolean
        val effectiveInfo: MusicInfo
        synchronized(lock) {
            val oldEffectiveInfo = effectiveInfoLocked()
            val oldPlayState = realPlayState
            newPlayState = if (overrideInfo != null && actualPlayState != null) actualPlayState else fallbackPlayState
            realInfo = info
            realIsFavor = info.isFavor
            realPositionMs = info.currentPlayTime
            realPlayState = newPlayState
            if (info.musicName.isNotEmpty() ||
                info.musicAuthor.isNotEmpty() ||
                info.albumName.isNotEmpty() ||
                info.totalPlayTime > 0L
            ) {
                hasRealDataBeenPublished = true
            }
            effectiveInfo = effectiveInfoLocked()
            infoChanged = infoKey(effectiveInfo) != infoKey(oldEffectiveInfo)
            stateChanged = newPlayState != oldPlayState
            progressChanged = forceProgress ||
                    infoChanged ||
                    abs(effectiveInfo.currentPlayTime - oldEffectiveInfo.currentPlayTime) >= MIN_PROGRESS_DELTA_MS ||
                    effectiveInfo.totalPlayTime != oldEffectiveInfo.totalPlayTime
        }

        if (infoChanged || forceMetadata) {
            publishMediaInfoAsync(effectiveInfo)
            publishSourceAsync()
            publishPlayListAsync()
        }
        if (stateChanged || infoChanged || forceMetadata) {
            publishPlayStateAsync(newPlayState)
        }
        if (progressChanged || stateChanged || infoChanged) {
            publishProgressAsync(effectiveInfo.currentPlayTime, effectiveInfo.totalPlayTime)
        }
        if (infoChanged || stateChanged) {
            AppLogger.log { "State: update title=${logTruncate(effectiveInfo.musicName)} artist=${logTruncate(effectiveInfo.musicAuthor)} playing=$isPlaying infoChanged=$infoChanged stateChanged=$stateChanged forceMetadata=$forceMetadata" }
        }
        if (BuildConfig.DEBUG && infoChanged) {
            MediaTransmissionLog.logSnapshotIfChanged(
                effectiveInfo.musicName,
                effectiveInfo.musicAuthor,
                effectiveInfo.albumName,
                effectiveInfo.musicPic.isNotBlank(),
                isPlayed = isPlaying,
                isFavor = effectiveInfo.isFavor,
            )
        }
    }

    fun updateOverride(
        context: Context?,
        title: String?,
        artist: String?,
        album: String?,
        cover: String?,
        progressMs: Long,
        durationMs: Long,
        forceMetadata: Boolean
    ) {
        initialize(context)
        val favor = synchronized(lock) { realIsFavor }
        val info = createInfo(title, artist, album, cover, progressMs, durationMs, favor)
        val infoChanged: Boolean
        val progressChanged: Boolean
        val effectiveInfo: MusicInfo
        val state: Int
        synchronized(lock) {
            val oldEffectiveInfo = effectiveInfoLocked()
            overrideTracksSessionPackage = true
            overrideSessionPackage = null
            val knownPackage = MediaSessionPipeline.get().lastKnownSessionPackage()
            if (knownPackage.isNotEmpty()) {
                overrideSessionPackage = knownPackage
            }
            overrideInfo = info
            effectiveInfo = effectiveInfoLocked()
            state = realPlayState
            infoChanged = infoKey(effectiveInfo) != infoKey(oldEffectiveInfo)
            progressChanged = infoChanged ||
                    abs(effectiveInfo.currentPlayTime - oldEffectiveInfo.currentPlayTime) >= MIN_PROGRESS_DELTA_MS ||
                    effectiveInfo.totalPlayTime != oldEffectiveInfo.totalPlayTime
        }

        if (infoChanged || forceMetadata) {
            publishMediaInfoAsync(effectiveInfo)
            publishSourceAsync()
            publishPlayListAsync()
        }
        if (infoChanged || forceMetadata) {
            publishPlayStateAsync(state)
        }
        if (progressChanged || infoChanged) {
            publishProgressAsync(effectiveInfo.currentPlayTime, effectiveInfo.totalPlayTime)
        }
        if (infoChanged) {
            AppLogger.log { "State: updateOverride title=${logTruncate(effectiveInfo.musicName)} artist=${logTruncate(effectiveInfo.musicAuthor)} infoChanged=$infoChanged forceMetadata=$forceMetadata" }
        }
        if (BuildConfig.DEBUG && infoChanged) {
            MediaTransmissionLog.logSnapshotIfChanged(
                effectiveInfo.musicName,
                effectiveInfo.musicAuthor,
                effectiveInfo.albumName,
                effectiveInfo.musicPic.isNotBlank(),
                isPlayed = state == PLAY_STATE_PLAY,
                isFavor = effectiveInfo.isFavor,
            )
        }
    }

    fun freezeDisplayOverride(context: Context?) {
        initialize(context)
        val effectiveInfo: MusicInfo
        val state: Int
        synchronized(lock) {
            overrideTracksSessionPackage = false
            overrideSessionPackage = null
            overrideInfo = effectiveInfoLocked()
            effectiveInfo = effectiveInfoLocked()
            state = realPlayState
        }
        publishMediaInfoAsync(effectiveInfo)
        publishPlayStateAsync(state)
        publishProgressAsync(effectiveInfo.currentPlayTime, effectiveInfo.totalPlayTime)
        publishSourceAsync()
        publishPlayListAsync()
    }

    fun clearDisplayOverrideIfSessionPackageChanged(context: Context?, packageName: String): Boolean {
        if (packageName.isEmpty()) {
            return false
        }
        val shouldClear = synchronized(lock) {
            if (overrideInfo == null || !overrideTracksSessionPackage) {
                return false
            }
            val anchor = overrideSessionPackage
            when {
                anchor == null -> {
                    overrideSessionPackage = packageName
                    false
                }
                anchor == packageName -> false
                else -> true
            }
        }
        if (!shouldClear) {
            return false
        }
        clearDisplayOverride(context)
        return true
    }

    fun clearDisplayOverride(context: Context?) {
        bumpExternalUpdateEpoch()
        initialize(context)
        val oldEffectiveInfo: MusicInfo
        val effectiveInfo: MusicInfo
        val state: Int
        val infoChanged: Boolean
        val progressChanged: Boolean
        synchronized(lock) {
            oldEffectiveInfo = effectiveInfoLocked()
            overrideInfo = null
            overrideSessionPackage = null
            overrideTracksSessionPackage = false
            effectiveInfo = effectiveInfoLocked()
            state = realPlayState
            infoChanged = infoKey(effectiveInfo) != infoKey(oldEffectiveInfo)
            progressChanged = infoChanged ||
                    abs(effectiveInfo.currentPlayTime - oldEffectiveInfo.currentPlayTime) >= MIN_PROGRESS_DELTA_MS ||
                    effectiveInfo.totalPlayTime != oldEffectiveInfo.totalPlayTime
        }
        if (infoChanged) {
            publishMediaInfoAsync(effectiveInfo)
            publishSourceAsync()
            publishPlayListAsync()
        }
        publishPlayStateAsync(state)
        if (progressChanged || infoChanged) {
            publishProgressAsync(effectiveInfo.currentPlayTime, effectiveInfo.totalPlayTime)
        }
        AppLogger.log { "State: clearDisplayOverride infoChanged=$infoChanged" }
    }

    fun clear(context: Context?) {
        initialize(context)
        val hasRealData = synchronized(lock) { hasRealDataBeenPublished }
        if (!hasRealData && synchronized(lock) { overrideInfo == null }) {
            synchronized(lock) {
                realInfo = createEmptyInfo()
                realIsFavor = false
                realPositionMs = 0L
                realPlayState = PLAY_STATE_STOP
            }
            return
        }
        val effectiveInfo: MusicInfo
        val infoChanged: Boolean
        val progressChanged: Boolean
        synchronized(lock) {
            val oldEffectiveInfo = effectiveInfoLocked()
            realInfo = createEmptyInfo()
            realIsFavor = false
            realPositionMs = 0L
            realPlayState = PLAY_STATE_STOP
            effectiveInfo = effectiveInfoLocked()
            infoChanged = infoKey(effectiveInfo) != infoKey(oldEffectiveInfo)
            progressChanged = infoChanged ||
                    abs(effectiveInfo.currentPlayTime - oldEffectiveInfo.currentPlayTime) >= MIN_PROGRESS_DELTA_MS ||
                    effectiveInfo.totalPlayTime != oldEffectiveInfo.totalPlayTime
        }
        AppLogger.log { "State: clear -> publish effective" }
        if (infoChanged) {
            publishMediaInfoAsync(effectiveInfo)
            publishPlayListAsync()
        }
        publishPlayStateAsync(PLAY_STATE_STOP)
        if (progressChanged || infoChanged) {
            publishProgressAsync(effectiveInfo.currentPlayTime, effectiveInfo.totalPlayTime)
        }
        if (BuildConfig.DEBUG && infoChanged) {
            MediaTransmissionLog.logSnapshotIfChanged(
                effectiveInfo.musicName,
                effectiveInfo.musicAuthor,
                effectiveInfo.albumName,
                effectiveInfo.musicPic.isNotBlank(),
                isPlayed = false,
                isFavor = effectiveInfo.isFavor,
            )
        }
    }

    fun setFavor(isFavor: Boolean) {
        val effectiveInfo: MusicInfo
        synchronized(lock) {
            realIsFavor = isFavor
            realInfo.isFavor = isFavor
            overrideInfo?.isFavor = isFavor
            effectiveInfo = effectiveInfoLocked()
        }
        publishMediaInfoAsync(effectiveInfo)
        publishFavorStateAsync(isFavor, effectiveInfo.id)
        publishPlayListAsync()
        AppLogger.log { "State: setFavor isFavor=$isFavor id=${logTruncate(effectiveInfo.id)}" }
    }

    fun currentInfo() = synchronized(lock) { effectiveInfoLocked() }

    fun currentPlayState() = synchronized(lock) { realPlayState }

    fun currentPosition() = synchronized(lock) { effectiveInfoLocked().currentPlayTime }

    fun commonSources() = Collections.singletonList(SOURCE)

    fun currentList(): ArrayList<MusicInfo> {
        val list = ArrayList<MusicInfo>()
        list.add(currentInfo())
        return list
    }

    fun register(
        stateListener: IMusicStateListener?,
        infoListener: IMusicInfoListener?,
        userInfoListener: IMusicUserInfoListener?
    ) {
        if (stateListener != null) {
            stateListeners.register(stateListener)
        }
        if (infoListener != null) {
            infoListeners.register(infoListener)
        }
        if (userInfoListener != null) {
            userInfoListeners.register(userInfoListener)
        }
        AppLogger.log { "State: register stateN=${listenerCount(stateListeners)} infoN=${listenerCount(infoListeners)} userN=${listenerCount(userInfoListeners)} addedState=${stateListener != null} addedInfo=${infoListener != null} addedUser=${userInfoListener != null}" }
        pushCurrentToAsync(stateListener, infoListener)
    }

    fun pushCurrentMediaInfo() {
        if (synchronized(lock) { !hasRealDataBeenPublished && overrideInfo == null }) {
            return
        }
        val info = currentInfo()
        publishMediaInfoAsync(info)
        publishPlayStateAsync(currentPlayState())
        publishProgressAsync(currentPosition(), info.totalPlayTime)
        publishSourceAsync()
        publishPlayListAsync()
    }

    fun requestSource() {
        publishSourceAsync()
        pushCurrentMediaInfo()
    }

    private fun pushCurrentToAsync(stateListener: IMusicStateListener?, infoListener: IMusicInfoListener?) {
        val info = currentInfo()
        val state = currentPlayState()
        val position = currentPosition()
        val displayId = currentDisplayId()
        val list = currentList()
        AppLogger.log { "State: pushCurrent snapshot title=${logTruncate(info.musicName)} artist=${logTruncate(info.musicAuthor)} playState=$state displayId=$displayId pos=${position}ms hasState=${stateListener != null} hasInfo=${infoListener != null} listSize=${list.size}" }
        callbackExecutor.execute {
            if (infoListener != null) {
                try {
                    infoListener.onMusicListResult(list, SOURCE, displayId)
                    infoListener.onMusicCurrentMediaInfoResult(info, SOURCE, displayId)
                } catch (ignored: RemoteException) {
                }
            }
            if (stateListener != null) {
                try {
                    stateListener.onSourceChange(SOURCE, displayId)
                    stateListener.onPlayStateChanged(state, SOURCE, displayId)
                    stateListener.onProgressChanged(position, info.totalPlayTime, SOURCE, displayId)
                    stateListener.onPlayListChanged(SOURCE, displayId)
                    stateListener.onFavorStateChanged(info.isFavor, SOURCE, info.id, displayId)
                } catch (ignored: RemoteException) {
                }
            }
        }
    }

    private fun publishMediaInfoAsync(info: MusicInfo) {
        val copy = copy(info)
        val displayId = currentDisplayId()
        callbackExecutor.execute { publishMediaInfo(copy, displayId) }
    }

    private fun publishPlayStateAsync(state: Int) {
        val displayId = currentDisplayId()
        callbackExecutor.execute { publishPlayState(state, displayId) }
    }

    private fun publishProgressAsync(position: Long, duration: Long) {
        val displayId = currentDisplayId()
        callbackExecutor.execute { publishProgress(position, duration, displayId) }
    }

    private fun publishSourceAsync() {
        val displayId = currentDisplayId()
        callbackExecutor.execute { publishSource(displayId) }
    }

    private fun publishPlayListAsync() {
        val displayId = currentDisplayId()
        callbackExecutor.execute { publishPlayList(displayId) }
    }

    private fun publishFavorStateAsync(isFavor: Boolean, id: String) {
        val displayId = currentDisplayId()
        callbackExecutor.execute { publishFavorState(isFavor, id, displayId) }
    }

    private fun publishMediaInfo(info: MusicInfo, displayId: String) {
        val count = infoListeners.beginBroadcast()
        var infoFail = 0
        try {
            for (i in 0 until count) {
                try {
                    infoListeners.getBroadcastItem(i).onMusicCurrentMediaInfoResult(info, SOURCE, displayId)
                } catch (_: RemoteException) {
                    infoFail++
                }
            }
        } finally {
            infoListeners.finishBroadcast()
        }

        val stateCount = stateListeners.beginBroadcast()
        var stateFail = 0
        try {
            for (i in 0 until stateCount) {
                try {
                    stateListeners.getBroadcastItem(i).onMediaChange(info, SOURCE, displayId)
                    stateListeners.getBroadcastItem(i).onMediaDataChanged()
                } catch (_: RemoteException) {
                    stateFail++
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
        val dedupKey = "${valueOrEmpty(info.musicName)}|${valueOrEmpty(info.musicAuthor)}|${valueOrEmpty(info.albumName)}|${info.totalPlayTime}|$displayId|$count|$infoFail|$stateCount|$stateFail"
        if (dedupKey != lastLogMediaInfoKey) {
            lastLogMediaInfoKey = dedupKey
            AppLogger.log { "State: pushMediaInfo infoN=$count infoFail=$infoFail stateN=$stateCount stateFail=$stateFail displayId=$displayId title=${logTruncate(info.musicName)}" }
        }
    }

    private fun publishPlayState(state: Int, displayId: String) {
        val count = stateListeners.beginBroadcast()
        var failures = 0
        try {
            for (i in 0 until count) {
                try {
                    stateListeners.getBroadcastItem(i).onPlayStateChanged(state, SOURCE, displayId)
                } catch (_: RemoteException) {
                    failures++
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
        val dedupKey = "$state|$displayId|$count|$failures"
        if (dedupKey != lastLogPlayStateKey) {
            lastLogPlayStateKey = dedupKey
            AppLogger.log { "State: pushPlayState state=$state n=$count fail=$failures displayId=$displayId" }
        }
    }

    private fun shouldLogProgress(position: Long, duration: Long, displayId: String, n: Int, fail: Int): Boolean {
        val metaKey = "$duration|$displayId|$n|$fail"
        if (metaKey != lastLogProgressMetaKey) {
            lastLogProgressMetaKey = metaKey
            lastLogProgressPos = position
            return true
        }
        if (position < 500L && (lastLogProgressPos == Long.MIN_VALUE || lastLogProgressPos >= 1000L)) {
            lastLogProgressPos = position
            return true
        }
        if (lastLogProgressPos == Long.MIN_VALUE) {
            lastLogProgressPos = position
            return true
        }
        if (abs(position - lastLogProgressPos) >= PROGRESS_LOG_DELTA_MS) {
            lastLogProgressPos = position
            return true
        }
        return false
    }

    private fun publishProgress(position: Long, duration: Long, displayId: String) {
        val count = stateListeners.beginBroadcast()
        var failures = 0
        try {
            for (i in 0 until count) {
                try {
                    stateListeners.getBroadcastItem(i).onProgressChanged(position, duration, SOURCE, displayId)
                } catch (_: RemoteException) {
                    failures++
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
        if (shouldLogProgress(position, duration, displayId, count, failures)) {
            AppLogger.log { "State: pushProgress pos=$position dur=$duration n=$count fail=$failures displayId=$displayId" }
        }
    }

    private fun publishFavorState(isFavor: Boolean, id: String, displayId: String) {
        val count = stateListeners.beginBroadcast()
        var failures = 0
        try {
            for (i in 0 until count) {
                try {
                    stateListeners.getBroadcastItem(i).onFavorStateChanged(isFavor, SOURCE, id, displayId)
                } catch (_: RemoteException) {
                    failures++
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
        AppLogger.log { "State: pushFavor isFavor=$isFavor id=${logTruncate(id)} n=$count fail=$failures displayId=$displayId" }
    }

    private fun publishSource(displayId: String) {
        val count = stateListeners.beginBroadcast()
        var failures = 0
        try {
            for (i in 0 until count) {
                try {
                    stateListeners.getBroadcastItem(i).onSourceChange(SOURCE, displayId)
                } catch (_: RemoteException) {
                    failures++
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
        val dedupKey = "$displayId|$count|$failures"
        if (dedupKey != lastLogSourceKey) {
            lastLogSourceKey = dedupKey
            AppLogger.log { "State: pushSource n=$count fail=$failures displayId=$displayId" }
        }
    }

    private fun publishPlayList(displayId: String) {
        val list = currentList()
        val infoCount = infoListeners.beginBroadcast()
        var infoFail = 0
        try {
            for (i in 0 until infoCount) {
                try {
                    infoListeners.getBroadcastItem(i).onMusicListResult(list, SOURCE, displayId)
                } catch (_: RemoteException) {
                    infoFail++
                }
            }
        } finally {
            infoListeners.finishBroadcast()
        }

        val stateCount = stateListeners.beginBroadcast()
        var stateFail = 0
        try {
            for (i in 0 until stateCount) {
                try {
                    stateListeners.getBroadcastItem(i).onPlayListChanged(SOURCE, displayId)
                } catch (_: RemoteException) {
                    stateFail++
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
        val dedupKey = "${list.size}|$displayId|$infoCount|$infoFail|$stateCount|$stateFail"
        if (dedupKey != lastLogPlayListKey) {
            lastLogPlayListKey = dedupKey
            AppLogger.log { "State: pushPlayList infoN=$infoCount infoFail=$infoFail stateN=$stateCount stateFail=$stateFail displayId=$displayId items=${list.size}" }
        }
    }

    private fun createInfo(
        title: String?,
        artist: String?,
        album: String?,
        cover: String?,
        progressMs: Long,
        durationMs: Long,
        isFavor: Boolean
    ): MusicInfo {
        return MusicInfo().apply {
            musicName = valueOrEmpty(title)
            musicAuthor = normalizeMusicAuthor(artist)
            albumName = valueOrEmpty(album)
            musicPic = valueOrEmpty(cover)
            id = buildId(musicName, musicAuthor, albumName)
            mediaType = "music"
            unplayCode = ""
            totalPlayTime = max(durationMs, 0L)
            currentPlayTime = max(progressMs, 0L)
            hasProgress = totalPlayTime > 0L
            this.isFavor = isFavor
            isFavorAble = OnlineMusicSettingsProvider.currentIsFavorAble()
            playMode = 0
        }
    }

    private fun effectiveInfoLocked(): MusicInfo {
        return copy(overrideInfo ?: realInfo)
    }

    private fun normalizeMusicAuthor(artist: String?): String {
        if (!unknownArtistStub) return valueOrEmpty(artist)
        val trimmed = (artist ?: "").trim()
        if (trimmed.isEmpty() || trimmed.lowercase(Locale.ROOT) in EMPTY_SUBTITLES) {
            return "\u00A0"
        }
        return valueOrEmpty(artist)
    }

    private fun logTruncate(text: String?, maxLen: Int = 48): String {
        val s = text ?: ""
        return if (s.length <= maxLen) s else s.take(maxLen) + "…"
    }

    companion object {
        @JvmStatic
        var SOURCE: String
            get() = OnlineMusicSettingsProvider.currentSource()
            internal set(value) {
                OnlineMusicSettingsProvider.setSource(value)
            }

        private const val DEFAULT_DISPLAY_ID: String = "0"
        const val PLAY_STATE_STOP: Int = 0
        const val PLAY_STATE_PLAY: Int = 1
        const val PLAY_STATE_PAUSE: Int = 2

        private const val MIN_PROGRESS_DELTA_MS: Long = 700L
        private const val PROGRESS_LOG_DELTA_MS: Long = 5000L
        private val EMPTY_SUBTITLES = setOf(
            "unknown artist",
            "неизвестный исполнитель"
        )
        private val INSTANCE = OnlineMusicState()
        private val externalUpdateEpoch = AtomicLong(0L)

        @JvmStatic
        fun get(): OnlineMusicState = INSTANCE

        @JvmStatic
        fun bumpExternalUpdateEpoch(): Long = externalUpdateEpoch.incrementAndGet()

        @JvmStatic
        fun isExternalUpdateEpoch(value: Long): Boolean = value == externalUpdateEpoch.get()

        private fun createEmptyInfo(): MusicInfo {
            return MusicInfo().apply {
                musicName = ""
                musicAuthor = ""
                albumName = ""
                musicPic = ""
                id = ""
                mediaType = "music"
                unplayCode = ""
                totalPlayTime = 0L
                currentPlayTime = 0L
                hasProgress = false
                isFavor = false
                isFavorAble = OnlineMusicSettingsProvider.currentIsFavorAble()
                playMode = 0
            }
        }

        private fun copy(source: MusicInfo): MusicInfo {
            return MusicInfo().apply {
                musicName = valueOrEmpty(source.musicName)
                musicPic = valueOrEmpty(source.musicPic)
                musicAuthor = valueOrEmpty(source.musicAuthor)
                totalPlayTime = source.totalPlayTime
                currentPlayTime = source.currentPlayTime
                unplayCode = valueOrEmpty(source.unplayCode)
                id = valueOrEmpty(source.id)
                isFavorAble = source.isFavorAble
                isFavor = source.isFavor
                hasProgress = source.hasProgress
                albumName = valueOrEmpty(source.albumName)
                playMode = source.playMode
                mediaType = valueOrEmpty(source.mediaType)
            }
        }

        private fun valueOrEmpty(value: String?): String {
            return value ?: ""
        }

        private fun buildId(title: String?, artist: String?, album: String?): String {
            return valueOrEmpty(title) + "|" + valueOrEmpty(artist) + "|" + valueOrEmpty(album)
        }

        private fun infoKey(info: MusicInfo): String {
            return valueOrEmpty(info.musicName) + "|" + valueOrEmpty(info.musicAuthor) + "|" + valueOrEmpty(info.albumName) + "|" + valueOrEmpty(info.musicPic) + "|" + info.totalPlayTime + "|" + info.isFavorAble + "|" + info.isFavor
        }

        private fun listenerCount(list: RemoteCallbackList<*>): Int {
            val n = list.beginBroadcast()
            list.finishBroadcast()
            return n
        }
    }
}
