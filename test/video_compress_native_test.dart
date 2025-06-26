import 'package:flutter_test/flutter_test.dart';
import 'package:video_compress_native/video_compress_native.dart';
import 'package:video_compress_native/video_compress_native_platform_interface.dart';
import 'package:video_compress_native/video_compress_native_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockVideoCompressNativePlatform
    with MockPlatformInterfaceMixin
    implements VideoCompressNativePlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final VideoCompressNativePlatform initialPlatform = VideoCompressNativePlatform.instance;

  test('$MethodChannelVideoCompressNative is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelVideoCompressNative>());
  });

  test('getPlatformVersion', () async {
    VideoCompressNative videoCompressNativePlugin = VideoCompressNative();
    MockVideoCompressNativePlatform fakePlatform = MockVideoCompressNativePlatform();
    VideoCompressNativePlatform.instance = fakePlatform;

    expect(await videoCompressNativePlugin.getPlatformVersion(), '42');
  });
}
