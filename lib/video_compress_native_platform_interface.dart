import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'video_compress_native_method_channel.dart';

abstract class VideoCompressNativePlatform extends PlatformInterface {
  /// Constructs a VideoCompressNativePlatform.
  VideoCompressNativePlatform() : super(token: _token);

  static final Object _token = Object();

  static VideoCompressNativePlatform _instance = MethodChannelVideoCompressNative();

  /// The default instance of [VideoCompressNativePlatform] to use.
  ///
  /// Defaults to [MethodChannelVideoCompressNative].
  static VideoCompressNativePlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [VideoCompressNativePlatform] when
  /// they register themselves.
  static set instance(VideoCompressNativePlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
