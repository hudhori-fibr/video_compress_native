package com.hudhodev.video_compress_native

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.TransformationRequest
import java.io.File
import java.util.concurrent.Executors // Import tambahan

@OptIn(UnstableApi::class)
class VideoProcessor {
    private var transformer: Transformer? = null
    private var handlerThread: HandlerThread? = null
    private val progressHandler = Handler(Looper.getMainLooper()) // Handler untuk update UI dari thread lain
    private var progressRunnable: Runnable? = null // Runnable untuk memantau progres

    fun processVideo(
        context: Context,
        sourcePath: String,
        destPath: String,
        startTimeMs: Long,
        endTimeMs: Long,
        targetHeight: Int?,
        progressCallback: (Int) -> Unit,
        completionCallback: (Result<String>) -> Unit
    ) {
        cancel() // Pastikan operasi sebelumnya dibatalkan

        handlerThread = HandlerThread("VideoProcessorThread")
        handlerThread?.start()
        val looper = handlerThread!!.looper // Dapatkan looper dari thread

        val mediaItem = MediaItem.Builder()
            .setUri(sourcePath)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startTimeMs)
                    .setEndPositionMs(endTimeMs)
                    .build()
            )
            .build()

        val videoEffects = mutableListOf<Effect>()
        if (targetHeight != null && targetHeight > 0) {
            val presentation = Presentation.createForHeight(targetHeight)
            videoEffects.add(presentation)
        }

        val effects = Effects(
            listOf(), // Tidak ada audio effects
            videoEffects
        )

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                super.onCompleted(composition, exportResult)
                stopProgressTracking() // Hentikan pemantauan progres
                completionCallback(Result.success(destPath))
                releaseThread()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                 android.util.Log.e("VideoProcessor", "Export error: ${exportException.message}", exportException)
                super.onError(composition, exportResult, exportException)
                stopProgressTracking() // Hentikan pemantauan progres
                completionCallback(Result.failure(exportException))
                releaseThread()
            }

            override fun onFallbackApplied(
                composition: Composition,
                originalTransformationRequest: TransformationRequest,
                fallbackTransformationRequest: TransformationRequest
            ) {
                super.onFallbackApplied(
                    composition,
                    originalTransformationRequest,
                    fallbackTransformationRequest
                )
                // Fallback biasanya tidak memerlukan penanganan progres khusus,
                // tapi pastikan untuk menghentikan jika perlu dan merilis thread.
                stopProgressTracking()
                releaseThread()
            }
        }

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .build()

        // Menggunakan buildAsync memerlukan penanganan ListenableFuture atau coroutine
        // Untuk kesederhanaan, kita akan tetap menggunakan build() di thread terpisah
        // dan memantau progres secara manual.

        // Membuat Transformer di dalam HandlerThread
        Handler(looper).post {
            try {
                val currentTransformer = Transformer.Builder(context)
                    .setEncoderFactory(encoderFactory)
                    .setLooper(looper) // Gunakan looper dari HandlerThread
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(listener)
                    .build() // Tetap menggunakan build di sini karena kita sudah di thread yang benar

                this.transformer = currentTransformer
                File(destPath).delete() // Hapus file tujuan jika sudah ada
                currentTransformer.start(editedMediaItem, destPath)

                // Mulai memantau progres setelah start dipanggil
                startProgressTracking(currentTransformer, progressCallback)

            } catch (e: Exception) {
                // Tangani error pembuatan Transformer di sini
                completionCallback(Result.failure(ExportException.createForUnexpected(e)))
                releaseThread()
            }
        }
    }

    private fun startProgressTracking(transformer: Transformer, progressCallback: (Int) -> Unit) {
        val progressHolder = ProgressHolder()
        progressRunnable = object : Runnable {
            override fun run() {
                when (transformer.getProgress(progressHolder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> {
                        // progressHolder.progress adalah nilai antara 0 dan 100
                        progressCallback(progressHolder.progress)
                    }
                    Transformer.PROGRESS_STATE_UNAVAILABLE -> {
                        // Progres belum tersedia, mungkin coba lagi nanti atau tunggu
                    }
                    Transformer.PROGRESS_STATE_NOT_STARTED -> {
                        // Tidak ada transformasi yang sedang berjalan
                        // Mungkin sudah selesai atau error, hentikan pemantauan
                        stopProgressTracking()
                        return
                    }
                }
                // Jadwalkan pengecekan progres berikutnya
                if (this@VideoProcessor.transformer != null) { // Pastikan transformer masih ada
                    progressHandler.postDelayed(this, 100) // Cek setiap 100ms
                }
            }
        }
        // Jalankan pengecekan progres pertama kali
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressTracking() {
        progressRunnable?.let {
            progressHandler.removeCallbacks(it)
        }
        progressRunnable = null
    }


    fun trimVideoOnly(
        context: Context,
        sourcePath: String,
        destPath: String,
        startTimeMs: Long,
        endTimeMs: Long,
        progressCallback: (Int) -> Unit,
        completionCallback: (Result<String>) -> Unit
    ) {
        cancel() // Pastikan operasi sebelumnya dibatalkan

        handlerThread = HandlerThread("VideoProcessorTrimThread")
        handlerThread?.start()
        val looper = handlerThread!!.looper // Dapatkan looper dari thread

        val mediaItem = MediaItem.Builder()
            .setUri(sourcePath)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startTimeMs)
                    .setEndPositionMs(endTimeMs)
                    .build()
            )
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                super.onCompleted(composition, exportResult)
                stopProgressTracking() // Hentikan pemantauan progres
                completionCallback(Result.success(destPath))
                releaseThread()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                super.onError(composition, exportResult, exportException)
                stopProgressTracking() // Hentikan pemantauan progres
                completionCallback(Result.failure(exportException))
                releaseThread()
            }

            override fun onFallbackApplied(
                composition: Composition,
                originalTransformationRequest: TransformationRequest,
                fallbackTransformationRequest: TransformationRequest
            ) {
                super.onFallbackApplied(
                    composition,
                    originalTransformationRequest,
                    fallbackTransformationRequest
                )
                stopProgressTracking()
                releaseThread()
            }
        }

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .build()

        // Membuat Transformer di dalam HandlerThread
        Handler(looper).post {
            try {
                val currentTransformer = Transformer.Builder(context)
                    .setEncoderFactory(encoderFactory)
                    .setLooper(looper) // Gunakan looper dari HandlerThread
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(listener)
                    .build() // Tetap menggunakan build di sini

                this.transformer = currentTransformer
                File(destPath).delete() // Hapus file tujuan jika sudah ada
                currentTransformer.start(editedMediaItem, destPath)

                // Mulai memantau progres setelah start dipanggil
                startProgressTracking(currentTransformer, progressCallback)
            } catch (e: Exception) {
                completionCallback(Result.failure(ExportException.createForUnexpected(e)))
                releaseThread()
            }
        }
    }

    fun cancel() {
        transformer?.cancel()
        stopProgressTracking() // Pastikan pemantauan progres dihentikan saat pembatalan
        releaseThread()
    }

    private fun releaseThread() {
        handlerThread?.quitSafely()
        handlerThread = null
        // Tidak perlu null-kan transformer di sini karena bisa jadi masih ada callback listener yang berjalan
        // Biarkan listener yang menangani null-kan transformer atau operasi berikutnya akan melakukannya
    }
}