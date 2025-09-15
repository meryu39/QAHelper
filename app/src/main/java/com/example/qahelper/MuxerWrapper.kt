package com.example.qahelper

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

class MuxerWrapper(outputFilePath: String) {

    companion object {
        private const val TAG = "MuxerWrapper"
    }

    private val mediaMuxer: MediaMuxer
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isStarted = false

    init {
        try {
            mediaMuxer = MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            Log.e(TAG, "MediaMuxer creation failed", e)
            throw RuntimeException("MediaMuxer creation failed", e)
        }
    }

    @Synchronized
    fun addTrack(format: MediaFormat, isVideo: Boolean): Int {
        if (isStarted) {
            throw IllegalStateException("Muxer already started")
        }
        val trackIndex = mediaMuxer.addTrack(format)
        if (isVideo) {
            videoTrackIndex = trackIndex
            Log.d(TAG, "Video track added. Index: $trackIndex")
        } else {
            audioTrackIndex = trackIndex
            Log.d(TAG, "Audio track added. Index: $trackIndex")
        }
        return trackIndex
    }

    @Synchronized
    fun start() {
        if (videoTrackIndex != -1 && audioTrackIndex != -1) {
            Log.d(TAG, "Starting MediaMuxer")
            mediaMuxer.start()
            isStarted = true
        } else {
            Log.w(TAG, "Muxer cannot be started. Tracks not ready.")
        }
    }

    @Synchronized
    fun stop() {
        if (isStarted) {
            Log.d(TAG, "Stopping MediaMuxer")
            try {
                mediaMuxer.stop()
                mediaMuxer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping or releasing muxer", e)
            }
        }
        isStarted = false
    }

    @Synchronized
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (isStarted && trackIndex != -1) {
            try {
                mediaMuxer.writeSampleData(trackIndex, byteBuf, bufferInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write sample data for track $trackIndex", e)
            }
        }
    }

    fun isStarted(): Boolean {
        return isStarted
    }
}