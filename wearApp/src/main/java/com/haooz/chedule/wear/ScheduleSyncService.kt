package com.haooz.chedule.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * 接收手机推来的当日课表。
 *
 * 走 DataClient 而不是 MessageClient：DataItem 会持久化在 Data Layer，
 * 手表断连、重启、应用被杀之后重新连上都能拿到最新一份；MessageClient 是
 * best-effort，手机关屏那一刻发的消息手表收不到就没了。
 */
class ScheduleSyncService : WearableListenerService() {

    companion object {
        private const val TAG = "WearScheduleSync"

        /** Data Layer 路径。两端必须一致，改这里等于改协议。 */
        const val PATH_TODAY = "/nexio/today"

        private const val KEY_SENT_AT = "sentAt"
        private const val KEY_COUNT = "count"
        private const val KEY_NAME = "name"
        private const val KEY_CLASSROOM = "classroom"
        private const val KEY_TEACHER = "teacher"
        private const val KEY_START = "startMinute"
        private const val KEY_END = "endMinute"
        private const val KEY_SECTION = "section"

        /** 把 DataMap 解成快照；字段缺失按空处理，不让一条脏数据把表盘搞崩 */
        fun decode(item: DataMapItem): TodaySnapshot {
            val map = item.dataMap
            val count = map.getInt(KEY_COUNT, 0)
            val courses = (0 until count).mapNotNull { i ->
                val name = map.getString("$KEY_NAME$i") ?: return@mapNotNull null
                WatchCourse(
                    name = name,
                    classroom = map.getString("$KEY_CLASSROOM$i").orEmpty(),
                    teacher = map.getString("$KEY_TEACHER$i").orEmpty(),
                    startMinute = map.getInt("$KEY_START$i", -1),
                    endMinute = map.getInt("$KEY_END$i", -1),
                    sectionText = map.getString("$KEY_SECTION$i").orEmpty(),
                ).takeIf { it.startMinute >= 0 && it.endMinute > it.startMinute }
            }
            return TodaySnapshot(
                courses = courses.sortedBy { it.startMinute },
                sentAtMillis = map.getLong(KEY_SENT_AT, 0L),
            )
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (path != PATH_TODAY) continue

            val snapshot = decode(DataMapItem.fromDataItem(event.dataItem))
            SnapshotStore.save(this, snapshot)
            Log.i(TAG, "snapshot updated: ${snapshot.courses.size} courses")
            ComplicationUpdater.refresh(this)
        }
    }
}
