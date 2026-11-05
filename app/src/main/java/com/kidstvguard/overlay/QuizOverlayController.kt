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
 * 悬浮答题窗控制器。
 *
 * 状态机：
 *  - 每次 show() 开启一轮：题目总数 = 1 + 连错次数，最多 [PrefsManager.maxQuestionsPerRound] 道。
 *  - 题目从当前年级题库中随机抽取（[PrefsManager.selectedGrade] + [Question.matchesGrade]）。
 *  - 答对当前题 → 结束本轮，回调 [onCleared]（Service 用来重置计时）。
 *  - 答错当前题 或 单题倒计时到点 → 立即出下一题；wrongStreak++。
 *  - 达到上限后仍未答对 → 展示家长密码解锁区。
 *
 * 断电续弹：只要 show() 被调用，[PrefsManager.pendingPopupOnBoot] 立刻置 true；
 * 本轮成功结束时置 false。因此若中途关机，下次开机 GuardService 读到 true 就立即再弹。
 */
class QuizOverlayController(private val context: Context) {

    private val prefs = PrefsManager.get(context)
    private val repo = QuestionRepository.get(context)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var countdown: CountDownTimer? = null
    private var currentQueue: List<Question> = emptyList()
    private var usedStems: MutableSet<String> = mutableSetOf()
    private var currentIndex = 0
    private var wrongStreak = 0
    private var totalSlots = 1

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
     * @param bootPending 是否是"关机前遗留"的场景，若是则沿用持久化的 wrongStreak。
     */
    fun show(bootPending: Boolean = false) {
        if (isShowing) return
        if (!canDrawOverlay()) {
            Toast.makeText(context, "未授予悬浮窗权限，无法弹题", Toast.LENGTH_LONG).show()
            return
        }

        wrongStreak = if (bootPending) prefs.pendingWrongStreak.coerceIn(0, prefs.maxQuestionsPerRound - 1) else 0
        totalSlots = (1 + wrongStreak).coerceAtMost(prefs.maxQuestionsPerRound)

        usedStems.clear()
        currentQueue = pickNew(totalSlots)
        if (currentQueue.isEmpty()) {
            Toast.makeText(context, "当前年级题库为空，请在设置里检查", Toast.LENGTH_LONG).show()
            return
        }
        currentQueue.forEach { usedStems.add(it.stem) }
        currentIndex = 0

        // 断电续弹标记：本轮未结束前一直保持 true
        prefs.pendingPopupOnBoot = true
        prefs.pendingWrongStreak = wrongStreak

        addRootView()
        renderQuestion()
    }

    /** 抽 count 道题，尽量避开已用过的题干。 */
    private fun pickNew(count: Int): List<Question> {
        val grade = prefs.selectedGrade
        // 一次抽多点再过滤已用
        val candidates = repo.pickRandom(count * 3, grade)
        val fresh = candidates.filter { it.stem !in usedStems }.take(count)
        if (fresh.size >= count) return fresh
        // 池不够，允许重复
        val fill = candidates.take(count - fresh.size)
        return fresh + fill
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

        view.post {
            view.findViewById<Button>(R.id.btn_opt_0)?.requestFocus()
        }
    }

    private fun renderQuestion() {
        val view = root ?: return
        val q = currentQueue.getOrNull(currentIndex) ?: run { finishRound(true); return }

        view.findViewById<TextView>(R.id.tv_header).text =
            "${prefs.selectedGradeLabel()} · 答题时间到"
        view.findViewById<TextView>(R.id.tv_progress).text =
            "第 ${currentIndex + 1} / $totalSlots 题（连错 $wrongStreak 次）"
        view.findViewById<TextView>(R.id.tv_stem).text = q.stem
        view.findViewById<TextView>(R.id.tv_feedback).text = ""
        view.findViewById<LinearLayout>(R.id.lock_panel).visibility = View.GONE

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
                // 超时 = 当次答错，进入下一题（与选错一致）
                handleWrong("本题 ${totalSec} 秒内未作答")
            }
        }.start()
    }

    private fun onAnswer(picked: Int) {
        val view = root ?: return
        val q = currentQueue.getOrNull(currentIndex) ?: return
        countdown?.cancel()

        val correct = picked == q.answer
        val feedback = view.findViewById<TextView>(R.id.tv_feedback)

        if (correct) {
            feedback.setTextColor(0xFF81C784.toInt())
            feedback.text = if (wrongStreak == 0) "答对啦！继续看电视～"
                            else "答对了！本轮共错 $wrongStreak 次，继续看电视～"
            disableAllOptions(view)
            mainHandler.postDelayed({ finishRound(true) }, 900)
        } else {
            val explain = q.explain?.let { "（$it）" } ?: ""
            handleWrong("答错了 $explain")
        }
    }

    /** 答错或超时的统一处理：wrongStreak++，未达上限就出下一题，达上限就锁死等家长。 */
    private fun handleWrong(message: String) {
        val view = root ?: return
        countdown?.cancel()
        disableAllOptions(view)

        wrongStreak++
        prefs.pendingWrongStreak = wrongStreak

        val feedback = view.findViewById<TextView>(R.id.tv_feedback)
        feedback.setTextColor(0xFFEF9A9A.toInt())
        feedback.text = message

        val nextSlots = (1 + wrongStreak).coerceAtMost(prefs.maxQuestionsPerRound)
        if (nextSlots > totalSlots) {
            totalSlots = nextSlots
            if (currentQueue.size < totalSlots) {
                val extra = pickNew(totalSlots - currentQueue.size)
                currentQueue = currentQueue + extra
                extra.forEach { usedStems.add(it.stem) }
            }
        }

        if (wrongStreak >= prefs.maxQuestionsPerRound) {
            mainHandler.postDelayed({ lockForParent("连错已达 $wrongStreak 次") }, 900)
        } else {
            mainHandler.postDelayed({
                currentIndex++
                renderQuestion()
            }, 900)
        }
    }

    private fun disableAllOptions(view: View) {
        val optIds = intArrayOf(R.id.btn_opt_0, R.id.btn_opt_1, R.id.btn_opt_2, R.id.btn_opt_3)
        optIds.forEach { view.findViewById<Button>(it)?.isEnabled = false }
    }

    private fun lockForParent(reason: String) {
        val view = root ?: return
        countdown?.cancel()
        disableAllOptions(view)
        view.findViewById<TextView>(R.id.tv_feedback).apply {
            setTextColor(0xFFEF9A9A.toInt())
            text = reason + "，请家长解锁"
        }
        view.findViewById<TextView>(R.id.tv_progress).text = "已锁定"
        view.findViewById<TextView>(R.id.tv_timer).text = ""
        val lockPanel = view.findViewById<LinearLayout>(R.id.lock_panel)
        lockPanel.visibility = View.VISIBLE

        val et = view.findViewById<EditText>(R.id.et_password)
        val btn = view.findViewById<Button>(R.id.btn_unlock)
        et.text?.clear()
        btn.setOnClickListener {
            val input = et.text?.toString().orEmpty()
            if (prefs.verifyPassword(input)) {
                finishRound(false)
            } else {
                Toast.makeText(context, "密码错误", Toast.LENGTH_SHORT).show()
            }
        }
        view.post { et.requestFocus() }
        onLocked?.invoke()
    }

    /**
     * @param clearedByAnswer true = 孩子自己答对通过；false = 家长密码解锁。
     */
    private fun finishRound(clearedByAnswer: Boolean) {
        prefs.pendingPopupOnBoot = false
        prefs.pendingWrongStreak = 0
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
        currentQueue = emptyList()
        usedStems.clear()
        currentIndex = 0
        wrongStreak = 0
        totalSlots = 1
    }

    fun dismissSilently() {
        removeRootView()
    }
}
