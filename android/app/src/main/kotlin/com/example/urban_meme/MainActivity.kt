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
                        try {
                            val success = encodeSafe(framePaths, outputPath, width, height, fps)
                            runOnUiThread { result.success(if (success) "OK" else "Erreur encodage interne") }
                        } catch (e: Exception) {
                            runOnUiThread { result.success("CRASH: ${e.localizedMessage}") }
                        }
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
        // Force multiples de 16
        val w = (width / 16) * 16
        val h = (height / 16) * 16
        
        val file = File(outputPath)
        if (file.exists()) file.delete()

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var muxerStarted = false

        for ((i, path) in framePaths.withIndex()) {
            val bmp = BitmapFactory.decodeFile(path) ?: continue
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            
            val canvas = surface.lockCanvas(null)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)

            var outIdx = encoder.dequeueOutputBuffer(info, 10000)
            while (outIdx >= 0) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (muxerStarted) {
                    val buf = encoder.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        info.presentationTimeUs = i * (1000000L / fps)
                        muxer.writeSampleData(track, buf, info)
                    }
                }
                encoder.releaseOutputBuffer(outIdx, false)
                outIdx = encoder.dequeueOutputBuffer(info, 0)
            }
            scaled.recycle()
            bmp.recycle()
        }

        encoder.signalEndOfInputStream()
        // Drainage final simplifié
        encoder.stop()
        encoder.release()
        if (muxerStarted) {
            muxer.stop()
            muxer.release()
        }
        surface.release()
        return muxerStarted
    }
}