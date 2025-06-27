import Flutter
import UIKit
import AVFoundation

public class VideoCompressNativePlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
  private var eventSink: FlutterEventSink?
  private var exportSession: AVAssetExportSession?
  private var timer: Timer?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let methodChannel = FlutterMethodChannel(name: "com.hudhodev.video_compress_native/method", binaryMessenger: registrar.messenger())
    let eventChannel = FlutterEventChannel(name: "com.hudhodev.video_compress_native/event", binaryMessenger: registrar.messenger())
    let instance = VideoCompressNativePlugin()
    registrar.addMethodCallDelegate(instance, channel: methodChannel)
    eventChannel.setStreamHandler(instance)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getPlatformVersion":
      result("iOS " + UIDevice.current.systemVersion)
    case "processVideo":
      handleProcessVideo(call: call, result: result)
    case "trimVideo":
      handleTrimVideo(call: call, result: result)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func handleProcessVideo(call: FlutterMethodCall, result: @escaping FlutterResult) {
    guard let args = call.arguments as? [String: Any],
        let path = args["path"] as? String,
        let startTime = args["startTime"] as? Double,
        let endTime = args["endTime"] as? Double else {
      result(FlutterError(code: "INVALID_ARGS", message: "Argumen tidak valid", details: nil))
      return
    }
    let resolutionHeight = args["resolutionHeight"] as? Int
    processVideo(path: path, startTime: startTime, endTime: endTime, targetHeight: resolutionHeight, flutterResult: result)
  }
    
    private func handleTrimVideo(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let path = args["path"] as? String,
              let startTime = args["startTime"] as? Double,
              let endTime = args["endTime"] as? Double else {
            result(FlutterError(code: "INVALID_ARGS", message: "Argumen tidak valid", details: nil))
            return
        }
        performTrimOnly(path: path, startTime: startTime, endTime: endTime, flutterResult: result)
    }

    private func processVideo(path: String, startTime: Double, endTime: Double, targetHeight: Int?, flutterResult: @escaping FlutterResult) {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        guard let videoTrack = asset.tracks(withMediaType: .video).first else {
             flutterResult(FlutterError(code: "NO_VIDEO_TRACK", message: "Tidak ada track video", details: nil))
            return
        }

        var videoComposition: AVMutableVideoComposition?
        if let targetHeight = targetHeight {
            let naturalSize = videoTrack.naturalSize
            let preferredTransform = videoTrack.preferredTransform
            let transformedSize = naturalSize.applying(preferredTransform)
            let actualWidth = abs(transformedSize.width)
            let actualHeight = abs(transformedSize.height)
            let aspectRatio = actualWidth / actualHeight
            var outputWidth: CGFloat, outputHeight: CGFloat

            if actualWidth > actualHeight {
                outputHeight = CGFloat(targetHeight)
                outputWidth = outputHeight * aspectRatio
            } else {
                outputWidth = CGFloat(targetHeight)
                outputHeight = outputWidth / aspectRatio
            }
            
            outputWidth = round(outputWidth / 2) * 2
            outputHeight = round(outputHeight / 2) * 2
            
            let composition = AVMutableVideoComposition()
            composition.renderSize = CGSize(width: outputWidth, height: outputHeight)
            composition.frameDuration = CMTime(value: 1, timescale: 30)

            let instruction = AVMutableVideoCompositionInstruction()
            instruction.timeRange = CMTimeRange(start: .zero, duration: asset.duration)
            let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
            layerInstruction.setTransform(preferredTransform, at: .zero)
            instruction.layerInstructions = [layerInstruction]
            composition.instructions = [instruction]
            videoComposition = composition
        }

        startExport(asset: asset, preset: AVAssetExportPresetMediumQuality, videoComposition: videoComposition,
                    startTime: startTime, endTime: endTime, flutterResult: flutterResult)
    }

    private func performTrimOnly(path: String, startTime: Double, endTime: Double, flutterResult: @escaping FlutterResult) {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        startExport(asset: asset, preset: AVAssetExportPresetPassthrough, videoComposition: nil,
                    startTime: startTime, endTime: endTime, flutterResult: flutterResult)
    }

    private func startExport(asset: AVAsset, preset: String, videoComposition: AVMutableVideoComposition?,
                             startTime: Double, endTime: Double, flutterResult: @escaping FlutterResult) {
        
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else {
            flutterResult(FlutterError(code: "SESSION_FAILED", message: "Gagal membuat AVAssetExportSession", details: nil))
            return
        }
        
        if let composition = videoComposition {
            session.videoComposition = composition
        }
        
        self.exportSession = session
        let tempDir = NSTemporaryDirectory()
        let fileName = "\(UUID().uuidString).mp4"
        let destinationURL = URL(fileURLWithPath: tempDir).appendingPathComponent(fileName)
        
        try? FileManager.default.removeItem(at: destinationURL)
        
        session.outputURL = destinationURL
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true
        
        session.timeRange = CMTimeRange(start: CMTime(seconds: startTime, preferredTimescale: 1000),
                                        end: CMTime(seconds: endTime, preferredTimescale: 1000))
        
        self.timer?.invalidate()
        self.timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            self?.eventSink?(Double(self?.exportSession?.progress ?? 0))
        }
        
        session.exportAsynchronously {
            DispatchQueue.main.async {
                self.timer?.invalidate()
                self.timer = nil
                self.handleExportCompletion(session: session, flutterResult: flutterResult)
            }
        }
    }

    private func handleExportCompletion(session: AVAssetExportSession, flutterResult: @escaping FlutterResult) {
        switch session.status {
        case .completed:
            self.eventSink?(1.0)
            flutterResult(session.outputURL?.path)
        case .failed:
            let error = session.error?.localizedDescription ?? "Unknown error"
            self.eventSink?(FlutterError(code: "EXPORT_FAILED", message: error, details: nil))
            flutterResult(FlutterError(code: "EXPORT_FAILED", message: error, details: nil))
        case .cancelled:
            flutterResult(FlutterError(code: "EXPORT_CANCELLED", message: "Proses dibatalkan", details: nil))
        default:
            break
        }
    }
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.exportSession?.cancelExport()
        self.timer?.invalidate()
        self.timer = nil
        self.eventSink = nil
        return nil
    }
}
