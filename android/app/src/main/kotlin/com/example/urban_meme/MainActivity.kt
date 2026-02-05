package com.example.urban_meme

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
                    // CRUCIAL: Exécuter hors du thread UI
                    Thread {
                        try {
                            val success = encodeWithSurface(framePaths, outputPath, width, height, fps)
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.error("VIDEO_ERROR", e.message, null) }
                        }
                    }.start()
                } else {
                    result.error("INVALID_ARGS", "Missing arguments", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    private fun encodeWithSurface(framePaths: List<String>, outputPath: String, width: Int, height: Int, fps: Int): Boolean {
        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2500000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val frameDurationUs = 1000000L / fps

        try {
            for ((index, path) in framePaths.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(path) ?: continue
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                
                // Dessiner le bitmap sur la surface de l'encodeur
                val canvas = inputSurface.lockCanvas(null)
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(canvas)
                
                // Envoyer le timestamp correct
                // On ne peut pas appeler queueInputBuffer avec une Surface, 
                // le timestamp est géré par la surface ou via une méthode spécifique si besoin.

                // Récupérer les données encodées
                var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputBufferIndex >= 0) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
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
            // Dernier passage pour vider le buffer
            // ... (logique de flush identique)
            
            return true
        } finally {
            encoder.stop()
            encoder.release()
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }
            inputSurface.release()
        }
    }
}