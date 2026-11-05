package com.kidstvguard.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kidstvguard.R
import com.kidstvguard.data.PrefsManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 应用内自动更新。
 *
 * 流程：
 *  1. 拉取远程 version.json（地址在设置页可改，默认指向本仓库 GitHub Release）
 *  2. 比对 versionCode，比本地新就弹确认框（forceUpdate=true 时不给"稍后"）
 *  3. 用户确认后后台下载 APK 到 cacheDir，带进度条
 *  4. 下载完触发系统安装页（FileProvider URI + REQUEST_INSTALL_PACKAGES）
 *
 * 重要前提：Android 不允许 APP 替换自身代码，"功能更新"必须装新 APK；
 * 而且新 APK 必须和已装版本用**同一把签名密钥**，否则系统报"签名不一致"拒绝覆盖安装。
 * 本仓库用 signing/release.p12 固定签名，CI 每次构建签名一致，更新才能装上。
 * 完全静默（连一次确认都不点）需要系统签名/设备管理器特权，侧载 APP 做不到。
 */
object UpdateManager {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val force: Boolean
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    fun currentVersionCode(context: Context): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode
        }
    } catch (t: Throwable) {
        0
    }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    /**
     * 检查更新。
     * @param silentIfNone true=没有新版本/失败时不打扰（开机自动检查用）；
     *                     false=手动检查，无更新或失败也给提示。
     */
    fun check(activity: Activity, silentIfNone: Boolean, onLog: ((String) -> Unit)? = null) {
        if (busy) {
            onLog?.invoke("正在检查中，请稍候…")
            return
        }
        val prefs = PrefsManager.get(activity)
        val feed = prefs.updateFeedUrl.trim()
        if (feed.isEmpty() || !(feed.startsWith("http://") || feed.startsWith("https://"))) {
            if (!silentIfNone) toast(activity, "更新地址未配置或非法")
            onLog?.invoke("更新地址未配置或非法")
            return
        }
        busy = true
        onLog?.invoke("检查中…")
        io.execute {
            val info = fetchInfo(feed)
            main.post {
                busy = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (info == null) {
                    if (!silentIfNone) toast(activity, "检查更新失败：无法获取版本信息（地址或网络问题）")
                    onLog?.invoke("✗ 检查失败：无法获取版本信息（地址或网络问题）")
                    return@post
                }
                val cur = currentVersionCode(activity)
                if (info.versionCode > cur) {
                    onLog?.invoke("发现新版本 ${info.versionName.ifEmpty { "(vc=${info.versionCode})" }}（当前 vc=$cur）")
                    promptUpdate(activity, info)
                } else {
                    onLog?.invoke("✓ 已是最新版本（${currentVersionName(activity)} / vc=$cur）")
                    if (!silentIfNone) toast(activity, "已是最新版本（${currentVersionName(activity)} / vc=$cur）")
                }
            }
        }
    }

    private fun fetchInfo(feed: String): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(feed).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", "KidsTvGuard-updater/1.0")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(text)
            val apkUrl = o.optString("apkUrl").trim()
            if (apkUrl.isEmpty()) return null
            UpdateInfo(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName", ""),
                apkUrl = apkUrl,
                changelog = o.optString("changelog", ""),
                force = o.optBoolean("forceUpdate", false)
            )
        } catch (t: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun promptUpdate(activity: Activity, info: UpdateInfo) {
        val msg = buildString {
            append("发现新版本：").append(info.versionName.ifEmpty { "(vc=${info.versionCode})" })
            append("\n当前版本：").append(currentVersionName(activity))
            append("（vc=").append(currentVersionCode(activity)).append("）")
            if (info.changelog.isNotBlank()) {
                append("\n\n更新内容：\n").append(info.changelog)
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("应用更新")
            .setMessage(msg)
            .setCancelable(!info.force)
            .setPositiveButton("立即更新") { _, _ -> startDownload(activity, info) }
        if (!info.force) builder.setNegativeButton("稍后") { d, _ -> d.dismiss() }
        builder.show()
    }

    private fun startDownload(activity: Activity, info: UpdateInfo) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val bar = view.findViewById<ProgressBar>(R.id.pb_download)
        val tv = view.findViewById<TextView>(R.id.tv_download)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载新版本")
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()

        busy = true
        io.execute {
            val result = downloadApk(activity, info.apkUrl) { percent ->
                main.post {
                    bar.progress = percent
                    tv.text = "下载中… $percent%"
                }
            }
            main.post {
                busy = false
                if (!activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { file ->
                    launchInstall(activity, file)
                }.onFailure { t ->
                    toast(activity, "下载失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): Result<File> {
        var conn: HttpURLConnection? = null
        return try {
            val dest = File(context.cacheDir, "update.apk")
            if (dest.exists()) dest.delete()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "KidsTvGuard-updater/1.0")
            }
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLength
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var done = 0L
                    var lastPercent = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != lastPercent) {
                                lastPercent = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (dest.length() < 10 * 1024) throw java.io.IOException("下载文件过小，可能已损坏")
            Result.success(dest)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun launchInstall(activity: Activity, file: File) {
        // Android 8+ 需要"允许安装未知应用"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val can = try { activity.packageManager.canRequestPackageInstalls() } catch (t: Throwable) { false }
            if (!can) {
                AlertDialog.Builder(activity)
                    .setTitle("需要安装权限")
                    .setMessage("首次更新需要允许本应用安装其他应用。点「去设置」打开系统页面，允许后返回再点一次更新即可。")
                    .setCancelable(false)
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        try {
                            activity.startActivity(intent)
                        } catch (t: Throwable) {
                            toast(activity, "打开设置失败：${t.message}")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        } catch (t: Throwable) {
            toast(activity, "文件授权失败：${t.message}")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            toast(activity, "调起安装失败：${t.message}")
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
