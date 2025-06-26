package com.hudhodev.video_compress_native

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.EventChannel.EventSink
import java.io.File

/** VideoCompressNativePlugin */
class VideoCompressNativePlugin: FlutterPlugin, MethodCallHandler, StreamHandler {
  private lateinit var methodChannel : MethodChannel
  private lateinit var eventChannel: EventChannel
  private lateinit var context: Context
  private var eventSink: EventChannel.EventSink? = null
  private val videoProcessor = VideoProcessor()

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    context = flutterPluginBinding.applicationContext
    methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.hudhodev.video_compress_native/method")
    methodChannel.setMethodCallHandler(this)
    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.hudhodev.video_compress_native/event")
    eventChannel.setStreamHandler(this)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "processVideo" -> handleProcessVideo(call, result)
      "trimVideo" -> handleTrimVideo(call, result)
      "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
    videoProcessor.cancel()
  }

  private fun handleProcessVideo(call: MethodCall, result: Result) {
    try {
      val path = call.argument<String>("path")!!
      val startTimeMs = (call.argument<Double>("startTime")!! * 1000).toLong()
      val endTimeMs = (call.argument<Double>("endTime")!! * 1000).toLong()
      val resolutionHeight = call.argument<Int?>("resolutionHeight")
      
      val outputFile = File.createTempFile("processed_", ".mp4", context.cacheDir)

      videoProcessor.processVideo(
        context = context,
        sourcePath = path,
        destPath = outputFile.absolutePath,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        targetHeight = resolutionHeight,
        progressCallback = { progress -> sendProgress(progress.toDouble() / 100.0) },
        completionCallback = { res -> handleCompletion(res, result) }
      )
    } catch (e: Exception) {
      result.error("ARGUMENT_ERROR", e.localizedMessage, e.stackTraceToString())
    }
  }
    
  private fun handleTrimVideo(call: MethodCall, result: Result) {
    try {
      val path = call.argument<String>("path")!!
      val startTimeMs = (call.argument<Double>("startTime")!! * 1000).toLong()
      val endTimeMs = (call.argument<Double>("endTime")!! * 1000).toLong()
      
      val outputFile = File.createTempFile("trimmed_only_", ".mp4", context.cacheDir)

      videoProcessor.trimVideoOnly(
        context = context,
        sourcePath = path,
        destPath = outputFile.absolutePath,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        progressCallback = { progress -> sendProgress(progress.toDouble() / 100.0) },
        completionCallback = { res -> handleCompletion(res, result) }
      )
    } catch (e: Exception) {
      result.error("ARGUMENT_ERROR", e.localizedMessage, e.stackTraceToString())
    }
  }

  private fun sendProgress(progress: Double) {
    Handler(Looper.getMainLooper()).post {
      eventSink?.success(progress)
    }
  }

  private fun handleCompletion(res: kotlin.Result<String>, result: Result) {
    Handler(Looper.getMainLooper()).post {
      res.onSuccess { returnedPath ->
        eventSink?.success(1.0) // Pastikan progres akhir 100%
        result.success(returnedPath)
      }
      res.onFailure { error ->
        eventSink?.error("PROCESSING_FAILED", error.localizedMessage, error.stackTraceToString())
        result.error("PROCESSING_FAILED", error.localizedMessage, null)
      }
    }
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
  }

  override fun onCancel(arguments: Any?) {
    videoProcessor.cancel()
    eventSink = null
  }
}
