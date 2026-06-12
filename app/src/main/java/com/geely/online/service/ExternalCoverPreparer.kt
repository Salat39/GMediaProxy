package com.geely.online.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object ExternalCoverPreparer {
    private val lock = Any()
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    fun prepare(context: Context, cover: String?, onReady: (String?) -> Unit) {
        if (cover.isNullOrBlank()) {
            onReady(cover)
            return
        }

        val uri = parseCoverUri(cover)
        if (isExternalStorageCover(cover, uri)) {
            val handler = ensureHandler()
            val posted = handler.post {
                val prepared = runCatching {
                    val normalized = normalizeStorageCoverUri(cover)
                    val path = Uri.parse(normalized).path
                    if (path != null && File(path).exists()) normalized else null
                }.getOrNull()
                onReady(prepared)
            }
            if (!posted) {
                onReady(null)
            }
            return
        }

        if (uri == null || !isSupportedLocalUri(uri) || isAlreadyPrepared(uri)) {
            onReady(cover)
            return
        }

        val appContext = context.applicationContext
        val handler = ensureHandler()
        val posted = handler.post {
            val prepared = runCatching { prepareInternal(appContext, cover, uri) }
                .onFailure { e -> AppLogger.log { "ExternalCover: prepare error ${e.message}" } }
                .getOrNull()
            onReady(prepared ?: cover)
        }
        if (!posted) {
            onReady(cover)
        }
    }

    private fun ensureHandler(): Handler {
        synchronized(lock) {
            val existingHandler = workerHandler
            if (existingHandler != null) {
                return existingHandler
            }
            val thread = HandlerThread("ExternalCoverPreparer").also { it.start() }
            val handler = Handler(thread.looper)
            workerThread = thread
            workerHandler = handler
            return handler
        }
    }

    private fun prepareInternal(context: Context, cover: String, uri: Uri): String? {
        val cacheKey = buildCacheKey(context, uri, cover)
        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            TEMP_FOLDER
        ).apply { mkdirs() }
        val outputFile = File(outputDir, "$cacheKey$FILE_TYPE")

        if (isReadableImageFile(outputFile)) {
            return Uri.fromFile(outputFile).toString()
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        clearOldFiles(outputDir)

        return if (isReadableImageFile(outputFile)) Uri.fromFile(outputFile).toString() else null
    }

    private fun parseCoverUri(cover: String): Uri? {
        return runCatching {
            if (cover.startsWith("/")) Uri.fromFile(File(cover)) else Uri.parse(cover)
        }.getOrNull()
    }

    private fun isExternalStorageCover(cover: String, uri: Uri?): Boolean {
        if (cover.startsWith("/storage/")) return true
        if (uri?.scheme == "file") {
            return uri.path?.startsWith("/storage/") == true
        }
        return false
    }

    private fun normalizeStorageCoverUri(cover: String): String {
        return if (cover.startsWith("/storage/")) {
            Uri.fromFile(File(cover)).toString()
        } else {
            cover
        }
    }

    private fun isSupportedLocalUri(uri: Uri): Boolean {
        return uri.scheme == null || uri.scheme == "file" || uri.scheme == "content"
    }

    private fun isAlreadyPrepared(uri: Uri): Boolean {
        if (uri.scheme != "file" && uri.scheme != null) return false
        val path = uri.path ?: return false
        return path.contains("/$TEMP_FOLDER/")
    }

    private fun buildCacheKey(context: Context, uri: Uri, cover: String): String {
        val file = uri.path?.let { path -> File(path) }?.takeIf { uri.scheme == null || uri.scheme == "file" }
        val source = if (file != null && file.exists()) {
            "$cover:${file.lastModified()}:${file.length()}"
        } else {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            "$cover:$type:$size"
        }
        return source.md5()
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > MAX_IMAGE_SIDE || height / sampleSize > MAX_IMAGE_SIDE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun isReadableImageFile(file: File): Boolean {
        return file.exists() && file.isFile && file.canRead() && file.length() > 0L
    }

    private fun clearOldFiles(dir: File) {
        val files = dir.listFiles { file -> file.isFile }
            ?.sortedBy { it.lastModified() }
            ?: return
        if (files.size > CACHE_FILE_BUFFER) {
            files.take(files.size - CACHE_FILE_BUFFER).forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private fun String.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val TEMP_FOLDER = ".gmp_temp"
    private const val FILE_TYPE = ".jpg"
    private const val JPEG_QUALITY = 90
    private const val CACHE_FILE_BUFFER = 7
    private const val MAX_IMAGE_SIDE = 1600
}
