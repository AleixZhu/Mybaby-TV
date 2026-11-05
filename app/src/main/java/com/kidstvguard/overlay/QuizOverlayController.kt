package com.kidstvguard.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.kidstvguard.R
import com.kidstvguard.data.PrefsManager
import com.kidstvguard.data.Question
import com.kidstvguard.data.QuestionRepository

/**
 * 悬浮答题窗控制器（连对模型 v2）。
 *
 * 通关规则：
 *  - 初始必须**连续答对 1 题**才通过；
 *  - 每答错 1 题，`required` +1（同时 correctStreak 归零），最高到 [PrefsManager.maxQuestionsPerRound]（默认 5）；
 *  - 只要 `correctStreak >= required` 就算通关，回调 [onCleared]（Service 用来重置观看计时）。
 *  - 单题倒计时到点 = 该题答错，required 递增、correctStreak 清零，出下一题。
 *
 * 管理员解锁：
 *  - 答题过程中任何时候都可以点「管理员解锁」按钮，弹出密码框；
 *  - 密码正确 → 立即结束本轮（等同家长亲自放行），不再要求孩子答题；
 *  - 密码错误 → 提示后回到题目继续作答，不做惩罚。
 *
 * 断电续弹：
 *  - `show()` 后立刻把 `pendingPopupOnBoot=true` + 当前 `wrongCount` + `correctStreak` 落盘；
 *  - 中途关机 → 下次开机 GuardService 读到 true 就重新弹，并沿用上次的 wrongCount/correctStreak；
 *  - 本轮成功结束（答对/管理员解锁）→ 清空所有 pending 状态。
 */
class QuizOverlayController(private val context: Context) {

    private val prefs = PrefsManager.get(context)
    private val repo = QuestionRepository.get(context)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var countdown: CountDownTimer? = null

    /** 本轮累计答错次数（0..maxQuestionsPerRound）。 */
    private var wrongCount = 0

    /** 当前连对次数（0..required）。 */
    private var correctStreak = 0

    /** 本轮通关所需的连对题数 = min(1 + wrongCount, maxQuestionsPerRound)。 */
    private var required = 1

    /** 当前正在作答的题目。 */
    private var currentQuestion: Question? = null

    /** 最近若干道已用过的题干，避免立刻重复。 */
    private val recentlyUsed = ArrayDeque<String>()
    private val recentHistoryLimit = 6

    var onCleared: (() -> Unit)? = null
    var onLocked: (() -> Unit)? = null

    val isShowing: Boolean
        get() = root != null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("InlinedApi")
    fun openOverlaySettings() {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            val i = android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    /**
     * 启动一轮答题。
     * @param bootPending true = 关机前遗留的场景，沿用持久化的 wrongCount / correctStreak。
     */
    fun show(bootPending: Boolean = false) {
        if (isShowing) return
        if (!canDrawOverlay()) {
            Toast.makeText(context, "未授予悬浮窗权限，无法弹题", Toast.LENGTH_LONG).show()
            return
        }

        val max = prefs.maxQuestionsPerRound.coerceAtLeast(1)
        if (bootPending) {
            wrongCount = prefs.pendingWrongStreak.coerceIn(0, max)
            correctStreak = prefs.pendingCorrectStreak.coerceIn(0, max)
        } else {
            wrongCount = 0
            correctStreak = 0
        }
        required = (1 + wrongCount).coerceAtMost(max)
        // 断电续弹时，若历史连对已超过 required（罕见，例如 required 曾经更高），钳制一下
        if (correctStreak > required) correctStreak = required

        recentlyUsed.clear()

        // 断电续弹标记：本轮未结束前一直保持 true
        prefs.pendingPopupOnBoot = true
        prefs.pendingWrongStreak = wrongCount
        prefs.pendingCorrectStreak = correctStreak

        addRootView()
        nextQuestion()
    }

    /** 抽一道题（尽量避开最近用过的题干），渲染到面板。 */
    private fun nextQuestion() {
        val grade = prefs.selectedGrade
        val candidates = repo.pickRandom(6, grade)
        val fresh = candidates.firstOrNull { it.stem !in recentlyUsed }
            ?: candidates.firstOrNull()

        if (fresh == null) {
            Toast.makeText(context, "当前年级题库为空，请在设置里检查", Toast.LENGTH_LONG).show()
            finishRound(clearedByAnswer = false, silent = true)
            return
        }

        currentQuestion = fresh
        recentlyUsed.addLast(fresh.stem)
        while (recentlyUsed.size > recentHistoryLimit) recentlyUsed.removeFirst()

        renderQuestion()
    }

    private fun addRootView() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.view_quiz_overlay, null)
        root = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            @Suppress("DEPRECATION")
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            root = null
            Toast.makeText(context, "无法添加悬浮窗：${t.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 管理员解锁按钮：任何时刻都可以点，点了展开密码面板
        view.findViewById<Button>(R.id.btn_admin_unlock)?.setOnClickListener {
            showAdminUnlockPanel()
        }
        view.findViewById<Button>(R.id.btn_unlock)?.setOnClickListener {
            onAdminUnlockSubmit()
        }
        view.findViewById<Button>(R.id.btn_unlock_cancel)?.setOnClickListener {
            hideAdminUnlockPanel()
        }

        view.post {
            view.findViewById<Button>(R.id.btn_opt_0)?.requestFocus()
        }
    }

    private fun renderQuestion() {
        val view = root ?: return
        val q = currentQuestion ?: return

        view.findViewById<TextView>(R.id.tv_header).text =
            "${prefs.selectedGradeLabel()} · 答题时间到"
        view.findViewById<TextView>(R.id.tv_progress).text =
            "需连续答对 $required 题 · 已连对 $correctStreak 题（本轮已错 $wrongCount 次）"
        view.findViewById<TextView>(R.id.tv_stem).text = q.stem
        view.findViewById<TextView>(R.id.tv_feedback).text = ""
        view.findViewById<LinearLayout>(R.id.lock_panel).visibility = View.GONE
        view.findViewById<Button>(R.id.btn_admin_unlock)?.apply {
            visibility = View.VISIBLE
            isEnabled = true
        }

        val optIds = intArrayOf(R.id.btn_opt_0, R.id.btn_opt_1, R.id.btn_opt_2, R.id.btn_opt_3)
        val labels = charArrayOf('A', 'B', 'C', 'D')
        for (i in optIds.indices) {
            val btn = view.findViewById<Button>(optIds[i])
            if (i < q.options.size) {
                btn.visibility = View.VISIBLE
                btn.text = "${labels[i]}. ${q.options[i]}"
                btn.isEnabled = true
                btn.setOnClickListener { onAnswer(i) }
            } else {
                btn.visibility = View.INVISIBLE
                btn.setOnClickListener(null)
            }
        }

        startCountdown(view)
    }

    private fun startCountdown(view: View) {
        countdown?.cancel()
        val totalSec = prefs.perQuestionTimeoutSec.coerceAtLeast(10)
        val total = totalSec * 1000L
        val timerView = view.findViewById<TextView>(R.id.tv_timer)
        timerView.setTextColor(0xFFFF8A65.toInt())
        timerView.setTypeface(null, Typeface.NORMAL)
        timerView.text = "剩余 $totalSec 秒"

        countdown = object : CountDownTimer(total, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished + 999) / 1000
                timerView.text = "剩余 $sec 秒"
                if (sec <= 10) {
                    timerView.setTextColor(0xFFFF1744.toInt())
                    timerView.setTypeface(null, Typeface.BOLD)
                }
            }

            override fun onFinish() {
                timerView.text = "时间到"
                timerView.setTextColor(0xFFFF1744.toInt())
                handleWrong("本题 ${totalSec} 秒内未作答")
            }
        }.start()
    }

    private fun onAnswer(picked: Int) {
        val view = root ?: return
        val q = currentQuestion ?: return
        countdown?.cancel()
        disableAllOptions(view)

        val correct = picked == q.answer
        val feedback = view.findViewById<TextView>(R.id.tv_feedback)

        if (correct) {
            correctStreak++
            prefs.pendingCorrectStreak = correctStreak

            feedback.setTextColor(0xFF81C784.toInt())
            feedback.text = if (correctStreak >= required) {
                "答对啦！本轮共错 $wrongCount 次，继续看电视～"
            } else {
                "答对了！还需再连对 ${required - correctStreak} 题"
            }

            if (correctStreak >= required) {
                mainHandler.postDelayed({ finishRound(clearedByAnswer = true) }, 900)
            } else {
                mainHandler.postDelayed({ nextQuestion() }, 900)
            }
        } else {
            val explain = q.explain?.let { "（$it）" } ?: ""
            handleWrong("答错了 $explain")
        }
    }

    /** 答错或超时的统一处理：wrongCount++，correctStreak 归零，required 上调，出下一题。 */
    private fun handleWrong(message: String) {
        val view = root ?: return
        countdown?.cancel()
        disableAllOptions(view)

        wrongCount++
        correctStreak = 0
        required = (1 + wrongCount).coerceAtMost(prefs.maxQuestionsPerRound.coerceAtLeast(1))

        prefs.pendingWrongStreak = wrongCount
        prefs.pendingCorrectStreak = 0

        val feedback = view.findViewById<TextView>(R.id.tv_feedback)
        feedback.setTextColor(0xFFEF9A9A.toInt())
        feedback.text = message + "，连对进度已清零，需连对 $required 题"

        mainHandler.postDelayed({ nextQuestion() }, 1100)
    }

    private fun disableAllOptions(view: View) {
        val optIds = intArrayOf(R.id.btn_opt_0, R.id.btn_opt_1, R.id.btn_opt_2, R.id.btn_opt_3)
        optIds.forEach { view.findViewById<Button>(it)?.isEnabled = false }
    }

    // ===== 管理员解锁 =====

    private fun showAdminUnlockPanel() {
        val view = root ?: return
        // 取消所有正在倒计时的下一题/finishRound 回调，避免面板被自动切走
        countdown?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        disableAllOptions(view)
        view.findViewById<Button>(R.id.btn_admin_unlock)?.isEnabled = false
        val panel = view.findViewById<LinearLayout>(R.id.lock_panel)
        panel.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_lock_hint)?.text =
            "管理员解锁：输入家长密码后可直接放行本轮"
        val et = view.findViewById<EditText>(R.id.et_password)
        et.text?.clear()
        view.post { et.requestFocus() }
        onLocked?.invoke()
    }

    private fun hideAdminUnlockPanel() {
        val view = root ?: return
        view.findViewById<LinearLayout>(R.id.lock_panel).visibility = View.GONE
        view.findViewById<Button>(R.id.btn_admin_unlock)?.isEnabled = true
        // 回到当前题目继续作答（重启倒计时，避免"点开面板就白嫖时间"）
        renderQuestion()
    }

    private fun onAdminUnlockSubmit() {
        val view = root ?: return
        val et = view.findViewById<EditText>(R.id.et_password)
        val input = et.text?.toString().orEmpty()
        if (prefs.verifyPassword(input)) {
            Toast.makeText(context, "已解锁，继续看电视吧", Toast.LENGTH_SHORT).show()
            finishRound(clearedByAnswer = false)
        } else {
            Toast.makeText(context, "密码错误，请重试或返回继续答题", Toast.LENGTH_SHORT).show()
            et.text?.clear()
        }
    }

    /**
     * @param clearedByAnswer true = 孩子自己连对通过；false = 管理员密码解锁 / 题库为空等异常收尾。
     * @param silent true = 不显示任何 Toast（异常场景）。
     */
    private fun finishRound(clearedByAnswer: Boolean, silent: Boolean = false) {
        prefs.pendingPopupOnBoot = false
        prefs.pendingWrongStreak = 0
        prefs.pendingCorrectStreak = 0
        if (clearedByAnswer) {
            prefs.lastRoundClearedAt = System.currentTimeMillis()
        }
        removeRootView()
        onCleared?.invoke()
    }

    private fun removeRootView() {
        countdown?.cancel()
        countdown = null
        root?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        root = null
        currentQuestion = null
        recentlyUsed.clear()
        wrongCount = 0
        correctStreak = 0
        required = 1
    }

    fun dismissSilently() {
        removeRootView()
    }
}
