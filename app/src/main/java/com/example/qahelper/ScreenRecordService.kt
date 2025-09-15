package com.example.qahelper

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioRecord: AudioRecord? = null

    private var audioRecordThread: Thread? = null
    private var videoDrainThread: Thread? = null
    private var audioDrainThread: Thread? = null


    @Volatile
    private var isRecording = false
    private val muxerStarted = AtomicBoolean(false)
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    private val screenDensity by lazy { resources.displayMetrics.densityDpi }
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private val screenHeight by lazy { resources.displayMetrics.heightPixels }
    private val isAudioPermissionGranted by lazy {
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    private val muxerLock = java.lang.Object()

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                // ▼▼▼▼▼ [수정됨] ▼▼▼▼▼
                // '준비' 단계에서만 mediaProjection 객체를 생성합니다.
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

                if (data != null && mediaProjection == null) { // mediaProjection이 없을 때만 생성
                    startForegroundWithNotification()
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            if (isRecording) stopRecording()
                        }
                    }, Handler(Looper.getMainLooper()))
                    // 준비만 하고 대기 상태
                } else if (data == null) {
                    stopSelf()
                }
            }
            ACTION_START -> {
                // ▼▼▼▼▼ [수정됨] ▼▼▼▼▼
                // '시작' 단계에서는 이미 만들어진 mediaProjection 객체를 사용하기만 합니다.
                // 더 이상 resultCode와 data를 여기서 받지 않습니다.
                if (!isRecording && mediaProjection != null) { // mediaProjection 객체가 있는지 확인
                    isRecording = true
                    // 별도의 스레드에서 녹화 시작
                    Thread { startRecording() }.start()
                } else if (mediaProjection == null) {
                    // 준비가 안 된 상태이므로 서비스 종료
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                if (isRecording) stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        try {
            setupMuxer()
            setupVideoEncoder()
            if (isAudioPermissionGranted) {
                setupAudioComponents()
            }

            videoDrainThread = Thread { drainEncoder(videoEncoder, false) }.apply { start() }
            if (isAudioPermissionGranted && audioEncoder != null) {
                audioDrainThread = Thread { drainEncoder(audioEncoder, true) }.apply { start() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            releaseResources()
        }
    }

    private fun setupMuxer() {
        val outputPath = generateFilePath("record.mp4")
        mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8 * 1024 * 1024)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecordService", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, createInputSurface(), null, null
            )
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioComponents() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            val audioPlaybackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val audioRecordFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBufferSize = AudioRecord.getMinBufferSize(audioRecordFormat.sampleRate, audioRecordFormat.channelMask, audioRecordFormat.encoding)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioRecordFormat)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(audioPlaybackConfig)
                .build().also {
                    if (it.state != AudioRecord.STATE_INITIALIZED) {
                        it.release(); throw IllegalStateException("AudioRecord 초기화 실패")
                    }
                    it.startRecording()
                }

            audioRecordThread = Thread {
                val buffer = ByteArray(4096)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) feedAudioEncoder(buffer, read)
                }
            }
            audioRecordThread?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            audioEncoder?.release(); audioEncoder = null
        }
    }

    private fun feedAudioEncoder(data: ByteArray, length: Int) {
        if (!isRecording) return
        try {
            audioEncoder?.let { encoder ->
                val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    encoder.getInputBuffer(inputBufferIndex)?.apply {
                        clear()
                        put(data, 0, length)
                        encoder.queueInputBuffer(inputBufferIndex, 0, length, System.nanoTime() / 1000, 0)
                    }
                }
            }
        } catch (e: Exception) {
            // 무시
        }
    }

    private fun drainEncoder(encoder: MediaCodec?, isAudio: Boolean) {
        if (encoder == null) return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            try {
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        val trackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        if (isAudio) audioTrackIndex = trackIndex else videoTrackIndex = trackIndex
                        val audioReady = !isAudioPermissionGranted || audioEncoder == null || audioTrackIndex != -1
                        val videoReady = videoTrackIndex != -1
                        if (audioReady && videoReady && muxerStarted.compareAndSet(false, true)) {
                            mediaMuxer!!.start()
                            muxerLock.notifyAll()
                        }
                    }
                } else if (outputIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outputIndex)
                    if (encodedData != null) {
                        synchronized(muxerLock) {
                            if (!muxerStarted.get()) {
                                try {
                                    muxerLock.wait(100)
                                } catch (_: InterruptedException) {}
                            }
                        }
                        if (muxerStarted.get() && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val trackIndex = if (isAudio) audioTrackIndex else videoTrackIndex
                            mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!isRecording) break
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    // #################### 🔽 FIX: 안정적인 종료 로직으로 수정 🔽 ####################
    private fun stopRecording() {
        isRecording = false
        Thread {
            try {
                // 오디오 인코더(Buffer 방식)에만 종료 신호 전송
                audioEncoder?.signalEndOfInputStream()

                // 데이터 처리 스레드들이 모두 종료될 때까지 대기
                audioRecordThread?.join()
                videoDrainThread?.join()
                audioDrainThread?.join()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 모든 리소스 해제
                releaseResources()
            }
        }.start()
    }

    private fun releaseResources() {
        // 리소스 해제 순서가 중요함
        try {
            mediaProjection?.stop()
        } catch (e: Exception) { e.printStackTrace() }
        mediaProjection = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) { e.printStackTrace() }
        virtualDisplay = null

        try {
            videoEncoder?.stop(); videoEncoder?.release()
        } catch (e: Exception) { e.printStackTrace() }
        videoEncoder = null

        try {
            audioRecord?.stop(); audioRecord?.release()
        } catch (e: Exception) { e.printStackTrace() }
        audioRecord = null

        try {
            audioEncoder?.stop(); audioEncoder?.release()
        } catch (e: Exception) { e.printStackTrace() }
        audioEncoder = null

        try {
            if (muxerStarted.get()) mediaMuxer?.stop()
            mediaMuxer?.release()
        } catch (e: Exception) { e.printStackTrace() }
        mediaMuxer = null

        stopForeground(true)
        stopSelf()
    }
    // ##########################################################################

    private fun generateFilePath(fileName: String): String {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "${timestamp}_$fileName").absolutePath
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("화면 녹화 중")
            .setContentText("앱이 화면과 오디오를 녹화하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Screen Record Channel", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_PREPARE = "ACTION_PREPARE"  // 추가된 상수
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "ScreenRecordChannel"
    }
}