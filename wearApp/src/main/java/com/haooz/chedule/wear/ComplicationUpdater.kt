package com.haooz.chedule.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * 通知表盘「这一项数据变了」。
 *
 * 两类时机：
 * 1. 手机推来新课表 → 立即刷新
 * 2. 到了上课 / 下课时刻 → 用闹钟唤醒自己再刷一次
 *
 * 不做按秒刷新：倒计时交给 [androidx.wear.watchface.complications.data.TimeDifferenceComplicationText]，
 * 由表盘自己走字，应用只在状态真正切换时才参与。
 */
object ComplicationUpdater {

    private const val TAG = "ComplicationUpdater"
    private const val ALARM_RC = 8801

    /** 请系统重新拉取数据源 */
    fun refresh(context: Context) {
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, CourseComplicationService::class.java))
                .requestUpdateAll()
        }.onFailure { Log.w(TAG, "requestUpdateAll failed: ${it.message}") }
    }

    /** 到某个时刻再刷新一次 */
    fun scheduleRefresh(context: Context, delayMillis: Long) {
        val intent = Intent(context, ComplicationRefreshReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            ALARM_RC,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + delayMillis
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 手表上 setExactAndAllowWhileIdle 已足够；setAlarmClock 会在表盘上显示闹钟图标
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }.onFailure { Log.w(TAG, "schedule failed: ${it.message}") }
    }
}
