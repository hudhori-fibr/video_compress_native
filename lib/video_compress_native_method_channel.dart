import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'video_compress_native_platform_interface.dart';

/// An implementation of [VideoCompressNativePlatform] that uses method channels.
class MethodChannelVideoCompressNative extends VideoCompressNativePlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('com.hudhodev.video_compress_native/method');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>(
      'getPlatformVersion',
    );
    return version;
  }
}
