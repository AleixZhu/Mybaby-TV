package com.kidstvguard.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kidstvguard.R
import com.kidstvguard.data.PrefsManager
import com.kidstvguard.data.QuestionRepository

/**
 * 设置页。进入前弹密码框，密码正确才展示内容。
 * 覆盖需求：
 *  - 弹窗开关（enable/disable）
 *  - 间隔 5/10/15/20/30 分钟（修改需密码 → 通过入口密码门实现）
 *  - 每轮最多题数、单题时长
 *  - 题库导入 / 恢复内置
 *  - 修改密码
 */
@Suppress("DEPRECATION")
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var repo: QuestionRepository

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val r = repo.importFromUri(uri)
            r.onSuccess { n ->
                Toast.makeText(this, "已导入 $n 道题", Toast.LENGTH_LONG).show()
                refreshBank()
            }.onFailure { t ->
                Toast.makeText(this, "导入失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager.get(this)
        repo = QuestionRepository.get(this)
        showPasswordGate()
    }

    private fun showPasswordGate() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_password, null)
        val et = view.findViewById<EditText>(R.id.et_pwd)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消") { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = et.text?.toString().orEmpty()
                if (prefs.verifyPassword(input)) {
                    dialog.dismiss()
                    showContent()
                } else {
                    et.error = "密码错误"
                }
            }
        }
        dialog.show()
        et.requestFocus()
    }

    private fun showContent() {
        setContentView(R.layout.activity_settings)
        bindWidgets()
    }

    private fun bindWidgets() {
        // 开关
        val sw = findViewById<Switch>(R.id.sw_enable)
        sw.isChecked = prefs.popupEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.popupEnabled = checked
            Toast.makeText(this, if (checked) "弹题已启用" else "弹题已关闭", Toast.LENGTH_SHORT).show()
        }

        // 间隔
        val rg = findViewById<RadioGroup>(R.id.rg_interval)
        val map = mapOf(
            5 to R.id.rb_5,
            10 to R.id.rb_10,
            15 to R.id.rb_15,
            20 to R.id.rb_20,
            30 to R.id.rb_30
        )
        rg.check(map[prefs.intervalMinutes] ?: R.id.rb_15)
        rg.setOnCheckedChangeListener { _, checkedId ->
            val entry = map.entries.firstOrNull { it.value == checkedId } ?: return@setOnCheckedChangeListener
            prefs.intervalMinutes = entry.key
            Toast.makeText(this, "间隔已改为 ${entry.key} 分钟", Toast.LENGTH_SHORT).show()
        }

        // 年级选择
        bindGradeSelector()

        // 每轮最多题数
        val tvMax = findViewById<TextView>(R.id.tv_max)
        tvMax.text = prefs.maxQuestionsPerRound.toString()
        findViewById<Button>(R.id.btn_max_dec).setOnClickListener {
            val v = (prefs.maxQuestionsPerRound - 1).coerceAtLeast(1)
            prefs.maxQuestionsPerRound = v
            tvMax.text = v.toString()
        }
        findViewById<Button>(R.id.btn_max_inc).setOnClickListener {
            val v = (prefs.maxQuestionsPerRound + 1).coerceAtMost(20)
            prefs.maxQuestionsPerRound = v
            tvMax.text = v.toString()
        }

        // 单题时长
        val tvTo = findViewById<TextView>(R.id.tv_to)
        tvTo.text = prefs.perQuestionTimeoutSec.toString()
        findViewById<Button>(R.id.btn_to_dec).setOnClickListener {
            val v = (prefs.perQuestionTimeoutSec - 10).coerceAtLeast(10)
            prefs.perQuestionTimeoutSec = v
            tvTo.text = v.toString()
        }
        findViewById<Button>(R.id.btn_to_inc).setOnClickListener {
            val v = (prefs.perQuestionTimeoutSec + 10).coerceAtMost(600)
            prefs.perQuestionTimeoutSec = v
            tvTo.text = v.toString()
        }

        // 题库
        refreshBank()
        findViewById<Button>(R.id.btn_import).setOnClickListener {
            try {
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            } catch (t: Throwable) {
                Toast.makeText(this, "打开文件选择器失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btn_reset_bank).setOnClickListener {
            repo.clearExternal()
            Toast.makeText(this, "已恢复内置题库", Toast.LENGTH_SHORT).show()
            refreshBank()
        }

        // 远程题库
        bindRemoteSync()

        // 修改密码
        val etOld = findViewById<EditText>(R.id.et_old)
        val etNew = findViewById<EditText>(R.id.et_new)
        findViewById<Button>(R.id.btn_change_pwd).setOnClickListener {
            val old = etOld.text?.toString().orEmpty()
            val new = etNew.text?.toString().orEmpty()
            if (new.length < 4) {
                Toast.makeText(this, "新密码至少 4 位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (prefs.changePassword(old, new)) {
                etOld.text?.clear()
                etNew.text?.clear()
                Toast.makeText(this, "密码已更新", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "当前密码不正确", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshBank() {
        repo.reload()
        val n = repo.questions().size
        val effective = repo.questionsForCurrentGrade().size
        findViewById<TextView>(R.id.tv_bank).text =
            "题库：$n 道题（当前年级命中 $effective）· 来源 ${repo.sourceLabel()}"
        refreshSyncLog()
        refreshUrlPreview()
    }

    private fun bindGradeSelector() {
        val tvGrade = findViewById<TextView>(R.id.tv_grade)
        val grades = PrefsManager.ALLOWED_GRADES

        fun render() {
            tvGrade.text = prefs.selectedGradeLabel()
        }
        render()

        findViewById<Button>(R.id.btn_grade_dec).setOnClickListener {
            val cur = prefs.selectedGrade
            val idx = grades.indexOfLast { it < cur }
            prefs.selectedGrade = if (idx >= 0) grades[idx] else 0
            onGradeChanged()
            render()
        }
        findViewById<Button>(R.id.btn_grade_inc).setOnClickListener {
            val cur = prefs.selectedGrade
            val idx = grades.indexOfFirst { it > cur }
            prefs.selectedGrade = if (idx >= 0) grades[idx] else 9
            onGradeChanged()
            render()
        }
    }

    private fun onGradeChanged() {
        refreshBank()
        Toast.makeText(this, "已切换到 ${prefs.selectedGradeLabel()}", Toast.LENGTH_SHORT).show()
        // 有远程 URL 时立刻拉一次新年级的题库
        if (prefs.questionBankUrl.isNotBlank()) {
            com.kidstvguard.service.GuardService.syncNow(this)
        }
    }

    private fun refreshUrlPreview() {
        val tv = findViewById<TextView>(R.id.tv_url_preview) ?: return
        val raw = prefs.questionBankUrl
        if (raw.isBlank()) {
            tv.text = "远程 URL 预览：未配置"
            return
        }
        val resolved = repo.resolveUrl()
        val tag = if (repo.urlHasTemplate()) "模板" else "静态"
        tv.text = "远程 URL 预览（$tag）：$resolved"
    }

    private fun bindRemoteSync() {
        val etUrl = findViewById<EditText>(R.id.et_url)
        etUrl.setText(prefs.questionBankUrl)

        val tvHours = findViewById<TextView>(R.id.tv_sync_hours)
        val hoursSteps = intArrayOf(0, 1, 6, 12, 24, 48, 72, 168)
        fun renderHours() {
            val h = prefs.autoSyncHours
            tvHours.text = if (h <= 0) "仅启动时" else "$h 小时"
        }
        renderHours()
        findViewById<Button>(R.id.btn_sync_dec).setOnClickListener {
            val cur = prefs.autoSyncHours
            val idx = hoursSteps.indexOfLast { it < cur }
            prefs.autoSyncHours = if (idx >= 0) hoursSteps[idx] else 0
            renderHours()
        }
        findViewById<Button>(R.id.btn_sync_inc).setOnClickListener {
            val cur = prefs.autoSyncHours
            val idx = hoursSteps.indexOfFirst { it > cur }
            prefs.autoSyncHours = if (idx >= 0) hoursSteps[idx] else 168
            renderHours()
        }

        val btnSync = findViewById<Button>(R.id.btn_sync_now)
        btnSync.setOnClickListener {
            val url = etUrl.text?.toString()?.trim().orEmpty()
            prefs.questionBankUrl = url
            if (url.isEmpty()) {
                Toast.makeText(this, "URL 已清空，远程同步关闭", Toast.LENGTH_SHORT).show()
                refreshSyncLog()
                return@setOnClickListener
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                Toast.makeText(this, "URL 必须以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnSync.isEnabled = false
            btnSync.text = "同步中…"
            repo.syncFromRemote(url) { r ->
                btnSync.isEnabled = true
                btnSync.text = "保存 URL 并立即同步"
                r.onSuccess { n ->
                    Toast.makeText(this, "已拉取 $n 道题", Toast.LENGTH_LONG).show()
                }.onFailure { t ->
                    Toast.makeText(this, "同步失败：${t.message}", Toast.LENGTH_LONG).show()
                }
                refreshBank()
                // 通知服务重新排程
                com.kidstvguard.service.GuardService.syncNow(this)
            }
        }

        findViewById<Button>(R.id.btn_clear_remote).setOnClickListener {
            repo.clearRemoteCache()
            Toast.makeText(this, "远程缓存已清除", Toast.LENGTH_SHORT).show()
            refreshBank()
        }

        refreshSyncLog()
    }

    private fun refreshSyncLog() {
        val tv = findViewById<TextView>(R.id.tv_sync_log) ?: return
        val url = prefs.questionBankUrl
        val prefix = if (url.isBlank()) "未启用远程同步。" else "URL：$url\n"
        tv.text = prefix + "最近同步：" + repo.lastSyncSummary()
    }

    override fun onBackPressed() {
        // 从设置退出回到主页时，不需要额外确认
        super.onBackPressed()
    }
}
