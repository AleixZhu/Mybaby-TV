package com.kidstvguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kidstvguard.data.PrefsManager

/**
 * 开机广播：
 *  - 只要 popupEnabled 就拉起 GuardService（常驻计时）。
 *  - Service 内部会读 pendingPopupOnBoot，若为 true 则立即弹题，
 *    覆盖"孩子没答完就关机 → 再开机马上弹"的诉求。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = PrefsManager.get(context)
        if (!prefs.popupEnabled) return
        GuardService.start(context)
    }
}
