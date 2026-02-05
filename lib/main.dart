import 'package:flutter/material.dart';
import 'package:camera/camera.dart';
import 'package:permission_handler/permission_handler.dart';
import 'video_recorder_screen.dart';

List<CameraDescription> cameras = [];

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  await Permission.camera.request();
  await Permission.microphone.request();
  await Permission.storage.request();
  
  try {
    cameras = await availableCameras();
  } catch (e) {
    debugPrint('Errore camere: $e');
  }
  
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Video 2FPS',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const VideoRecorderScreen(),
      debugShowCheckedModeBanner: false,
    );
  }
}
