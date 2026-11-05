package com.kidstvguard.service

import android.app.ActivityManager
import android.content.Context

/** 判断 GuardService 是否已启动（供 UI 展示状态）。 */
object GuardServiceRunning {
    fun isRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (s in am.getRunningServices(Int.MAX_VALUE)) {
            if (s.service.className == GuardService::class.java.name) return true
        }
        return false
    }
}
