package com.hudhodev.video_compress_native

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
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
        rotation: Int,
        targetHeight: Int
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        val isPortrait = actualWidth > 0 && actualWidth < actualHeight
        val needsRotation = rotation != 0 || (rotation == 0 && isPortrait)
        val finalTargetHeight = minOf(targetHeight, actualHeight)

        Log.d("VideoProcessor", "Final Target Dimension: $finalTargetHeight")

        if (needsRotation) {
            Log.d("VideoProcessor", "Mode Potret: Menambah rotasi dan membalik logika scaleToFit.")
            val rotationDegrees = if (rotation != 0) rotation.toFloat() else 90f
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(rotationDegrees)
                    .build()
            )
            effects.add(LanczosResample.scaleToFit(finalTargetHeight, 10000))
        } else {
            Log.d("VideoProcessor", "Mode Landscape: Menggunakan logika scaleToFit standar.")
            effects.add(LanczosResample.scaleToFit(10000, finalTargetHeight))
        }
        return effects
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

        runOnProcessorThread {
            Log.d("VideoProcessor", "=== Mulai proses video ===")
            Log.d("VideoProcessor", "sourcePath: $sourcePath")
            Log.d("VideoProcessor", "destPath: $destPath")
            Log.d("VideoProcessor", "targetHeight: $targetHeight")
            Log.d("VideoProcessor", "startTimeMs: $startTimeMs, endTimeMs: $endTimeMs")

            val videoDurationMs = getVideoDurationMs(sourcePath)
            Log.d("VideoProcessor", "videoDurationMs: $videoDurationMs")

            val safeStart = startTimeMs.coerceAtLeast(0L).coerceAtMost(videoDurationMs)
            val safeEnd = endTimeMs.coerceAtLeast(safeStart).coerceAtMost(videoDurationMs)
            Log.d("VideoProcessor", "safeStart: $safeStart, safeEnd: $safeEnd")

            val mediaItem = MediaItem.Builder()
                .setUri(sourcePath)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(safeStart)
                        .setEndPositionMs(safeEnd)
                        .build()
                )
                .build()

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(sourcePath)
            val actualHeightFromMetadata =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: targetHeight
            val actualWidthFromMetadata =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            val rotation =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            retriever.release()
            val videoEffects = createVideoEffects(
                actualWidthFromMetadata,
                actualHeightFromMetadata,
                rotation,
                targetHeight
            )
            Log.d("VideoProcessor", "videoEffects: $videoEffects")

            val audioSupported = isAudioSupported(sourcePath)
            Log.d("VideoProcessor", "audioSupported: $audioSupported")

            val effects = Effects(
                if (audioSupported) listOf() else emptyList(), // Audio tetap jika didukung, hilang jika tidak
                videoEffects
            )

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .build()

            val editedMediaItemSequence = EditedMediaItemSequence(editedMediaItem)
            val composition = Composition.Builder(editedMediaItemSequence).build()

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Log.d("VideoProcessor", "Export selesai: $destPath")
                    // Cek metadata output
                    val retrieverOut = MediaMetadataRetriever()
                    retrieverOut.setDataSource(destPath)
                    val outputRotation =
                        retrieverOut.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    val outputWidth =
                        retrieverOut.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val outputHeight =
                        retrieverOut.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    Log.d("VideoProcessor", "output rotation: $outputRotation")
                    Log.d("VideoProcessor", "output size: ${outputWidth}x${outputHeight}")
                    retrieverOut.release()
                    completionCallback(Result.success(destPath))
                    releaseThread()
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    Log.e("VideoProcessor", "Export error: ${exception.message}", exception)
                    completionCallback(Result.failure(exception))
                    releaseThread()
                }
            }


            val videoEncoderSettings = VideoEncoderSettings.DEFAULT
                .buildUpon()
                .setRepeatPreviousFrameIntervalUs(C.MICROS_PER_SECOND / 30)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .build()

            val transformer = Transformer.Builder(context.applicationContext)
                .setEncoderFactory(encoderFactory)
                .setLooper(handlerThread!!.looper)
                .addListener(listener)
                .build()

            this.transformer = transformer
            File(destPath).delete()
            transformer.start(composition, destPath)

            // Progress polling
            progressHandler = Handler(handlerThread!!.looper)
            val progressHolder = ProgressHolder()
            val pollRunnable = object : Runnable {
                override fun run() {
                    transformer.getProgress(progressHolder)
                    Handler(context.mainLooper).post {
                        progressCallback(progressHolder.progress)
                    }
                    if (progressHolder.progress < 100) {
                        progressHandler?.postDelayed(this, 500)
                    }
                }
            }
            progressHandler?.post(pollRunnable)
        }
    }

    fun trimVideoOnly(
        context: Context,
        sourcePath: String,
        destPath: String,
        startTimeMs: Long = 0L,
        endTimeMs: Long = 90_000L,
        targetHeight: Int = 480, // tambahkan parameter ini!
        progressCallback: (Int) -> Unit,
        completionCallback: (Result<String>) -> Unit
    ) {
        cancel()
        handlerThread = HandlerThread("VideoProcessorTrimThread")
        handlerThread?.start()

        runOnProcessorThread {
            Log.d("VideoProcessor", "=== Mulai trim video ===")
            Log.d("VideoProcessor", "sourcePath: $sourcePath")
            Log.d("VideoProcessor", "destPath: $destPath")
            Log.d("VideoProcessor", "targetHeight: $targetHeight")
            Log.d("VideoProcessor", "startTimeMs: $startTimeMs, endTimeMs: $endTimeMs")

            val videoDurationMs = getVideoDurationMs(sourcePath)
            Log.d("VideoProcessor", "videoDurationMs: $videoDurationMs")

            val safeStart = startTimeMs.coerceAtLeast(0L).coerceAtMost(videoDurationMs)
            val safeEnd = endTimeMs.coerceAtLeast(safeStart).coerceAtMost(videoDurationMs)
            Log.d("VideoProcessor", "safeStart: $safeStart, safeEnd: $safeEnd")

            if (safeEnd - safeStart < 1000L) {
                completionCallback(Result.failure(Exception("Durasi trim terlalu pendek atau tidak valid")))
                releaseThread()
                return@runOnProcessorThread
            }

            val mediaItem = MediaItem.Builder()
                .setUri(sourcePath)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(safeStart)
                        .setEndPositionMs(safeEnd)
                        .build()
                )
                .build()

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(sourcePath)
            val actualHeightFromMetadata =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: targetHeight
            val actualWidthFromMetadata =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            val rotation =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            retriever.release()

            val videoEffects = createVideoEffects(
                actualWidthFromMetadata,
                actualHeightFromMetadata,
                rotation,
                targetHeight
            )
            Log.d("VideoProcessor", "videoEffects: $videoEffects")

            val effects = Effects(listOf(), videoEffects)

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .build()

            val editedMediaItemSequence = EditedMediaItemSequence(editedMediaItem)
            val composition = Composition.Builder(editedMediaItemSequence).build()

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    completionCallback(Result.success(destPath))
                    releaseThread()
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    completionCallback(Result.failure(exception))
                    releaseThread()
                }
            }

            val videoEncoderSettings = VideoEncoderSettings.DEFAULT
                .buildUpon()
                .setRepeatPreviousFrameIntervalUs(C.MICROS_PER_SECOND / 30)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .build()

            val transformer = Transformer.Builder(context.applicationContext)
                .setEncoderFactory(encoderFactory)
                .setLooper(handlerThread!!.looper)
                .addListener(listener)
                .build()

            this.transformer = transformer
            File(destPath).delete()
            transformer.start(composition, destPath)

            // Progress polling
            progressHandler = Handler(handlerThread!!.looper)
            val progressHolder = ProgressHolder()
            val pollRunnable = object : Runnable {
                override fun run() {
                    transformer.getProgress(progressHolder)
                    Handler(context.mainLooper).post {
                        progressCallback(progressHolder.progress)
                    }
                    if (progressHolder.progress < 100) {
                        progressHandler?.postDelayed(this, 500)
                    }
                }
            }
            progressHandler?.post(pollRunnable)
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
