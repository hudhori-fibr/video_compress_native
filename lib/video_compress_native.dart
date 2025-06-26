import 'package:flutter/services.dart';

import 'video_compress_native_platform_interface.dart';

class VideoCompressNative {
  static const MethodChannel _methodChannel = MethodChannel(
    'com.hudhodev.video_compress_native/method',
  );
  static const EventChannel _eventChannel = EventChannel(
    'com.hudhodev.video_compress_native/event',
  );

  static Future<String?> compressAndTrim({
    required String path,
    required double startTime,
    required double endTime,
    int? resolutionHeight,
  }) async {
    if (startTime < 0.0 || endTime <= startTime) {
      throw ArgumentError('Waktu mulai dan selesai tidak valid.');
    }

    final String? resultPath = await _methodChannel
        .invokeMethod('processVideo', {
          'path': path,
          'startTime': startTime,
          'endTime': endTime,
          'resolutionHeight': resolutionHeight,
        });
    return resultPath;
  }

  static Future<String?> trimVideo({
    required String path,
    required double startTime,
    required double endTime,
  }) async {
    if (startTime < 0.0 || endTime <= startTime) {
      throw ArgumentError('Waktu mulai dan selesai tidak valid.');
    }

    final String? resultPath = await _methodChannel.invokeMethod('trimVideo', {
      'path': path,
      'startTime': startTime,
      'endTime': endTime,
    });
    return resultPath;
  }

  static Stream<double> getProgressStream() {
    return _eventChannel.receiveBroadcastStream().map(
      (dynamic event) => event as double,
    );
  }

  Future<String?> getPlatformVersion() {
    return VideoCompressNativePlatform.instance.getPlatformVersion();
  }
}
