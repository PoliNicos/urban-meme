package com.example.urban_meme

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.view.Surface
import java.io.File
import android.util.Log

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
                        // On force la résolution à être un multiple de 16 pour éviter le bug 0kb
                        val safeWidth = (width / 16) * 16
                        val safeHeight = (height / 16) * 16
                        
                        val success = encodeSafe(framePaths, outputPath, safeWidth, safeHeight, fps)
                        runOnUiThread { result.success(success) }
                    }.start()
                } else {
                    result.error("ARGS", "Arguments manquants", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    private fun encodeSafe(framePaths: List<String>, outputPath: String, width: Int, height: Int, fps: Int): Boolean {
        try {
            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            
            // Configuration conservatrice pour garantir la compatibilité
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000) // 2 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false
            val frameDurationUs = 1000000L / fps

            for ((i, path) in framePaths.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(path) ?: continue
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                
                val canvas = inputSurface.lockCanvas(null)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(canvas)

                // Drainage de l'encodeur
                var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                while (outIndex >= 0) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val byteBuffer = encoder.getOutputBuffer(outIndex)
                        if (byteBuffer != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                            bufferInfo.presentationTimeUs = i * frameDurationUs
                            muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                scaled.recycle()
                bitmap.recycle()
            }

            encoder.signalEndOfInputStream()
            
            // Vidage final
            var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            while (outIndex >= 0) {
                 if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                     // Cas rare où le format change à la toute fin
                     if (!muxerStarted) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                     }
                 } else if (outIndex >= 0) {
                    val byteBuffer = encoder.getOutputBuffer(outIndex)
                    if (byteBuffer != null && muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                        muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                 }
                 if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                 outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }

            encoder.stop()
            encoder.release()
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            inputSurface.release()
            
            // Scan pour la galerie
            MediaScannerConnection.scanFile(context, arrayOf(outputPath), null, null)
            
            return muxerStarted // Si le muxer n'a jamais démarré, c'est un échec (0kb)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}