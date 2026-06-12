package com.geely.online.service

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_ENTRIES = 1_200
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES + 1)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var enabled: Boolean = false

    @PublishedApi
    internal fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            synchronized(lock) {
                entries.clear()
            }
        }
    }

    inline fun log(message: () -> String) {
        if (!isEnabled()) return
        appendLogLine(message())
    }

    @PublishedApi
    internal fun appendLogLine(text: String) {
        synchronized(lock) {
            val line = "${timeFormat.format(Date())} $text"
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    fun snapshot(): List<String> {
        if (!isEnabled()) return emptyList()
        synchronized(lock) { return entries.toList() }
    }
}
