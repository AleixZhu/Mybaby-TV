package com.kidstvguard.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 题库加载与远程同步（支持按年级分组）。
 *
 * 加载优先级（reload 时按此顺序取第一个可用）：
 *  1. 当前年级的远程缓存 remote_questions_g{grade}.json
 *  2. SAF 导入的外部 JSON
 *  3. /sdcard/Download/kids_tv_guard_questions.json 兜底扫描
 *  4. assets/questions.json 内置示例
 *
 * 远程 URL 支持模板占位符：
 *  - `{grade}` 或 `{N}` → 当前选中年级数字（0..9）
 *  - 例：`https://xxx.gitee.io/bank/grade{grade}.json` + selectedGrade=3 → `.../grade3.json`
 *  - 不含占位符时视为单文件 URL，所有年级共用同一份 JSON（此时 JSON 内可用 grade/grades 字段区分）
 *
 * 拉取失败不覆盖旧缓存，断网也能用上次的题目。
 */
class QuestionRepository private constructor(private val context: Context) {

    @Volatile
    private var cache: List<Question> = emptyList()

    @Volatile
    var currentSource: Source = Source.NONE
        private set

    private val prefs = PrefsManager.get(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    enum class Source { NONE, REMOTE, EXTERNAL, ASSETS }

    fun questions(): List<Question> {
        if (cache.isEmpty()) reload()
        return cache
    }

    /** 当前年级过滤后的题目数（用于 UI 展示）。 */
    fun questionsForCurrentGrade(): List<Question> {
        val g = prefs.selectedGrade
        val all = questions()
        val filtered = all.filter { it.matchesGrade(g) }
        return filtered.ifEmpty { all }
    }

    fun reload() {
        val grade = prefs.selectedGrade
        val remote = loadRemoteCache(grade)
        if (prefs.questionBankUrl.isNotBlank() && remote != null) {
            cache = remote
            currentSource = Source.REMOTE
            return
        }
        val external = loadExternal()
        if (external != null) {
            cache = external
            currentSource = Source.EXTERNAL
            return
        }
        val assets = loadAssets()
        if (assets != null) {
            cache = assets
            currentSource = Source.ASSETS
            return
        }
        cache = emptyList()
        currentSource = Source.NONE
    }

    fun sourceLabel(): String = when (currentSource) {
        Source.REMOTE -> "远程 URL"
        Source.EXTERNAL -> "本地导入 JSON"
        Source.ASSETS -> "内置示例"
        Source.NONE -> "空"
    }

    /** 把 URL 模板里的 `{grade}` / `{N}` 替换为当前年级。 */
    fun resolveUrl(template: String? = null, grade: Int = prefs.selectedGrade): String {
        val raw = (template ?: prefs.questionBankUrl).trim()
        if (raw.isEmpty()) return ""
        return raw
            .replace("{grade}", grade.toString())
            .replace("{Grade}", grade.toString())
            .replace("{N}", grade.toString())
            .replace("{n}", grade.toString())
    }

    fun urlHasTemplate(template: String? = null): Boolean {
        val raw = (template ?: prefs.questionBankUrl)
        return raw.contains("{grade}") || raw.contains("{Grade}") ||
                raw.contains("{N}") || raw.contains("{n}")
    }

    /**
     * 随机抽 n 道不重复题；不足 n 时循环补齐。
     * @param grade 目标年级（0=不区分）；优先抽符合年级的题，池不够时补通用题。
     */
    fun pickRandom(n: Int, grade: Int = prefs.selectedGrade, seed: Long = System.nanoTime()): List<Question> {
        val all = questions()
        if (all.isEmpty()) return emptyList()

        // 分池：符合年级的 vs 通用的
        val primary = all.filter { it.matchesGrade(grade) }
        val pool = (if (primary.isNotEmpty()) primary else all).toMutableList()

        val rnd = Random(seed)
        pool.shuffle(rnd)
        val out = mutableListOf<Question>()
        var guard = 0
        while (out.size < n && guard < 100) {
            out.addAll(pool.shuffled(rnd))
            guard++
        }
        return out.take(n)
    }

    // ===== 本地文件加载 =====

    private fun loadAssets(): List<Question>? = try {
        val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        parse(text)
    } catch (t: Throwable) {
        null
    }

    private fun loadExternal(): List<Question>? {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), EXTERNAL_FILE_NAME),
            File("/sdcard/Download/$EXTERNAL_FILE_NAME"),
            File("/sdcard/$EXTERNAL_FILE_NAME")
        )
        for (f in candidates) {
            if (f.exists() && f.canRead()) {
                try {
                    return parse(f.readText())
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun remoteCacheFile(grade: Int): File =
        File(context.filesDir, "remote_questions_g${grade}.json")

    private fun loadRemoteCache(grade: Int): List<Question>? {
        val f = remoteCacheFile(grade)
        if (!f.exists()) return null
        return try {
            parse(f.readText())
        } catch (_: Throwable) {
            null
        }
    }

    // ===== SAF 导入 =====

    fun importFromUri(uri: Uri): Result<Int> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("无法读取所选文件")
        val list = parse(text) ?: error("JSON 格式非法")
        val target = File(context.getExternalFilesDir(null), EXTERNAL_FILE_NAME)
        target.parentFile?.mkdirs()
        target.writeText(text)
        reload()
        list.size
    }

    fun clearExternal() {
        val target = File(context.getExternalFilesDir(null), EXTERNAL_FILE_NAME)
        if (target.exists()) target.delete()
        reload()
    }

    fun externalExists(): Boolean {
        val f = File(context.getExternalFilesDir(null), EXTERNAL_FILE_NAME)
        return f.exists()
    }

    // ===== 远程同步 =====

    fun clearRemoteCache(grade: Int? = null) {
        if (grade == null) {
            // 清所有年级
            (0..9).forEach { g ->
                val f = remoteCacheFile(g)
                if (f.exists()) f.delete()
            }
        } else {
            val f = remoteCacheFile(grade)
            if (f.exists()) f.delete()
        }
        prefs.lastSyncAt = 0L
        prefs.lastSyncMessage = ""
        prefs.lastSyncSuccess = false
        reload()
    }

    fun remoteCacheExists(grade: Int = prefs.selectedGrade): Boolean =
        remoteCacheFile(grade).exists()

    /**
     * 异步从远程拉当前年级题库。
     * @param urlTemplate 为空则读 prefs.questionBankUrl；含 `{grade}`/`{N}` 会替换为当前年级。
     * @param grade       目标年级；null 表示用 prefs.selectedGrade。
     */
    fun syncFromRemote(
        urlTemplate: String? = null,
        grade: Int? = null,
        onDone: ((Result<Int>) -> Unit)? = null
    ) {
        val g = grade ?: prefs.selectedGrade
        val resolved = resolveUrl(urlTemplate, g)
        if (resolved.isBlank()) {
            val r = Result.failure<Int>(IllegalArgumentException("未配置题库 URL"))
            onDone?.let { main.post { it(r) } }
            return
        }
        io.execute {
            val result = doSync(resolved, g)
            main.post {
                reload()
                onDone?.invoke(result)
            }
        }
    }

    private fun doSync(url: String, grade: Int): Result<Int> {
        val startedAt = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val u = URL(url)
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", "KidsTvGuard/1.1")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val list = parse(text) ?: throw IllegalArgumentException("JSON 格式非法或为空")
            if (list.isEmpty()) throw IllegalArgumentException("题库为空")
            remoteCacheFile(grade).writeText(text)
            val cost = System.currentTimeMillis() - startedAt
            val gradeLabel = PrefsManager.GRADE_LABELS[grade] ?: "$grade"
            prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastSyncSuccess = true
            prefs.lastSyncMessage = "[$gradeLabel] 拉取 ${list.size} 道题，用时 ${cost}ms"
            Result.success(list.size)
        } catch (t: Throwable) {
            val gradeLabel = PrefsManager.GRADE_LABELS[grade] ?: "$grade"
            prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastSyncSuccess = false
            prefs.lastSyncMessage = "[$gradeLabel] ${t.message ?: t.javaClass.simpleName}"
            Result.failure(t)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    fun lastSyncSummary(): String {
        if (prefs.lastSyncAt == 0L) return "尚未同步"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val flag = if (prefs.lastSyncSuccess) "✓" else "✗"
        return "$flag ${sdf.format(Date(prefs.lastSyncAt))}  ${prefs.lastSyncMessage}"
    }

    // ===== 解析 =====

    private fun parse(text: String): List<Question>? = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val stem = o.optString("stem").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val optsArr = o.optJSONArray("options") ?: return@mapNotNull null
            val options = (0 until optsArr.length()).map { optsArr.optString(it) }
            if (options.size < 2) return@mapNotNull null
            val answer = o.optInt("answer", 0).coerceIn(0, options.size - 1)
            val explain = o.optString("explain").takeIf { it.isNotBlank() }

            // 年级字段：支持单值 "grade": 3 或多值 "grades": [1,2,3]
            val grades = mutableSetOf<Int>()
            if (o.has("grade") && !o.isNull("grade")) {
                grades.add(o.optInt("grade"))
            }
            o.optJSONArray("grades")?.let { ga ->
                for (k in 0 until ga.length()) grades.add(ga.optInt(k))
            }
            grades.remove(0) // 0 保留给"不区分"，题目侧不应写 0

            Question(stem, options, answer, explain, grades)
        }
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val ASSET_FILE = "questions.json"
        private const val EXTERNAL_FILE_NAME = "kids_tv_guard_questions.json"

        @Volatile
        private var INSTANCE: QuestionRepository? = null

        fun get(context: Context): QuestionRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: QuestionRepository(context).also { INSTANCE = it }
            }
    }
}
