import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:camera/camera.dart';
import 'package:path_provider/path_provider.dart';
import 'main.dart';
import 'video_encoder.dart';
import 'package:share_plus/share_plus.dart';

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
    if (cameras.isEmpty) return;

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
    if (_capturedFrames.isEmpty) return;

    try {
      // UTILISE LE DOSSIER TEMPORAIRE (GARANTI SANS ERREUR DE PERMISSION)
      final Directory tempDir = await getTemporaryDirectory();
      final String outputPath = '${tempDir.path}/video_meme.mp4';
      
      final encoder = VideoEncoder();
      // Le résultat n'est plus un bool mais un String (OK ou l'Erreur)
      final dynamic result = await encoder.createVideoFromFrames(
        framePaths: _capturedFrames,
        outputPath: outputPath,
        resolution: _selectedResolution,
        fps: 2,
      );

      if (result == "OK") {
        _showMessage("SUCCÈS ! Vidéo créée dans le cache.");
        // ICI : On ajoute le partage pour que tu puisses la sortir du téléphone
        await Share.shareXFiles([XFile(outputPath)], text: 'Ma Vidéo 2FPS');
      } else {
        _showMessage("ERREUR NATIF : $result");
      }
    } catch (e) {
      _showMessage("ERREUR FLUTTER : $e");
    }
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  void _showResolutionMenu() {
    showModalBottomSheet(
      context: context,
      builder: (context) => Container(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: _resolutions.map((res) => ListTile(
            title: Text(res),
            onTap: () {
              setState(() => _selectedResolution = res);
              Navigator.pop(context);
            },
          )).toList(),
        ),
      ),
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
        title: const Text('Urban Meme 2FPS'),
        actions: [
          IconButton(icon: const Icon(Icons.settings), onPressed: _isRecording ? null : _showResolutionMenu),
        ],
      ),
      body: Column(
        children: [
          Expanded(child: Center(child: CameraPreview(_controller!))),
          Container(
            padding: const EdgeInsets.all(20),
            color: Colors.black,
            child: Column(
              children: [
                Text('Risoluzione: $_selectedResolution', style: const TextStyle(color: Colors.white)),
                if (_isRecording) Text('Frames: $_frameCount', style: const TextStyle(color: Colors.red, fontWeight: FontWeight.bold)),
                if (_isProcessing) const LinearProgressIndicator(),
              ],
            ),
          )
        ],
      ),
      floatingActionButton: _isProcessing ? null : FloatingActionButton(
        onPressed: _isRecording ? _stopRecording : _startRecording,
        backgroundColor: _isRecording ? Colors.red : Colors.green,
        child: Icon(_isRecording ? Icons.stop : Icons.videocam),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
    );
  }
}