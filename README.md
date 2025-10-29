# video_compress_native

Flutter plugin for video compression, trimming, and re-encoding with cross-platform support.

## Features

- **Video Compression & Trimming**: Compress video with custom resolution and trim to specific time range
- **Video Trimming Only**: Trim video without compression
- **Video Re-encoding**: Re-encode video with standard codec for compatibility (NEW in v0.1.0)
- **Progress Monitoring**: Real-time progress updates during processing
- **Cross-platform**: Supports both Android and iOS

## Usage

```dart
import 'package:video_compress_native/video_compress_native.dart';

// Compress and trim video
String? result = await VideoCompressNative.compressAndTrim(
  path: '/path/to/video.mp4',
  startTime: 10.0,
  endTime: 60.0,
  resolutionHeight: 720,
);

// Trim video only
String? result = await VideoCompressNative.trimVideo(
  path: '/path/to/video.mp4', 
  startTime: 5.0,
  endTime: 30.0,
);

// Compress/re-encode video only (NEW)
String? result = await VideoCompressNative.compressVideo(
  path: '/path/to/video.mp4',
);

// Monitor progress
VideoCompressNative.getProgressStream().listen((progress) {
  print('Progress: ${(progress * 100).toInt()}%');
});
```

## Requirements

- **Android**: API level 21+ (Android 5.0+)
- **iOS**: iOS 11.0+
- **Flutter**: 3.3.0+

