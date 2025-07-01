import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';
import 'package:video_compress_native/video_compress_native.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: const VideoProcessorPage(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class VideoProcessorPage extends StatefulWidget {
  const VideoProcessorPage({super.key});

  @override
  State<VideoProcessorPage> createState() => _VideoProcessorPageState();
}

class _VideoProcessorPageState extends State<VideoProcessorPage> {
  String _status = 'Silakan pilih sebuah proses';
  double _progress = 0.0;
  String? _outputPath;
  bool _isProcessing = false;
  StreamSubscription<double>? _progressSubscription;

  @override
  void initState() {
    super.initState();
    _listenToProgress();
  }

  void _listenToProgress() {
    _progressSubscription = VideoCompressNative.getProgressStream().listen(
      (progress) => setState(() => _progress = progress),
      onError: (error) {
        setState(() {
          _status = 'Error pada stream: $error';
          _resetState();
        });
      },
    );
  }

  @override
  void dispose() {
    _progressSubscription?.cancel();
    super.dispose();
  }

  void _resetState() {
    _isProcessing = false;
    _progress = 0.0;
  }

  Future<bool> requestMediaPermission() async {
    if (Platform.isAndroid) {
      // Request dua permission sekaligus, biar aman di Android 13+ dan versi lama
      final statuses = await [Permission.videos, Permission.storage].request();

      final videoGranted = statuses[Permission.videos]?.isGranted ?? false;
      final storageGranted = statuses[Permission.storage]?.isGranted ?? false;

      if (videoGranted || storageGranted) return true;

      final videoDenied =
          statuses[Permission.videos]?.isPermanentlyDenied ?? false;
      final storageDenied =
          statuses[Permission.storage]?.isPermanentlyDenied ?? false;

      if (videoDenied || storageDenied) {
        await _showPermissionDialog();
      }
      return false;
    } else if (Platform.isIOS) {
      final status = await Permission.photos.request();
      if (status.isGranted) return true;
      if (status.isPermanentlyDenied) {
        return true;
        // await _showPermissionDialog();
      }
      return true;
    }
    return false;
  }

  Future<void> _showPermissionDialog() async {
    await showDialog(
      context: context,
      builder:
          (_) => AlertDialog(
            title: const Text('Izin Diperlukan'),
            content: const Text(
              'Aplikasi membutuhkan izin untuk mengakses video. Aktifkan izin dari pengaturan.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Batal'),
              ),
              TextButton(
                onPressed: () {
                  openAppSettings();
                  Navigator.pop(context);
                },
                child: const Text('Buka Pengaturan'),
              ),
            ],
          ),
    );
  }

  Future<void> _runProcess(
    Future<String?> Function(String path) processFunction,
  ) async {
    if (_isProcessing) return;

    final granted = await requestMediaPermission();
    if (!granted) {
      setState(() => _status = 'Izin ditolak.');
      return;
    }

    final result = await FilePicker.platform.pickFiles(type: FileType.video);
    if (result == null || result.files.single.path == null) {
      setState(() => _status = 'Pemilihan video dibatalkan.');
      return;
    }

    final path = result.files.single.path!;
    setState(() {
      _status = 'Memproses...';
      _isProcessing = true;
      _progress = 0.0;
      _outputPath = null;
    });

    try {
      final output = await processFunction(path);
      if (mounted) {
        setState(() {
          _status = 'Selesai!';
          _outputPath = output;
          _progress = 1.0;
        });
      }
    } catch (e) {
      debugPrint("$e");
      if (mounted) setState(() => _status = 'Gagal: $e');
    } finally {
      if (mounted) setState(() => _isProcessing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Video Processor')),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              ElevatedButton(
                onPressed:
                    _isProcessing
                        ? null
                        : () => _runProcess(
                          (path) => VideoCompressNative.compressAndTrim(
                            path: path,
                            startTime: 0.0,
                            endTime: 90.0,
                            resolutionHeight: 480,
                          ),
                        ),
                child: const Text('Kompresi, Trim & Resize 480p'),
              ),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed:
                    _isProcessing
                        ? null
                        : () => _runProcess(
                          (path) => VideoCompressNative.trimVideo(
                            path: path,
                            startTime: 0.0,
                            endTime: 90.0,
                          ),
                        ),
                style: ElevatedButton.styleFrom(backgroundColor: Colors.orange),
                child: const Text('Trim Video (Cepat)'),
              ),
              const SizedBox(height: 20),
              Text(_status),
              const SizedBox(height: 10),
              if (_isProcessing || _progress == 1.0)
                LinearProgressIndicator(value: _progress, minHeight: 10),
              const SizedBox(height: 20),
              if (_outputPath != null)
                Card(
                  margin: const EdgeInsets.all(8),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      children: [
                        const Icon(
                          Icons.check_circle,
                          color: Colors.green,
                          size: 40,
                        ),
                        const SizedBox(height: 8),
                        const Text('File Output:'),
                        SelectableText(
                          _outputPath!,
                          textAlign: TextAlign.center,
                          style: const TextStyle(fontSize: 12),
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
