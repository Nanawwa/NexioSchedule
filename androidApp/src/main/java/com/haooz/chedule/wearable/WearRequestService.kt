package com.haooz.chedule.wearable

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.haooz.chedule.data.NexioLog

/**
 * 手机端接收手表的请求消息。
 *
 * 手表刚装好、或者换了一天没收到推送时，会主动发一条空消息过来要数据。
 * 手机在这里把当日课表重新推一遍。
 */
class WearRequestService : WearableListenerService() {

    companion object {
        private const val TAG = "WearRequestService"
        private const val PATH_TODAY = "/nexio/today"
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_TODAY) return
        NexioLog.i(TAG, "watch requested schedule from ${event.sourceNodeId}")
        WearScheduleSync.pushTodayFromWatch(this)
    }
}
