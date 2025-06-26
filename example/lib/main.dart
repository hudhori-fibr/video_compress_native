import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'dart:async';
import 'package:permission_handler/permission_handler.dart';

import 'package:flutter/services.dart';
import 'package:video_compress_native/video_compress_native.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _status = 'Silakan pilih sebuah proses';
  double _progress = 0.0;
  String? _outputPath;
  StreamSubscription<double>? _progressSubscription;
  bool _isProcessing = false;

  @override
  void initState() {
    super.initState();
    _listenToProgress();
  }

  void _listenToProgress() {
    _progressSubscription = VideoCompressNative.getProgressStream().listen(
      (progress) => setState(() => _progress = progress),
      onError:
          (error) => setState(() {
            _status = 'Error pada stream: $error';
            _resetState();
          }),
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

  Future<void> _runProcess(
    Future<String?> Function(String path) processFunction,
  ) async {
    if (_isProcessing) return;

    final result = await FilePicker.platform.pickFiles(type: FileType.video);
    if (result == null || result.files.single.path == null) {
      setState(() => _status = 'Pemilihan video dibatalkan.');
      return;
    }
    final sourcePath = result.files.single.path!;

    setState(() {
      _status = 'Memulai proses...';
      _isProcessing = true;
      _progress = 0.0;
      _outputPath = null;
    });

    try {
      final outputPath = await processFunction(sourcePath);
      if (mounted) {
        setState(() {
          _status = 'Proses Selesai!';
          _outputPath = outputPath;
          _progress = 1.0;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() => _status = 'Error saat memproses: $e');
      }
    } finally {
      if (mounted) {
        setState(() => _isProcessing = false);
      }
    }
  }


  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Video Processor Example')),
        body: SingleChildScrollView(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  ElevatedButton(
                    onPressed:
                        _isProcessing
                            ? null
                            : () => _runProcess(
                              (path) => VideoCompressNative.compressAndTrim(
                                path: path,
                                startTime: 2.0,
                                endTime: 7.0,
                                resolutionHeight: 480,
                              ),
                            ),
                    child: const Text('Kompresi, Trim & Resize ke 480p'),
                  ),
                  const SizedBox(height: 12),
                  ElevatedButton(
                    onPressed:
                        _isProcessing
                            ? null
                            : () => _runProcess(
                              (path) => VideoCompressNative.trimVideo(
                                path: path,
                                startTime: 1.0,
                                endTime: 5.0,
                              ),
                            ),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.orange,
                    ),
                    child: const Text('Hanya Trim (Cepat)'),
                  ),
                  const SizedBox(height: 20),
                  Text(_status, textAlign: TextAlign.center),
                  const SizedBox(height: 10),
                  if (_isProcessing || _progress == 1.0)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 32.0),
                      child: LinearProgressIndicator(
                        value: _progress,
                        minHeight: 10,
                      ),
                    ),
                  const SizedBox(height: 20),
                  if (_outputPath != null)
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(8.0),
                        child: Column(
                          children: [
                            const Icon(
                              Icons.check_circle,
                              color: Colors.green,
                              size: 40,
                            ),
                            const SizedBox(height: 8),
                            const Text(
                              'File Output:',
                              textAlign: TextAlign.center,
                            ),
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
        ),
      ),
    );
  }
}
