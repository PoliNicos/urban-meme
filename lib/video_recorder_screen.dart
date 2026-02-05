import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:camera/camera.dart';
import 'package:path_provider/path_provider.dart';
import 'main.dart';
import 'video_encoder.dart';

class VideoRecorderScreen extends StatefulWidget {
  const VideoRecorderScreen({super.key});

  @override
  State<VideoRecorderScreen> createState() => _VideoRecorderScreenState();
}

class _VideoRecorderScreenState extends State<VideoRecorderScreen> {
  CameraController? _controller;
  bool _isRecording = false;
  bool _isProcessing = false;
  Timer? _captureTimer;
  final List<String> _capturedFrames = [];
  int _frameCount = 0;
  
  String _selectedResolution = '1280x720';
  final List<String> _resolutions = ['640x480', '1280x720', '1920x1080'];

  @override
  void initState() {
    super.initState();
    _initializeCamera();
  }

  Future<void> _initializeCamera() async {
    if (cameras.isEmpty) {
      debugPrint('Nessuna camera disponibile');
      return;
    }

    _controller = CameraController(
      cameras[0],
      ResolutionPreset.high,
      enableAudio: false,
      imageFormatGroup: ImageFormatGroup.jpeg,
    );

    try {
      await _controller!.initialize();
      if (mounted) setState(() {});
    } catch (e) {
      debugPrint('Errore camera: $e');
    }
  }

  Future<void> _startRecording() async {
    if (_controller == null || !_controller!.value.isInitialized) return;

    setState(() {
      _isRecording = true;
      _capturedFrames.clear();
      _frameCount = 0;
    });

    _captureTimer = Timer.periodic(const Duration(milliseconds: 500), (timer) async {
      await _captureFrame();
    });
  }

  Future<void> _captureFrame() async {
    if (_controller == null || !_controller!.value.isInitialized) return;

    try {
      final Directory tempDir = await getTemporaryDirectory();
      final String framePath = '${tempDir.path}/frame_${_frameCount.toString().padLeft(5, '0')}.jpg';
      
      final XFile image = await _controller!.takePicture();
      await File(image.path).copy(framePath);
      
      _capturedFrames.add(framePath);
      setState(() => _frameCount++);
      
      debugPrint('Frame $_frameCount catturato');
    } catch (e) {
      debugPrint('Errore cattura: $e');
    }
  }

  Future<void> _stopRecording() async {
    _captureTimer?.cancel();
    
    setState(() {
      _isRecording = false;
      _isProcessing = true;
    });

    await _createVideo();
    
    setState(() => _isProcessing = false);
  }

  Future<void> _createVideo() async {
    if (_capturedFrames.isEmpty) {
      _showMessage('Nessun frame catturato');
      return;
    }

    try {
      final Directory appDir = await getApplicationDocumentsDirectory();
      final String timestamp = DateTime.now().millisecondsSinceEpoch.toString();
      final String outputPath = '${appDir.path}/video_$timestamp.mp4';
      
      final encoder = VideoEncoder();
      final success = await encoder.createVideoFromFrames(
        framePaths: _capturedFrames,
        outputPath: outputPath,
        resolution: _selectedResolution,
        fps: 2,
      );

      for (var framePath in _capturedFrames) {
        try {
          await File(framePath).delete();
        } catch (e) {
          debugPrint('Errore eliminazione: $e');
        }
      }
      
      _capturedFrames.clear();
      
      if (success) {
        _showMessage('Video salvato: $outputPath');
      } else {
        _showMessage('Errore creazione video');
      }
    } catch (e) {
      debugPrint('Errore: $e');
      _showMessage('Errore: $e');
    }
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 3)),
    );
  }

  void _showResolutionMenu() {
    showModalBottomSheet(
      context: context,
      builder: (BuildContext context) {
        return Container(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Seleziona Risoluzione', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
              const SizedBox(height: 16),
              ..._resolutions.map((resolution) {
                return ListTile(
                  title: Text(resolution),
                  leading: Radio<String>(
                    value: resolution,
                    groupValue: _selectedResolution,
                    onChanged: (String? value) {
                      setState(() => _selectedResolution = value!);
                      Navigator.pop(context);
                    },
                  ),
                  onTap: () {
                    setState(() => _selectedResolution = resolution);
                    Navigator.pop(context);
                  },
                );
              }),
            ],
          ),
        );
      },
    );
  }

  @override
  void dispose() {
    _captureTimer?.cancel();
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_controller == null || !_controller!.value.isInitialized) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Registratore 2FPS'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: _isRecording ? null : _showResolutionMenu,
            tooltip: 'Risoluzione',
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Center(
              child: AspectRatio(
                aspectRatio: _controller!.value.aspectRatio,
                child: CameraPreview(_controller!),
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.all(16),
            color: Colors.black87,
            child: Column(
              children: [
                Text('Risoluzione: $_selectedResolution', style: const TextStyle(color: Colors.white, fontSize: 16)),
                const SizedBox(height: 8),
                if (_isRecording)
                  Text('Frame: $_frameCount (2 fps)', style: const TextStyle(color: Colors.green, fontSize: 18, fontWeight: FontWeight.bold)),
                if (_isProcessing)
                  const Column(
                    children: [
                      SizedBox(height: 8),
                      CircularProgressIndicator(color: Colors.white),
                      SizedBox(height: 8),
                      Text('Creazione video...', style: TextStyle(color: Colors.white)),
                    ],
                  ),
              ],
            ),
          ),
        ],
      ),
      floatingActionButton: _isProcessing ? null : FloatingActionButton.large(
        onPressed: _isRecording ? _stopRecording : _startRecording,
        backgroundColor: _isRecording ? Colors.red : Colors.green,
        child: Icon(_isRecording ? Icons.stop : Icons.fiber_manual_record, size: 40),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
    );
  }
}
