package com.geely.online.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.geely.online.music.IMusicInfoListener
import com.geely.online.music.IMusicManager
import com.geely.online.music.IMusicQueryCallback
import com.geely.online.music.IMusicStateListener
import com.geely.online.music.IMusicUserInfoListener
import com.geely.online.music.bean.MusicInfo
import com.tencent.wecarflow.control.data.LoginUserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class OnlineMusicService : Service() {
    private val state = OnlineMusicState.get()

    private val pollCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pollHeartbeatActive = false

    private val pollHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!pollHeartbeatActive) return
            val parts = ArrayList<String>()
            for ((name, counter) in pollCounters.entries.sortedBy { it.key }) {
                val c = counter.getAndSet(0)
                if (c > 0) parts.add("$name=$c")
            }
            if (parts.isNotEmpty()) {
                AppLogger.log {
                    "Binder: poll heartbeat window=${POLL_HEARTBEAT_MS}ms " + parts.joinToString(" ")
                }
            }
            mainHandler.postDelayed(this, POLL_HEARTBEAT_MS)
        }
    }

    private fun recordPoll(method: String) {
        pollCounters.getOrPut(method) { AtomicInteger() }.incrementAndGet()
    }

    private val binder = object : IMusicManager.Stub() {

        override fun activeLastSource(autoPlay: Boolean) {
            AppLogger.log { "Binder: activeLastSource autoPlay=$autoPlay" }
        }

        override fun addFavor(source: String?) {
            when (OnlineMusicSettingsProvider.currentLikeMode().toLikeMode) {
                LikeMode.CURRENT_PLAYER -> startApp()

                LikeMode.VENDOR_RATING -> MediaSessionPipeline.get().setFavorite(true)

                LikeMode.SEND_INTENT -> sendLikePressBroadcast()
            }
        }

        override fun cancelFavor(source: String?) {
            when (OnlineMusicSettingsProvider.currentLikeMode().toLikeMode) {
                LikeMode.CURRENT_PLAYER -> startApp()

                LikeMode.VENDOR_RATING -> MediaSessionPipeline.get().setFavorite(false)

                LikeMode.SEND_INTENT -> sendLikePressBroadcast()
            }
        }

        override fun addListener(
            stateListener: IMusicStateListener?,
            infoListener: IMusicInfoListener?,
            userInfoListener: IMusicUserInfoListener?
        ) {
            AppLogger.log { "Binder: addListener state=${stateListener != null} info=${infoListener != null} user=${userInfoListener != null}" }
            state.register(stateListener, infoListener, userInfoListener)
        }

        override fun closeLikeList() {
        }

        override fun doPlay(position: Int, musicInfo: MusicInfo?) {
        }

        override fun fastForward(time: Long) {
        }

        override fun fastRewind(time: Long) {
        }

        override fun getCommonUseSource(source: String?): MutableList<Any?> {
            recordPoll("getCommonUseSource")
            val result = ArrayList<Any?>()
            result.addAll(state.commonSources())
            return result
        }

        override fun getContent(contentId: String?, listener: IMusicStateListener?) {
        }

        override fun getCurrentMediaInfo(source: String?) {
            recordPoll("getCurrentMediaInfo")
            state.pushCurrentMediaInfo()
        }

        override fun getCurrentPlayState(source: String?): Int {
            recordPoll("getCurrentPlayState")
            return state.currentPlayState()
        }

        override fun getCurrentPosition(): Long {
            recordPoll("getCurrentPosition")
            return state.currentPosition()
        }

        override fun getFavor() {
        }

        override fun getOnlineUserInfo(listener: IMusicUserInfoListener?) {
            recordPoll("getOnlineUserInfo")
            if (listener == null) return
            try {
                val userInfo = LoginUserInfo()
                userInfo.setLogin(true)
                userInfo.setExpired(false)
                userInfo.setNickName("SALAT")
                userInfo.setAvatarUrl("")
                listener.onUserInfoResult(userInfo)
            } catch (_: RemoteException) {
            }
        }

        override fun getPlayList(listener: IMusicInfoListener?) {
            recordPoll("getPlayList")
            if (listener == null) return
            try {
                listener.onMusicListResult(
                    state.currentList(),
                    OnlineMusicState.SOURCE,
                    state.currentDisplayId()
                )
            } catch (_: RemoteException) {
            }
        }

        override fun getPlayMode(source: String?): Int {
            recordPoll("getPlayMode")
            return 0
        }

        override fun getSource(extra: String?): String {
            recordPoll("getSource")
            state.setDisplayId(extra)
            return OnlineMusicState.SOURCE
        }

        override fun isAgreedUserProtocol(): Boolean {
            recordPoll("isAgreedUserProtocol")
            return true
        }

        override fun isMusicQualitySwitch(quality: Int): Boolean {
            recordPoll("isMusicQualitySwitch")
            return false
        }

        override fun isSupportChangeMode(source: Int): Int {
            recordPoll("isSupportChangeMode")
            return 0
        }

        override fun isUserMusicVip(): Boolean {
            recordPoll("isUserMusicVip")
            return false
        }

        override fun isVipQuality(quality: Int): Boolean {
            recordPoll("isVipQuality")
            return false
        }

        override fun next() {
            AppLogger.log { "Binder: next" }
            sendControl(COMMAND_NEXT)
        }

        override fun notifyVrStatusNotifierStatus(status: Int): Int {
            recordPoll("notifyVrStatusNotifierStatus")
            return 0
        }

        override fun onUIWordingTriggered(wording: String?): Int {
            recordPoll("onUIWordingTriggered")
            return 0
        }

        override fun openChannelTab(tab: String?) {
        }

        override fun openFavor() {
        }

        override fun openHistory() {
        }

        override fun openHistoryList(type: Int, autoPlay: Boolean) {
        }

        override fun openLoginPage() {
        }

        override fun openLyric() {
        }

        override fun openPlayList() {
        }

        override fun pause() {
            AppLogger.log { "Binder: pause" }
            sendControl(COMMAND_PAUSE)
        }

        override fun play() {
            AppLogger.log { "Binder: play" }
            sendControl(COMMAND_PLAY)
        }

        override fun playContent(type: Int, id: String?, position: Int, autoPlay: Boolean) {
        }

        override fun playFavor() {
        }

        override fun playMusicByNameAndPosition(name: String?, position: Int) {
        }

        override fun pre() {
            AppLogger.log { "Binder: pre" }
            sendControl(COMMAND_PREVIOUS)
        }

        override fun queryMediaInfoSync(): MusicInfo {
            recordPoll("queryMediaInfoSync")
            return state.currentInfo()
        }

        override fun replayCurrent() {
        }

        override fun requestSource() {
            recordPoll("requestSource")
        }

        override fun screenChange(screenState: Int) {
            recordPoll("screenChange")
        }

        override fun searchAndPlayMusicFromFlow(keyword: String?) {
        }

        override fun searchMusicByName(keyword: String?, listener: IMusicStateListener?) {
        }

        override fun seekTo(position: Long) {
            AppLogger.log { "Binder: seekTo position=$position" }
            sendControl(COMMAND_SEEK_TO, position)
        }

        override fun semanticSearch(
            keyword: String?,
            artist: String?,
            album: String?,
            extra: String?
        ) {
        }

        override fun semanticSearchAndPlay(
            keyword: String?,
            words: MutableList<Any?>?,
            extra: String?,
            autoPlay: Boolean,
            fromVr: Boolean,
            scene: String?,
            callback: IMusicQueryCallback?
        ) {
        }

        override fun semanticSearchV2(keyword: String?, callback: IMusicQueryCallback?) {
        }

        override fun setPlayMode(playMode: Int) {
        }

        override fun setSource(source: String?, extra: String?) {
            AppLogger.log { "Binder: setSource source=$source extra=$extra" }
            if (!isFlowSource(source)) return
            state.setSource(source, extra)
        }

        override fun setSourceAndPlay(source: String?, autoPlay: Boolean, extra: String?) {
            AppLogger.log { "Binder: setSourceAndPlay source=$source autoPlay=$autoPlay extra=$extra" }
            if (!isFlowSource(source)) return
            state.setSource(source, extra)
            if (autoPlay) {
                sendControl(COMMAND_PLAY)
            }
        }

        override fun startApp() {
            AppLogger.log { "Binder: startApp" }
            MediaSessionPipeline.get().startLastMediaSessionApp()
        }

        override fun getAppAudioStatus(source: String?): String {
            AppLogger.log { "Binder: getAppAudioStatus source=$source result=$APP_AUDIO_STATUS_FLOW" }
            return APP_AUDIO_STATUS_FLOW
        }

        override fun setFlowAudioStatus(active: Boolean) {
            AppLogger.log { "Binder: setFlowAudioStatus active=$active" }
            if (active) {
                state.pushCurrentMediaInfo()
            }
        }

        override fun supportLastSource(): Boolean {
            recordPoll("supportLastSource")
            return false
        }

        override fun switchSourceQuality(quality: Int) {
        }

        // Treats an empty source as the current binder source, matching system calls that omit it
        private fun isFlowSource(source: String?) =
            source.isNullOrEmpty() || source == OnlineMusicState.SOURCE

        private fun sendLikePressBroadcast() = runCatching {
            val intent = Intent().apply { action = "com.salat.gmproxy.LIKE" }
            sendBroadcast(intent)
        }
    }

    private fun sendControl(command: String) {
        when (command) {
            COMMAND_PLAY -> MediaSessionPipeline.get().play()
            COMMAND_PAUSE -> MediaSessionPipeline.get().pause()
            COMMAND_NEXT -> MediaSessionPipeline.get().next()
            COMMAND_PREVIOUS -> MediaSessionPipeline.get().previous()
        }
    }

    private fun sendControl(command: String, positionMs: Long) {
        if (command == COMMAND_SEEK_TO) {
            MediaSessionPipeline.get().seekTo(positionMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.log { "Binder: OnlineMusicService onCreate" }
        state.initialize(this)
        MediaSessionPipeline.get().start(this)
        pollHeartbeatActive = true
        mainHandler.postDelayed(pollHeartbeatRunnable, POLL_HEARTBEAT_MS)
    }

    override fun onDestroy() {
        pollHeartbeatActive = false
        mainHandler.removeCallbacks(pollHeartbeatRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        AppLogger.log { "Binder: client connected onBind" }
        state.initialize(this)
        MediaSessionPipeline.get().start(this)
        return binder
    }

    companion object {
        private const val COMMAND_PLAY: String = "play"
        private const val COMMAND_PAUSE: String = "pause"
        private const val COMMAND_NEXT: String = "next"
        private const val COMMAND_PREVIOUS: String = "previous"
        private const val COMMAND_SEEK_TO: String = "seekTo"
        private const val APP_AUDIO_STATUS_FLOW: String = "3"
        private const val POLL_HEARTBEAT_MS: Long = 2000L
    }
}
