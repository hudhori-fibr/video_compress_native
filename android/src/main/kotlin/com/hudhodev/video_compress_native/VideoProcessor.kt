package com.hudhodev.video_compress_native

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.LanczosResample
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(UnstableApi::class)
class VideoProcessor {
    private var transformer: Transformer? = null
    private var handlerThread: HandlerThread? = null
    private var progressHandler: Handler? = null

    private fun generateOutputFilePath(destDir: String, ext: String = "mp4"): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        return "$destDir/VID_${timeStamp}_$uuid.$ext"
    }

    private fun getVideoDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationStr =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun createVideoEffects(
        actualWidth: Int,
        actualHeight: Int,
        targetHeight: Int
    ): List<Effect> {
        val videoEffects = mutableListOf<Effect>()
        val finalOutputHeight = minOf(targetHeight, actualHeight)

        val aspectRatio = if (actualHeight > 0) {
            actualWidth.toFloat() / actualHeight.toFloat()
        } else {
            1.0f
        }
        val finalOutputWidth = (finalOutputHeight * aspectRatio).toInt()

        Log.d("VideoProcessor", "Final target resolution: ${finalOutputWidth}x$finalOutputHeight")

        videoEffects.add(LanczosResample.scaleToFit(finalOutputWidth, finalOutputHeight))

        Log.d("VideoProcessor", "videoEffects: $videoEffects")
        return videoEffects
    }

    // Tambahkan fungsi untuk cek audio encoding didukung
    private fun isAudioSupported(path: String): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val audioCodec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            // Contoh: hanya dukung AAC dan MP3
            audioCodec == "audio/mp4a-latm" || audioCodec == "audio/mpeg"
        } catch (e: Exception) {
            false
        } finally {
            retriever.release()
        }
    }

    fun processVideo(
        context: Context,
        sourcePath: String,
        destPath: String,
        startTimeMs: Long = 0L,
        endTimeMs: Long = 90_000L,
        targetHeight: Int = 480,
        progressCallback: (Int) -> Unit,
        completionCallback: (Result<String>) -> Unit
    ) {
        cancel()
        handlerThread = HandlerThread("VideoProcessorThread")
        handlerThread?.start()
        progressHandler = Handler(handlerThread!!.looper)

        runOnProcessorThread {
            Log.d("VideoProcessor", "=== Mulai proses video (Proses 2 Langkah) ===")

            val tempFilePath = context.cacheDir.path + "/temp_flattened_${UUID.randomUUID()}.mp4"
            val tempFile = File(tempFilePath)
            val progressHolder = ProgressHolder()

            // HAPUS blok try...finally dari sini

            // ==========================================================
            // LANGKAH 1: FLATTEN & SCALE (TANPA CLIPPING)
            // ==========================================================
            Log.d("VideoProcessor", "Langkah 1: Flattening video ke -> $tempFilePath")

            val fullMediaItem = MediaItem.fromUri(sourcePath)

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(sourcePath)
            val actualHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: targetHeight
            val actualWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            retriever.release()

            val effects = createVideoEffects(actualWidth, actualHeight, targetHeight)
            val audioEffects = if (isAudioSupported(sourcePath)) listOf<AudioProcessor>() else emptyList()

            val editedMediaItemStep1 = EditedMediaItem.Builder(fullMediaItem)
                .setEffects(Effects(audioEffects, effects))
                .setFlattenForSlowMotion(true)
                .build()

            val compositionStep1 = Composition.Builder(EditedMediaItemSequence(editedMediaItemStep1)).build()

            val listenerStep1 = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Log.d("VideoProcessor", "Langkah 1 Selesai.")
                    // ==========================================================
                    // LANGKAH 2: CLIPPING VIDEO DARI FILE SEMENTARA
                    // ==========================================================
                    Log.d("VideoProcessor", "Langkah 2: Clipping video dari $tempFilePath ke -> $destPath")
                    
                    val videoDurationMs = getVideoDurationMs(tempFilePath)
                    val safeStart = startTimeMs.coerceAtLeast(0L).coerceAtMost(videoDurationMs)
                    val safeEnd = endTimeMs.coerceAtLeast(safeStart).coerceAtMost(videoDurationMs)

                    val clippedMediaItem = MediaItem.Builder()
                        .setUri(tempFilePath)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(safeStart)
                                .setEndPositionMs(safeEnd)
                                .build()
                        ).build()

                    val editedMediaItemStep2 = EditedMediaItem.Builder(clippedMediaItem).setRemoveAudio(audioEffects.isEmpty()).build()
                    val compositionStep2 = Composition.Builder(EditedMediaItemSequence(editedMediaItemStep2)).build()

                    val listenerStep2 = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            Log.d("VideoProcessor", "Langkah 2 Selesai. Proses Berhasil.")
                            Handler(context.mainLooper).post { completionCallback(Result.success(destPath)) }
                            // Hapus file sementara setelah berhasil
                            if (tempFile.exists()) tempFile.delete()
                            releaseThread()
                        }
                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            Log.e("VideoProcessor", "Langkah 2 Gagal.", exception)
                            Handler(context.mainLooper).post { completionCallback(Result.failure(exception)) }
                            // Hapus file sementara setelah gagal
                            if (tempFile.exists()) tempFile.delete()
                            releaseThread()
                        }
                    }
                    
                    val transformerStep2 = Transformer.Builder(context)
                        .setLooper(handlerThread!!.looper)
                        .addListener(listenerStep2).build()
                    
                    this@VideoProcessor.transformer = transformerStep2
                    File(destPath).delete()
                    transformerStep2.start(compositionStep2, destPath)
                    
                                            // Progress Polling untuk Langkah 2 (51% - 100%)
                        val pollRunnableStep2 = object : Runnable {
                            override fun run() {
                                if (transformerStep2.getProgress(progressHolder) != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                                    val overallProgress = 50 + (progressHolder.progress / 2)
                                    Handler(context.mainLooper).post {
                                        progressCallback(
                                            overallProgress
                                        )
                                    }
                                    if (progressHolder.progress < 100) {
                                        progressHandler?.postDelayed(this, 500)
                                    }
                                }
                            }
                        }

                    progressHandler?.post(pollRunnableStep2)
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    Log.e("VideoProcessor", "Langkah 1 Gagal.", exception)
                    Handler(context.mainLooper).post { completionCallback(Result.failure(exception)) }
                    // Hapus file sementara jika langkah 1 gagal
                    if (tempFile.exists()) tempFile.delete()
                    releaseThread()
                }
            }

            val transformerStep1 = Transformer.Builder(context)
                .setLooper(handlerThread!!.looper)
                .addListener(listenerStep1).build()
            
            this.transformer = transformerStep1
            transformerStep1.start(compositionStep1, tempFilePath)
            
            val pollRunnableStep1 = object : Runnable { /* ... Kode polling progress Anda ... */ }
            progressHandler?.post(pollRunnableStep1)
        }
    }

    fun trimVideoOnly(
        context: Context,
        sourcePath: String,
        destPath: String,
        startTimeMs: Long = 0L,
        endTimeMs: Long = 90_000L,
        targetHeight: Int = 480,
        progressCallback: (Int) -> Unit,
        completionCallback: (Result<String>) -> Unit
    ) {
        cancel()
        handlerThread = HandlerThread("VideoProcessorTrimThread")
        handlerThread?.start()
        progressHandler = Handler(handlerThread!!.looper)

        runOnProcessorThread {
            Log.d("VideoProcessor", "=== Mulai trim video (Proses 2 Langkah) ===")
            val tempFilePath =
                context.cacheDir.path + "/temp_flattened_trim_${UUID.randomUUID()}.mp4"
            val tempFile = File(tempFilePath)
            val progressHolder = ProgressHolder()

            try {
                // Langkah 1: Flatten & Scale (tanpa audio, tanpa clipping)
                Log.d("VideoProcessor", "Langkah 1 (Trim): Flattening video ke -> $tempFilePath")
                val fullMediaItem = MediaItem.fromUri(sourcePath)

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(sourcePath)
                val actualHeight =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: targetHeight
                val actualWidth =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                retriever.release()

                val effects = createVideoEffects(actualWidth, actualHeight, targetHeight)

                val editedMediaItemStep1 = EditedMediaItem.Builder(fullMediaItem)
                    .setEffects(Effects(emptyList(), effects)) // Kosongkan audio
                    .setFlattenForSlowMotion(true)
                    .build()

                val compositionStep1 =
                    Composition.Builder(EditedMediaItemSequence(editedMediaItemStep1)).build()

                val listenerStep1 = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        Log.d("VideoProcessor", "Langkah 1 (Trim) Selesai.")
                        // Langkah 2: Clipping dari file sementara
                        Log.d(
                            "VideoProcessor",
                            "Langkah 2 (Trim): Clipping video dari $tempFilePath ke -> $destPath"
                        )

                        val videoDurationMs = getVideoDurationMs(tempFilePath)
                        val safeStart = startTimeMs.coerceAtLeast(0L).coerceAtMost(videoDurationMs)
                        val safeEnd =
                            endTimeMs.coerceAtLeast(safeStart).coerceAtMost(videoDurationMs)

                        if (safeEnd - safeStart < 1000L) {
                            Handler(context.mainLooper).post {
                                completionCallback(
                                    Result.failure(
                                        Exception("Durasi trim terlalu pendek")
                                    )
                                )
                            }
                            releaseThread()
                            return
                        }

                        val clippedMediaItem = MediaItem.Builder()
                            .setUri(tempFilePath)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(safeStart)
                                    .setEndPositionMs(safeEnd)
                                    .build()
                            ).build()

                        val editedMediaItemStep2 =
                            EditedMediaItem.Builder(clippedMediaItem).setRemoveAudio(true).build()
                        val compositionStep2 =
                            Composition.Builder(EditedMediaItemSequence(editedMediaItemStep2))
                                .build()

                        val listenerStep2 = object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                result: ExportResult
                            ) {
                                Log.d(
                                    "VideoProcessor",
                                    "Langkah 2 (Trim) Selesai. Proses Berhasil."
                                )
                                Handler(context.mainLooper).post {
                                    completionCallback(
                                        Result.success(
                                            destPath
                                        )
                                    )
                                }
                                releaseThread()
                            }

                            override fun onError(
                                composition: Composition,
                                result: ExportResult,
                                exception: ExportException
                            ) {
                                Log.e("VideoProcessor", "Langkah 2 (Trim) Gagal.", exception)
                                Handler(context.mainLooper).post {
                                    completionCallback(
                                        Result.failure(
                                            exception
                                        )
                                    )
                                }
                                releaseThread()
                            }
                        }

                        val transformerStep2 = Transformer.Builder(context)
                            .setLooper(handlerThread!!.looper)
                            .addListener(listenerStep2).build()

                        this@VideoProcessor.transformer = transformerStep2
                        File(destPath).delete()
                        transformerStep2.start(compositionStep2, destPath)

                        // Progress Polling untuk Langkah 2
                        val pollRunnableStep2 = object : Runnable {
                            override fun run() {
                                if (transformerStep2.getProgress(progressHolder) != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                                    val overallProgress = 50 + (progressHolder.progress / 2)
                                    Handler(context.mainLooper).post {
                                        progressCallback(
                                            overallProgress
                                        )
                                    }
                                    if (progressHolder.progress < 100) {
                                        progressHandler?.postDelayed(this, 500)
                                    }
                                }
                            }
                        }
                        progressHandler?.post(pollRunnableStep2)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        Log.e("VideoProcessor", "Langkah 1 (Trim) Gagal.", exception)
                        Handler(context.mainLooper).post {
                            completionCallback(
                                Result.failure(
                                    exception
                                )
                            )
                        }
                        releaseThread()
                    }
                }

                val transformerStep1 = Transformer.Builder(context)
                    .setLooper(handlerThread!!.looper)
                    .addListener(listenerStep1).build()

                this.transformer = transformerStep1
                transformerStep1.start(compositionStep1, tempFilePath)

                // Progress Polling untuk Langkah 1
                val pollRunnableStep1 = object : Runnable {
                    override fun run() {
                        if (transformerStep1.getProgress(progressHolder) != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                            val overallProgress = progressHolder.progress / 2
                            Handler(context.mainLooper).post { progressCallback(overallProgress) }
                            if (progressHolder.progress < 100) {
                                progressHandler?.postDelayed(this, 500)
                            }
                        } else {
                            progressHandler?.postDelayed(this, 500)
                        }
                    }
                }
                progressHandler?.post(pollRunnableStep1)

            } finally {
                Handler(handlerThread!!.looper).postDelayed({
                    if (tempFile.exists()) {
                        Log.d("VideoProcessor", "Menghapus file sementara: $tempFilePath")
                        tempFile.delete()
                    }
                }, 1000)
            }
        }
    }

    fun cancel() {
        val thread = handlerThread
        if (thread != null && thread.isAlive) {
            Handler(thread.looper).post {
                transformer?.cancel()
                releaseThread()
            }
        } else {
            // Tidak ada proses yang berjalan, cukup release
            releaseThread()
        }
    }

    private fun releaseThread() {
        handlerThread?.quitSafely()
        handlerThread = null
        progressHandler = null
    }

    private fun runOnProcessorThread(action: () -> Unit) {
        handlerThread?.let {
            Handler(it.looper).post { action() }
        } ?: action()
    }
}
