package com.example.qahelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class CommandBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QA_HELPER_LOG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "[CommandBroadcastReceiver] 브로드캐스트 수신: ${intent.action}")

        when (intent.action) {
            "com.example.qahelper.action.START" -> {
                Log.i(TAG, "[CommandBroadcastReceiver] 녹화 시작 명령 처리")
                startRecording(context)
            }
            "com.example.qahelper.action.STOP" -> {
                Log.i(TAG, "[CommandBroadcastReceiver] 녹화 종료 명령 처리")
                stopRecording(context)
            }
            else -> {
                Log.w(TAG, "[CommandBroadcastReceiver] 알 수 없는 액션: ${intent.action}")
            }
        }
    }

    private fun startRecording(context: Context) {
        if (PermissionManager.resultCode != 0 && PermissionManager.data != null) {
            Log.d(TAG, "[CommandBroadcastReceiver] 저장된 권한 데이터 사용")

            // ▼▼▼ [수정됨] 데이터를 올바른 키로 전달하도록 이 부분을 수정했습니다. ▼▼▼
            val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                // ScreenRecordService가 사용하는 정확한 키로 resultCode와 data를 각각 전달합니다.
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, PermissionManager.resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, PermissionManager.data)
            }
            // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.i(TAG, "[CommandBroadcastReceiver] 녹화 서비스 시작됨")
        } else {
            Log.e(TAG, "[CommandBroadcastReceiver] MediaProjection 권한이 없습니다! 먼저 앱에서 권한을 설정하세요.")
        }
    }

    private fun stopRecording(context: Context) {
        val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        // 서비스를 중지할 때는 포그라운드 여부와 관계없이 startService를 사용해도 됩니다.
        context.startService(serviceIntent)
        Log.i(TAG, "[CommandBroadcastReceiver] 녹화 종료 명령 전송")
    }
}