package com.example.qahelper

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoder(
    private val mediaProjection: MediaProjection,
    private val muxerWrapper: MuxerWrapper,
    private val listener: EncoderListener
) : Thread("VideoEncoder") {

    interface EncoderListener {
        fun onTrackReady()
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val isRunning = AtomicBoolean(true)
    private var trackIndex = -1

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val WIDTH = 720
        private const val HEIGHT = 1280
        private const val BIT_RATE = 6_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 5
        private const val TIMEOUT_US = 10_000L
    }

    fun stopEncoding() {
        isRunning.set(false)
    }

    override fun run() {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecord", WIDTH, HEIGHT, 1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            while (isRunning.get()) {
                val codec = mediaCodec ?: break
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxerWrapper.addTrack(codec.outputFormat, true)
                        listener.onTrackReady() // íŠ¸ëž™ ì¤€ë¹„ ì™„ë£Œ ì‹ í˜¸ ì „ì†¡
                    }
                    outputBufferIndex >= 0 -> {
                        val encodedData = codec.getOutputBuffer(outputBufferIndex)
                        if (encodedData != null && muxerWrapper.isStarted() && bufferInfo.size > 0 && bufferInfo.presentationTimeUs > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxerWrapper.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Encoding failed", e)
        } finally {
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
                virtualDisplay?.release()
                inputSurface?.release()
            } catch (e: Exception) {
                Log.e("VideoEncoder", "Error releasing resources", e)
            }
        }
    }
}