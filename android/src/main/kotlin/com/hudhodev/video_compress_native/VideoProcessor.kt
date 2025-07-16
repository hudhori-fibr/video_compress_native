package com.hudhodev.video_compress_native
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C;
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Effects
import androidx.media3.transformer.VideoEncoderSettings;
import java.io.File
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder

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
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
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
            val videoDurationMs = getVideoDurationMs(sourcePath)
            val safeStart = startTimeMs.coerceAtLeast(0L).coerceAtMost(videoDurationMs)
            val safeEnd = endTimeMs.coerceAtLeast(safeStart).coerceAtMost(videoDurationMs)

            val mediaItem = MediaItem.Builder()
                .setUri(sourcePath)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(safeStart)
                        .setEndPositionMs(safeEnd)
                        .build()
                )
                .build()

            // Ambil tinggi asli dan rotasi video dari metadata
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(sourcePath)
            val actualHeightFromMetadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: targetHeight
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()

            val finalTargetHeight = minOf(targetHeight, actualHeightFromMetadata)
            val videoEffects = mutableListOf<Effect>()
            if (rotation != 0) {
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(rotation.toFloat())
                        .build()
                )
            }
            videoEffects.add(LanczosResample.scaleToFit(10000, finalTargetHeight))
            videoEffects.add(Presentation.createForHeight(finalTargetHeight))

            val audioSupported = isAudioSupported(sourcePath)
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
            val videoDurationMs = getVideoDurationMs(sourcePath)
            val safeStart = startTimeMs.coerceAtLeast(0L).coerceAtMost(videoDurationMs)
            val safeEnd = endTimeMs.coerceAtLeast(safeStart).coerceAtMost(videoDurationMs)

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
            val actualHeightFromMetadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: targetHeight
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()

            val finalTargetHeight = minOf(targetHeight, actualHeightFromMetadata)
            val videoEffects = mutableListOf<Effect>()
            if (rotation != 0) {
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(rotation.toFloat())
                        .build()
                )
            }
            videoEffects.add(LanczosResample.scaleToFit(10000, finalTargetHeight))
            videoEffects.add(Presentation.createForHeight(finalTargetHeight))

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
