package com.example.urban_meme

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.example.urban_meme/video_encoder"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "createVideo" -> {
                    val framePaths = call.argument<List<String>>("framePaths")
                    val outputPath = call.argument<String>("outputPath")
                    val width = call.argument<Int>("width") ?: 1280
                    val height = call.argument<Int>("height") ?: 720
                    val fps = call.argument<Int>("fps") ?: 2
                    
                    if (framePaths != null && outputPath != null) {
                        try {
                            val success = createVideoFromFrames(framePaths, outputPath, width, height, fps)
                            result.success(success)
                        } catch (e: Exception) {
                            result.error("VIDEO_ERROR", e.message, null)
                        }
                    } else {
                        result.error("INVALID_ARGS", "Missing arguments", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun createVideoFromFrames(
        framePaths: List<String>,
        outputPath: String,
        width: Int,
        height: Int,
        fps: Int
    ): Boolean {
        return try {
            val mimeType = "video/avc"
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            
            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            
            val bufferInfo = MediaCodec.BufferInfo()
            var frameIndex = 0
            val frameDurationUs = (1000000 / fps).toLong()
            
            for (framePath in framePaths) {
                val bitmap = BitmapFactory.decodeFile(framePath)
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                
                val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    
                    val yuvData = bitmapToYUV420(scaledBitmap, width, height)
                    inputBuffer?.put(yuvData)
                    
                    val presentationTimeUs = frameIndex * frameDurationUs
                    encoder.queueInputBuffer(inputBufferIndex, 0, yuvData.size, presentationTimeUs, 0)
                }
                
                scaledBitmap.recycle()
                bitmap.recycle()
                
                var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputBufferIndex >= 0) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                
                frameIndex++
            }
            
            val inputBufferIndex = encoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            
            var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            while (outputBufferIndex >= 0) {
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            }
            
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun bitmapToYUV420(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        
        val yuvSize = width * height * 3 / 2
        val yuv = ByteArray(yuvSize)
        
        var yIndex = 0
        var uvIndex = width * height
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val R = (argb[j * width + i] shr 16) and 0xFF
                val G = (argb[j * width + i] shr 8) and 0xFF
                val B = argb[j * width + i] and 0xFF
                
                val Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                val U = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128
                val V = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128
                
                yuv[yIndex++] = Y.coerceIn(0, 255).toByte()
                
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = U.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = V.coerceIn(0, 255).toByte()
                }
            }
        }
        
        return yuv
    }
}
