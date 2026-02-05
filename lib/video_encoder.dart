import 'package:ffmpeg_kit_flutter_min_gpl/ffmpeg_kit.dart';
import 'package:ffmpeg_kit_flutter_min_gpl/return_code.dart';
import 'dart:io';

class VideoEncoder {
  Future<bool> createVideoFromFrames({
    required List<String> framePaths,
    required String outputPath,
    required String resolution,
    int fps = 2,
  }) async {
    if (framePaths.isEmpty) return false;

    // Récupère le dossier où sont tes frames
    final String folderPath = File(framePaths.first).parent.path;
    
    // Commande FFmpeg : On cherche les fichiers frame_00000.jpg, frame_00001.jpg...
    // scale=${resolution.replaceAll('x', ':')} transforme '1280x720' en '1280:720'
    final String command = "-y -framerate $fps -i $folderPath/frame_%05d.jpg -vf scale=${resolution.replaceAll('x', ':')} -c:v libx264 -pix_fmt yuv420p $outputPath";

    final session = await FFmpegKit.execute(command);
    final returnCode = await session.getReturnCode();

    return ReturnCode.isSuccess(returnCode);
  }
}