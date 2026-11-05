package com.kidstvguard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kidstvguard.R
import com.kidstvguard.data.PrefsManager
import com.kidstvguard.data.QuestionRepository
import com.kidstvguard.overlay.QuizOverlayController
import com.kidstvguard.service.GuardService
import com.kidstvguard.service.GuardServiceRunning
import com.kidstvguard.util.UsageStatsHelper

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var repo: QuestionRepository
    private lateinit var controller: QuizOverlayController

    private lateinit var tvState: TextView
    private lateinit var tvInterval: TextView
    private lateinit var tvPending: TextView
    private lateinit var tvBank: TextView

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager.get(this)
        repo = QuestionRepository.get(this)
        controller = QuizOverlayController(this)

        tvState = findViewById(R.id.tv_state)
        tvInterval = findViewById(R.id.tv_interval_info)
        tvPending = findViewById(R.id.tv_pending_info)
        tvBank = findViewById(R.id.tv_bank_info)

        findViewById<Button>(R.id.btn_perm_overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                controller.openOverlaySettings()
            } else {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btn_perm_usage).setOnClickListener {
            if (!UsageStatsHelper.hasUsageStatsPermission(this)) {
                UsageStatsHelper.openUsageStatsSettings(this)
            } else {
                Toast.makeText(this, "使用情况访问已授予", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btn_perm_notification).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "当前系统无需该权限", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!checkAllPermissions(showToast = true)) return@setOnClickListener
            GuardService.start(this)
            Toast.makeText(this, "守护已启动", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            GuardService.stop(this)
            Toast.makeText(this, "守护已停止", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_test_popup).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // 试弹不写 pendingPopupOnBoot，避免测试完关机导致再次开机又弹
            GuardService.start(this)
            GuardService.popNow(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun checkAllPermissions(showToast: Boolean): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            if (showToast) Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            return false
        }
        if (!UsageStatsHelper.hasUsageStatsPermission(this)) {
            if (showToast) Toast.makeText(this, "请先授予使用情况访问权限", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = UsageStatsHelper.hasUsageStatsPermission(this)
        val running = GuardServiceRunning.isRunning(this)

        tvState.text = "守护状态：" + when {
            !prefs.popupEnabled -> "已关闭（弹窗开关关）"
            running && overlayOk && usageOk -> "运行中 ✓"
            running -> "运行中，但权限缺失"
            else -> "未启动"
        }
        tvInterval.text = "间隔：${prefs.intervalMinutes} 分钟 · 每轮最多 ${prefs.maxQuestionsPerRound} 题 · 单题 ${prefs.perQuestionTimeoutSec} 秒"
        tvPending.text = "断电续弹：" + if (prefs.pendingPopupOnBoot)
            "有未答完的题，下次开机会立即弹"
        else
            "无遗留"
        repo.reload()
        val n = repo.questions().size
        tvBank.text = "题库：$n 道题 · 来源 ${repo.sourceLabel()}"

        findViewById<Button>(R.id.btn_perm_overlay).text =
            (if (overlayOk) "✓ " else "✗ ") + "1. 悬浮窗权限（弹题必需）"
        findViewById<Button>(R.id.btn_perm_usage).text =
            (if (usageOk) "✓ " else "✗ ") + "2. 使用情况访问（识别视频 APP 前台）"
    }
}
