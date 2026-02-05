package com.example.urban_meme

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.view.Surface
import java.io.File

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.example.urban_meme/video_encoder"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "createVideo") {
                val framePaths = call.argument<List<String>>("framePaths")
                val outputPath = call.argument<String>("outputPath")
                val width = call.argument<Int>("width") ?: 1280
                val height = call.argument<Int>("height") ?: 720
                val fps = call.argument<Int>("fps") ?: 2
                
                if (framePaths != null && outputPath != null) {
                    Thread {
                        val success = encodeWithSurface(framePaths, outputPath, width, height, fps)
                        runOnUiThread { result.success(success) }
                    }.start()
                } else {
                    result.error("INVALID_ARGS", "Arguments manquants", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    private fun writeLog(outputPath: String, message: String) {
        try {
            val logFile = File(outputPath.replace(".mp4", "_log.txt"))
            logFile.appendText("[${java.util.Date()}] $message\n")
        } catch (e: Exception) {}
    }

    private fun encodeWithSurface(framePaths: List<String>, outputPath: String, width: Int, height: Int, fps: Int): Boolean {
        writeLog(outputPath, "DÉMARRAGE : $width x $height @ $fps fps. Frames: ${framePaths.size}")
        
        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3000000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var muxerStarted = false
        var trackIndex = -1

        try {
            encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1000000L / fps

            for ((index, path) in framePaths.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) {
                    writeLog(outputPath, "ERREUR : Frame $index illisible : $path")
                    continue
                }

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                
                // Dessin sur la Surface
                val canvas = inputSurface.lockCanvas(null)
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(canvas)
                
                // Drainer les données vers le muxer
                var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputBufferIndex >= 0) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        writeLog(outputPath, "Muxer démarré.")
                    } else {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            bufferInfo.presentationTimeUs = index * frameDurationUs
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                scaledBitmap.recycle()
                bitmap.recycle()
            }

            encoder.signalEndOfInputStream()

            // Vidage final
            var finalIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            while (finalIndex >= 0) {
                if (finalIndex != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputBuffer = encoder.getOutputBuffer(finalIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(finalIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                finalIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }

            writeLog(outputPath, "SUCCESS : Encodage terminé.")
            return true

        } catch (e: Exception) {
            writeLog(outputPath, "CRASH : ${e.localizedMessage}")
            return false
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
                if (muxerStarted) {
                    muxer?.stop()
                    muxer?.release()
                }
                inputSurface?.release()
            } catch (e: Exception) {}
            MediaScannerConnection.scanFile(context, arrayOf(outputPath), null, null)
        }
    }
}