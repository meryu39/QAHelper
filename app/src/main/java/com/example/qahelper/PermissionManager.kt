package com.example.qahelper

import android.content.Intent

// 앱 전역에서 MediaProjection 권한 데이터를 공유하기 위한 싱글턴 객체
object PermissionManager {
    var resultCode: Int = 0
    var data: Intent? = null
}