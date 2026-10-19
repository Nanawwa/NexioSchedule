package com.haooz.chedule.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 上课 / 下课时刻到了：请表盘重新取一次数据。 */
class ComplicationRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ComplicationUpdater.refresh(context)
    }
}
