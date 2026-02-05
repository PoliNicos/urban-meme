import 'package:flutter/services.dart';

class VideoEncoder {
  static const MethodChannel _channel = MethodChannel('com.example.urban_meme/video_encoder');

  Future<bool> createVideoFromFrames({
    required List<String> framePaths,
    required String outputPath,
    required String resolution,
    int fps = 2,
  }) async {
    try {
      final parts = resolution.split('x');
      final width = int.parse(parts[0]);
      final height = int.parse(parts[1]);

      final bool result = await _channel.invokeMethod('createVideo', {
        'framePaths': framePaths,
        'outputPath': outputPath,
        'width': width,
        'height': height,
        'fps': fps,
      });
      return result;
    } catch (e) {
      print("Erreur encodage: $e");
      return false;
    }
  }
}