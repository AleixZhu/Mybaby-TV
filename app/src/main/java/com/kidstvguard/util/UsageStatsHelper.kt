package com.kidstvguard.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.provider.Settings

/**
 * 通过 UsageStatsManager 检测"当前前台是否是视频/电视 APP"。
 *
 * 说明：
 *  - PACKAGE_USAGE_STATS 是"特殊权限"，必须引导用户到系统设置手动开启。
 *  - HDMI 输入不是 Android APP，无法用 UsageStats 检测；本 APP 采取的策略是：
 *      只要 GuardService 在运行且 popupEnabled=true，计时就在跑；
 *      如果检测到当前前台是"视频类 APP"（下方 [VIDEO_PACKAGES] 或包名含 tv/video/player），
 *      到点立即弹；如果检测到 HDMI（部分电视会把 HDMI 暴露成 com.mediatek.tvinput 之类），
 *      也认为在"看电视"，到点尝试弹（能否真叠在画面上取决于厂商）。
 *  - 如果当前前台是系统桌面/设置等非视频 APP，则暂停累计观看时长，避免误伤。
 */
object UsageStatsHelper {

    /** 常见视频/电视类 APP 包名白名单（可按需扩展）。 */
    private val VIDEO_PACKAGES = setOf(
        // 官方 TV 版
        "com.qiyi.video", "com.qiyi.video.tv", "com.qiyi.tv",
        "com.tencent.qqlivetv", "com.tencent.qqliveton",
        "com.youku.tv", "com.youku.iot",
        "com.hunantv.imgo.activity", "com.hunantv.imgo",
        "tv.danmaku.bili", "com.bilibili.tv",
        "com.ss.android.article.video",
        "com.xiaomi.mitv.video", "com.xiaomi.tv",
        "com.miui.video",
        "com.google.android.youtube.tv",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        // HDMI / 直播信号（部分厂商）
        "com.mediatek.tvinput", "com.mstar.tvinput",
        "com.hisense.tvinput", "com.tcl.tvinput",
        "com.skyworth.tvinput",
        "com.android.tv", "com.google.android.tv"
    )

    /** 包名包含以下关键字视为视频类。 */
    private val VIDEO_KEYWORDS = listOf("video", "player", "tv", "live", "iptv", "youtube", "netflix")

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageStatsSettings(context: Context) {
        val intent = android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            // 极少数厂商没有此页，退到应用详情
            val i = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }

    /**
     * 取当前前台包名。返回 null 表示拿不到（多半是权限没给）。
     */
    fun getForegroundPackage(context: Context): String? {
        if (!hasUsageStatsPermission(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 30_000L
        val events = usm.queryEvents(begin, end)
        val ev = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTs = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                if (ev.timeStamp >= latestTs) {
                    latestTs = ev.timeStamp
                    latestPkg = ev.packageName
                }
            }
        }
        return latestPkg
    }

    /** 判断给定包名是否属于"应该被计时"的视频/电视类前台应用。 */
    fun isVideoForeground(pkg: String?, selfPackage: String): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == selfPackage) return false // 我们自己不算
        if (VIDEO_PACKAGES.contains(pkg)) return true
        val lower = pkg.lowercase()
        return VIDEO_KEYWORDS.any { lower.contains(it) }
    }
}
