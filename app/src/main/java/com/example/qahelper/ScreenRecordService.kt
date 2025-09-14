package com.example.qahelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class ScreenRecordService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var mediaRecorder: MediaRecorder

    private var screenDensity: Int = 0
    private var displayWidth: Int = 1920
    private var displayHeight: Int = 1080

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            stopRecording()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        thread {
            startRecording(intent)
        }

        return START_STICKY
    }

    private fun startRecording(intent: Intent?) {
        intent?.let {
            val resultCode = it.getIntExtra("resultCode", -1)
            val data: Intent? = it.getParcelableExtra("data")

            if (data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

                val metrics = resources.displayMetrics
                screenDensity = metrics.densityDpi
                displayWidth = metrics.widthPixels
                displayHeight = metrics.heightPixels

                // 1. MediaRecorder를 먼저 초기화합니다. (구조 수정)
                initRecorder()

                // 2. 안드로이드 10 이상에서만 mediaProjection을 이용해 내부 오디오 캡처를 설정합니다. (구조 수정)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build()
                    mediaRecorder.setAudioPlaybackCaptureConfig(config)
                }

                createVirtualDisplay()

                try {
                    mediaRecorder.start()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    stopSelf()
                }
            } else {
                stopSelf()
            }
        } ?: stopSelf()
    }

    private fun initRecorder() {
        // minSdk가 29이므로 버전 체크를 단순화하거나 제거할 수 있습니다.
        // 현재 코드는 하위 호환성을 위해 남겨둡니다.
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val videoPath = generateFilePath()
        mediaRecorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            // minSdk가 29이므로 사실상 이 코드만 실행됩니다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 'PLAYBACK'이 아닌 'VOICE_UPLINK'가 올바른 오디오 소스입니다. (오류 수정)
                setAudioSource(MediaRecorder.AudioSource.VOICE_UPLINK)
            }

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoPath)
            setVideoSize(displayWidth, displayHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }

            setVideoEncodingBitRate(8 * 1024 * 1024)
            setVideoFrameRate(30)
            try {
                prepare()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun generateFilePath(): String {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "${directory.absolutePath}/record_${sdf.format(Date())}.mp4"
    }

    private fun createVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecordService",
            displayWidth,
            displayHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    private fun stopRecording() {
        try {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaRecorder.stop()
            mediaRecorder.reset()
            mediaRecorder.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("화면 녹화 중")
            .setContentText("앱이 화면을 녹화하고 있습니다.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Record Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenRecordChannel"
    }
}