package com.haooz.chedule.wear

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * 手表本地缓存。
 *
 * 表盘随时可能来取数据（包括息屏唤醒那一刻），不能每次都等 Data Layer 往返，
 * 所以收到就落盘，平时只读本地。Data Layer 本身也是持久化的，这里再存一份是为了
 * 让读取是同步的、且不依赖 GMS 已经连上。
 */
object SnapshotStore {

    private const val PREFS = "wear_snapshot"

    fun save(context: Context, snapshot: TodaySnapshot) {
        val map = DataMap().apply {
            putLong("sentAt", snapshot.sentAtMillis)
            putInt("count", snapshot.courses.size)
            snapshot.courses.forEachIndexed { i, c ->
                putString("name$i", c.name)
                putString("classroom$i", c.classroom)
                putString("teacher$i", c.teacher)
                putInt("startMinute$i", c.startMinute)
                putInt("endMinute$i", c.endMinute)
                putString("section$i", c.sectionText)
            }
        }
        val bytes = map.toByteArray()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("payload", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): TodaySnapshot {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("payload", null) ?: return TodaySnapshot.empty()
        return runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val map = DataMap.fromByteArray(bytes)
            val count = map.getInt("count", 0)
            val courses = (0 until count).mapNotNull { i ->
                val name = map.getString("name$i") ?: return@mapNotNull null
                WatchCourse(
                    name = name,
                    classroom = map.getString("classroom$i").orEmpty(),
                    teacher = map.getString("teacher$i").orEmpty(),
                    startMinute = map.getInt("startMinute$i", -1),
                    endMinute = map.getInt("endMinute$i", -1),
                    sectionText = map.getString("section$i").orEmpty(),
                ).takeIf { it.startMinute >= 0 && it.endMinute > it.startMinute }
            }
            TodaySnapshot(courses.sortedBy { it.startMinute }, map.getLong("sentAt", 0L))
        }.getOrDefault(TodaySnapshot.empty())
    }

    /** 手表主动向手机要一次：用户刚装好、或者换了一天没收到推送 */
    suspend fun requestFromPhone(context: Context): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, ScheduleSyncService.PATH_TODAY, byteArrayOf())
                .await()
        }
        nodes.isNotEmpty()
    }.getOrDefault(false)
}
