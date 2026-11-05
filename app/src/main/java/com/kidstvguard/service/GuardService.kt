package com.kidstvguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kidstvguard.R
import com.kidstvguard.data.PrefsManager
import com.kidstvguard.data.QuestionRepository
import com.kidstvguard.overlay.QuizOverlayController
import com.kidstvguard.ui.MainActivity
import com.kidstvguard.util.UsageStatsHelper

/**
 * 常驻前台服务：
 *  - 每秒 tick 一次，判断当前前台是否是视频 APP，是则累加 [PrefsManager.currentWatchElapsedMs]。
 *  - 累计到 [PrefsManager.intervalMillis] 且弹窗开关开启 → 弹题。
 *  - 服务启动时若读到 pendingPopupOnBoot=true，则立即弹题（覆盖"未答就关机再开机"场景）。
 *  - 若配置了远程题库 URL：启动后延迟 3 秒拉一次；每 [PrefsManager.autoSyncHours] 小时定时拉。
 *  - HDMI 无法叠加时，计时仍继续；用户切回任意 Android 视频 APP 前台的瞬间即会弹。
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsManager
    private lateinit var repo: QuestionRepository
    private lateinit var controller: QuizOverlayController

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (_: Throwable) {
            } finally {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    private val syncTask = object : Runnable {
        override fun run() {
            val url = prefs.questionBankUrl
            if (url.isNotBlank()) {
                val gradeLabel = prefs.selectedGradeLabel()
                val resolved = repo.resolveUrl()
                Log.i(TAG, "同步远程题库 [$gradeLabel] → $resolved")
                repo.syncFromRemote(url) { r ->
                    Log.i(TAG, "远程题库同步结果：${r.fold({ "成功 $it 道" }, { "失败 ${it.message}" })}")
                    refreshNotification()
                }
            }
            val hours = prefs.autoSyncHours
            if (hours > 0) {
                handler.postDelayed(this, hours * 3_600_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager.get(this)
        repo = QuestionRepository.get(this)
        controller = QuizOverlayController(this)
        controller.onCleared = {
            prefs.resetWatch()
            refreshNotification()
        }
        controller.onLocked = { refreshNotification() }
        repo.reload()

        startForeground(NOTIF_ID, buildNotification())

        // 关键：开机 / 服务重启后，如果上次遗留了未答完的题，立刻弹
        if (prefs.pendingPopupOnBoot && prefs.popupEnabled && controller.canDrawOverlay()) {
            handler.postDelayed({
                if (!controller.isShowing) controller.show(bootPending = true)
            }, BOOT_POPUP_DELAY_MS)
        }

        handler.post(tick)

        // 远程题库：启动后延迟 3 秒拉一次（等网络就绪），随后按周期定时拉
        if (prefs.questionBankUrl.isNotBlank()) {
            handler.postDelayed(syncTask, INITIAL_SYNC_DELAY_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_POP_NOW -> {
                if (controller.canDrawOverlay() && !controller.isShowing) {
                    controller.show(bootPending = false)
                }
            }
            ACTION_SYNC_NOW -> {
                handler.removeCallbacks(syncTask)
                handler.post(syncTask)
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(syncTask)
        // 服务被杀但仍有未答完的题 → 保留 pendingPopupOnBoot=true，下次开机 / 重启服务时立即弹
        controller.dismissSilently()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onTick() {
        if (!prefs.popupEnabled) return
        if (controller.isShowing) return // 正在答题，不重复弹

        val fgPkg = UsageStatsHelper.getForegroundPackage(this)
        val videoFg = UsageStatsHelper.isVideoForeground(fgPkg, packageName)

        if (videoFg) {
            prefs.currentWatchElapsedMs = prefs.currentWatchElapsedMs + TICK_INTERVAL_MS
            if (prefs.currentWatchElapsedMs >= prefs.intervalMillis) {
                if (controller.canDrawOverlay()) {
                    controller.show(bootPending = false)
                }
                // 弹不出（没权限）时不清零，等待用户授权后自动补弹
            }
        } else {
            // 前台不是视频 APP：暂停计时（不清零，保留累计进度）
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (prefs.popupEnabled) {
            getString(R.string.notif_text, prefs.intervalMinutes)
        } else {
            "弹窗已关闭"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "guard_service"
        private const val NOTIF_ID = 1001
        private const val TICK_INTERVAL_MS = 1000L
        private const val BOOT_POPUP_DELAY_MS = 1500L
        private const val INITIAL_SYNC_DELAY_MS = 3000L

        const val ACTION_POP_NOW = "com.kidstvguard.action.POP_NOW"
        const val ACTION_SYNC_NOW = "com.kidstvguard.action.SYNC_NOW"
        const val ACTION_STOP = "com.kidstvguard.action.STOP"

        fun start(context: Context) {
            val i = Intent(context, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun popNow(context: Context) {
            val i = Intent(context, GuardService::class.java).setAction(ACTION_POP_NOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun syncNow(context: Context) {
            val i = Intent(context, GuardService::class.java).setAction(ACTION_SYNC_NOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GuardService::class.java).setAction(ACTION_STOP))
        }
    }
}
