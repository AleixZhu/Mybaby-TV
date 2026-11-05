package com.kidstvguard.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * 全部设置项、密码、以及"关机前未答完"的续弹状态都持久化在这里。
 *
 * 关键点：
 *  - [KEY_PENDING_POPUP] 表示"上次关机时还有没答完的题"，开机后 GuardService 立刻弹窗。
 *  - 密码使用 SHA-256 加盐存储，默认 1234，可在设置里修改。
 */
class PrefsManager private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ===== 弹窗开关 =====
    var popupEnabled: Boolean
        get() = sp.getBoolean(KEY_POPUP_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_POPUP_ENABLED, v).apply()

    // ===== 间隔（分钟）：5/10/15/20/30 =====
    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)
        set(v) = sp.edit().putInt(KEY_INTERVAL_MIN, v).apply()

    val intervalMillis: Long
        get() = intervalMinutes * 60_000L

    // ===== 答错加题的上限 =====
    var maxQuestionsPerRound: Int
        get() = sp.getInt(KEY_MAX_QUESTIONS, DEFAULT_MAX_QUESTIONS)
        set(v) = sp.edit().putInt(KEY_MAX_QUESTIONS, v).apply()

    // ===== 单题作答时长（秒），到点算答错，进入下一题 =====
    var perQuestionTimeoutSec: Int
        get() = sp.getInt(KEY_PER_Q_TIMEOUT, DEFAULT_PER_Q_TIMEOUT)
        set(v) = sp.edit().putInt(KEY_PER_Q_TIMEOUT, v).apply()

    // ===== 当前年级（0=不区分，1..6=小学一到六年级，7..9=初中） =====
    var selectedGrade: Int
        get() = sp.getInt(KEY_SELECTED_GRADE, 0)
        set(v) = sp.edit().putInt(KEY_SELECTED_GRADE, v.coerceIn(0, 9)).apply()

    fun selectedGradeLabel(): String = GRADE_LABELS[selectedGrade] ?: "不区分"

    // ===== 密码 =====
    fun verifyPassword(input: String): Boolean {
        val stored = sp.getString(KEY_PASSWORD_HASH, null) ?: return hash(DEFAULT_PASSWORD) == hash(input)
        return stored == hash(input)
    }

    fun changePassword(oldInput: String, newPassword: String): Boolean {
        if (!verifyPassword(oldInput)) return false
        sp.edit().putString(KEY_PASSWORD_HASH, hash(newPassword)).apply()
        return true
    }

    fun hasCustomPassword(): Boolean = sp.contains(KEY_PASSWORD_HASH)

    private fun hash(raw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((SALT + raw).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ===== 关机前未答完的续弹状态 =====
    /** 存在待弹窗标记 → 开机后立刻弹。 */
    var pendingPopupOnBoot: Boolean
        get() = sp.getBoolean(KEY_PENDING_POPUP, false)
        set(v) = sp.edit().putBoolean(KEY_PENDING_POPUP, v).apply()

    /** 关机那一刻累计的连错次数（0..maxQuestionsPerRound-1），决定开机后应出几道题。 */
    var pendingWrongStreak: Int
        get() = sp.getInt(KEY_PENDING_STREAK, 0)
        set(v) = sp.edit().putInt(KEY_PENDING_STREAK, v).apply()

    /** 上次成功答完一轮的时间戳（毫秒），用于展示统计。 */
    var lastRoundClearedAt: Long
        get() = sp.getLong(KEY_LAST_CLEARED, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_CLEARED, v).apply()

    /** 本轮已开始的观看毫秒数（跨进程重启也保留）。 */
    var currentWatchElapsedMs: Long
        get() = sp.getLong(KEY_WATCH_ELAPSED, 0L)
        set(v) = sp.edit().putLong(KEY_WATCH_ELAPSED, v).apply()

    fun resetWatch() {
        currentWatchElapsedMs = 0L
    }

    // ===== 远程题库 =====
    /** 远程 JSON 地址；空字符串表示未启用远程同步。 */
    var questionBankUrl: String
        get() = sp.getString(KEY_BANK_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_BANK_URL, v.trim()).apply()

    /** 自动同步周期（小时）。0 = 只在服务启动时同步一次。 */
    var autoSyncHours: Int
        get() = sp.getInt(KEY_AUTO_SYNC_HOURS, DEFAULT_AUTO_SYNC_HOURS)
        set(v) = sp.edit().putInt(KEY_AUTO_SYNC_HOURS, v).apply()

    /** 上次同步时间戳。 */
    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_SYNC_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_SYNC_AT, v).apply()

    /** 上次同步结果描述（成功条数或错误信息）。 */
    var lastSyncMessage: String
        get() = sp.getString(KEY_LAST_SYNC_MSG, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_SYNC_MSG, v).apply()

    /** 上次同步是否成功。 */
    var lastSyncSuccess: Boolean
        get() = sp.getBoolean(KEY_LAST_SYNC_OK, false)
        set(v) = sp.edit().putBoolean(KEY_LAST_SYNC_OK, v).apply()

    companion object {
        private const val FILE_NAME = "kids_tv_guard_prefs"
        private const val SALT = "KidsTvGuard::2026::"

        private const val KEY_POPUP_ENABLED = "popup_enabled"
        private const val KEY_INTERVAL_MIN = "interval_minutes"
        private const val KEY_MAX_QUESTIONS = "max_questions"
        private const val KEY_PER_Q_TIMEOUT = "per_q_timeout"
        private const val KEY_SELECTED_GRADE = "selected_grade"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_PENDING_POPUP = "pending_popup_on_boot"
        private const val KEY_PENDING_STREAK = "pending_wrong_streak"
        private const val KEY_LAST_CLEARED = "last_round_cleared_at"
        private const val KEY_WATCH_ELAPSED = "current_watch_elapsed_ms"

        private const val KEY_BANK_URL = "question_bank_url"
        private const val KEY_AUTO_SYNC_HOURS = "auto_sync_hours"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_SYNC_MSG = "last_sync_message"
        private const val KEY_LAST_SYNC_OK = "last_sync_success"

        const val DEFAULT_PASSWORD = "1234"
        const val DEFAULT_INTERVAL_MIN = 15
        const val DEFAULT_MAX_QUESTIONS = 5
        const val DEFAULT_PER_Q_TIMEOUT = 60
        const val DEFAULT_AUTO_SYNC_HOURS = 24

        val ALLOWED_INTERVALS = intArrayOf(5, 10, 15, 20, 30)

        /** 年级下拉/单选可选值。0 = 不区分（全部题目命中）。 */
        val ALLOWED_GRADES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

        val GRADE_LABELS = mapOf(
            0 to "不区分",
            1 to "一年级",
            2 to "二年级",
            3 to "三年级",
            4 to "四年级",
            5 to "五年级",
            6 to "六年级",
            7 to "初一",
            8 to "初二",
            9 to "初三"
        )

        @Volatile
        private var INSTANCE: PrefsManager? = null

        fun get(context: Context): PrefsManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrefsManager(context).also { INSTANCE = it }
            }
    }
}
