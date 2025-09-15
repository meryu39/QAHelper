package com.example.qahelper

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class AudioEncoder(
    private val mediaProjection: MediaProjection,
    private val muxerWrapper: MuxerWrapper,
    private val listener: EncoderListener
) : Thread("AudioEncoder") {

    interface EncoderListener {
        fun onTrackReady()
    }

    private lateinit var mediaCodec: MediaCodec
    private lateinit var audioRecord: AudioRecord
    private val bufferInfo = MediaCodec.BufferInfo()
    private val isRunning = AtomicBoolean(true)
    private var trackIndex = -1

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val BIT_RATE = 128_000
        private const val TIMEOUT_US = 10_000L
    }

    fun stopEncoding() {
        isRunning.set(false)
    }

    override fun run() {
        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupAudioRecordingAPI29()
            } else {
                setupMicrophoneRecording()
            }

            audioRecord.startRecording()

            while (isRunning.get()) {
                val inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex)!!
                    val size = audioRecord.read(inputBuffer, inputBuffer.capacity())
                    if (size > 0) {
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, size, System.nanoTime() / 1000, 0)
                    }
                }

                var outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxerWrapper.addTrack(mediaCodec.outputFormat, false)
                            listener.onTrackReady() // íŠ¸ëž™ ì¤€ë¹„ ì™„ë£Œ ì‹ í˜¸ ì „ì†¡
                        }
                        outputBufferIndex >= 0 -> {
                            val encodedData = mediaCodec.getOutputBuffer(outputBufferIndex)
                            if (encodedData != null && muxerWrapper.isStarted() && bufferInfo.size > 0 && bufferInfo.presentationTimeUs > 0) {
                                muxerWrapper.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                    outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioEncoder", "Encoding failed", e)
        } finally {
            try {
                if (::audioRecord.isInitialized) {
                    audioRecord.stop()
                    audioRecord.release()
                }
                if (::mediaCodec.isInitialized) {
                    mediaCodec.stop()
                    mediaCodec.release()
                }
            } catch (e: Exception) {
                Log.e("AudioEncoder", "Error releasing resources", e)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun setupAudioRecordingAPI29() {
        val audioCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setAudioPlaybackCaptureConfig(audioCaptureConfig)
            .setBufferSizeInBytes(bufferSize * 2)
            .build()
    }

    private fun setupMicrophoneRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
    }
}