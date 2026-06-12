package com.geely.online.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

object OnlineMusicSettingsProvider {
    private val lock = Any()
    private val started = AtomicBoolean(false)

    @Volatile
    private var dataStore: DataStore<Preferences>? = null

    @Volatile
    private var sourceInitialized: Boolean = false

    @Volatile
    private var isFavorAbleInitialized: Boolean = false

    @Volatile
    private var likeModeInitialized: Boolean = false

    @Volatile
    private var source: String = DEFAULT_SOURCE

    @Volatile
    private var isFavorAble: Boolean = DEFAULT_IS_FAVOR_ABLE

    @Volatile
    private var likeMode: Int = DEFAULT_LIKE_MODE

    fun start(dataStore: DataStore<Preferences>, scope: CoroutineScope) {
        this.dataStore = dataStore
        if (!started.compareAndSet(false, true)) {
            return
        }
        scope.launch(Dispatchers.IO) {
            dataStore.data
                .map { prefs ->
                    OnlineMusicSettingsState(
                        source = resolveSourceKey(prefs[AppSettingKeys.DISPLAY_AS_APP_SOURCE_KEY]),
                        isFavorAble = prefs[AppSettingKeys.DISPLAY_LIKE_INSTEAD_OF_PREVIOUS]
                            ?: DEFAULT_IS_FAVOR_ABLE,
                        likeMode = prefs[AppSettingKeys.LIKE_MODE] ?: DEFAULT_LIKE_MODE
                    )
                }
                .distinctUntilChanged()
                .collect { setState(it) }
        }
    }

    fun currentSource(): String {
        val cached = source
        if (sourceInitialized) {
            return cached
        }
        return loadStateSync().source
    }

    fun currentIsFavorAble(): Boolean {
        val cached = isFavorAble
        if (isFavorAbleInitialized) {
            return cached
        }
        return loadStateSync().isFavorAble
    }

    fun currentLikeMode(): Int {
        val cached = likeMode
        if (likeModeInitialized) {
            return cached
        }
        return loadStateSync().likeMode
    }

    fun setSource(value: String?) {
        val resolved = resolveSourceKey(value)
        synchronized(lock) {
            source = resolved
            sourceInitialized = true
        }
    }

    private fun setState(value: OnlineMusicSettingsState) {
        synchronized(lock) {
            source = value.source
            isFavorAble = value.isFavorAble
            likeMode = value.likeMode
            sourceInitialized = true
            isFavorAbleInitialized = true
            likeModeInitialized = true
        }
    }

    private fun loadStateSync(): OnlineMusicSettingsState {
        synchronized(lock) {
            if (sourceInitialized && isFavorAbleInitialized && likeModeInitialized) {
                return OnlineMusicSettingsState(source, isFavorAble, likeMode)
            }
        }
        val store = dataStore
        val loaded = if (store == null) {
            null
        } else {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
                        val prefs = store.data.first()
                        OnlineMusicSettingsState(
                            source = resolveSourceKey(prefs[AppSettingKeys.DISPLAY_AS_APP_SOURCE_KEY]),
                            isFavorAble = prefs[AppSettingKeys.DISPLAY_LIKE_INSTEAD_OF_PREVIOUS]
                                ?: DEFAULT_IS_FAVOR_ABLE,
                            likeMode = prefs[AppSettingKeys.LIKE_MODE] ?: DEFAULT_LIKE_MODE
                        )
                    }
                }
            }.getOrNull()
        }
        val resolved = loaded ?: OnlineMusicSettingsState(
            DEFAULT_SOURCE,
            DEFAULT_IS_FAVOR_ABLE,
            DEFAULT_LIKE_MODE
        )
        synchronized(lock) {
            if (!sourceInitialized) {
                source = resolved.source
                sourceInitialized = true
            }
            if (!isFavorAbleInitialized) {
                isFavorAble = resolved.isFavorAble
                isFavorAbleInitialized = true
            }
            if (!likeModeInitialized) {
                likeMode = resolved.likeMode
                likeModeInitialized = true
            }
            return OnlineMusicSettingsState(source, isFavorAble, likeMode)
        }
    }

    private fun resolveSourceKey(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) {
            return DEFAULT_SOURCE
        }
        return when (raw) {
            "flow",
            "net_easy_cloud",
            "kugou",
            "kuwo",
            "fandeng",
            "jinri",
            "sohu",
            "xmly",
            "aiqiyi",
            "G-C",
            "tencent_video",
            "bilibili",
            "huoshan",
            "thunder_voice",
            "cmvideo" -> raw

            else -> DEFAULT_SOURCE
        }
    }

    private data class OnlineMusicSettingsState(
        val source: String,
        val isFavorAble: Boolean,
        val likeMode: Int
    )

    private const val DEFAULT_SOURCE = "flow"
    private const val DEFAULT_IS_FAVOR_ABLE = false
    private const val DEFAULT_LIKE_MODE = 0
    private const val SETTINGS_READ_TIMEOUT_MS = 5000L
}
