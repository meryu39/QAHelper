package com.example.qahelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    companion object {
        private const val TAG = "QAHelper"
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1000 // 화면 캡처 권한 요청 코드
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001 // 알림 권한 요청 코드
    }

    // 화면 캡처를 관리하는 시스템 서비스
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // MediaProjectionManager 초기화
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 알림 권한 확인 및 요청
        checkAndRequestNotificationPermission()

        val btnGrantPermission = findViewById<Button>(R.id.btn_grant_permission)
        btnGrantPermission.setOnClickListener {
            Log.d(TAG, "Permission button clicked")
            // 화면 캡처 권한을 요청하는 다이얼로그를 띄우기 위한 Intent 생성
            val captureIntent = projectionManager.createScreenCaptureIntent()
            // 권한 요청 다이얼로그 시작. 결과는 onActivityResult에서 처리
            startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE)
        }
    }

    /**
     * Android 13 (TIRAMISU) 이상에서 알림 권한이 있는지 확인하고, 없으면 요청합니다.
     * 포그라운드 서비스는 사용자에게 상태를 알리기 위해 알림을 사용해야 합니다.
     */
    private fun checkAndRequestNotificationPermission() {
        // API 레벨 33 이상에서만 POST_NOTIFICATIONS 권한이 필요
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // 권한이 없다면 사용자에게 요청
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * requestPermissions의 결과를 처리하는 콜백 메서드입니다.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            // 권한 요청이 거부된 경우
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "녹화 상태를 표시하려면 알림 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * startActivityForResult의 결과를 처리하는 콜백 메서드입니다.
     * 여기서는 화면 캡처 권한 요청의 결과를 처리합니다.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "Screen capture permission granted. Storing permission and starting service.")

                // ★ CommandBroadcastReceiver가 사용할 수 있도록 PermissionManager에 권한 데이터를 저장합니다.
                PermissionManager.resultCode = resultCode
                PermissionManager.data = data
                // ★ 위 두 줄이 없으면 절대 동작하지 않습니다.

                // '준비' 상태로 서비스를 시작합니다.
                val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_PREPARE
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenRecordService.EXTRA_DATA, data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(this, "녹화 서비스가 준비되었습니다.", Toast.LENGTH_LONG).show()
                finish()

            } else {
                Log.e(TAG, "Screen capture permission denied")
                Toast.makeText(this, "화면 녹화 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}