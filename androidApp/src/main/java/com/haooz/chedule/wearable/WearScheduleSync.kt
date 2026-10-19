package com.haooz.chedule.wearable

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.haooz.chedule.data.CourseRepository
import com.haooz.chedule.data.NexioLog
import com.haooz.chedule.reminder.CourseReminderHelper
import java.util.Calendar

/**
 * 把「今天剩下的课」推给 Wear OS 手表。
 *
 * 只推今天、且只推还没下课的：手表上要显示的是「现在这节课 / 下一节课」，
 * 昨天的和已经上完的推过去只会让手表多做无用判定。
 *
 * 走 DataClient：DataItem 持久化在 Data Layer，手表断连、重启后重新连上
 * 仍能拿到最新一份；MessageClient 是 best-effort，手表没在线就丢了。
 *
 * 与小米手表的 [WearableScheduleSync] 互不影响：那条走 xms-wearable，
 * 这条走 Google Play services，两端各自独立。
 */
object WearScheduleSync {

    private const val TAG = "WearScheduleSync"

    /** 与手表端 [ScheduleSyncService.PATH_TODAY] 保持一致 */
    private const val PATH_TODAY = "/nexio/today"

    private const val KEY_SENT_AT = "sentAt"
    private const val KEY_COUNT = "count"

    /**
     * 推送今日剩余课程。
     *
     * 调用时机：应用启动、课表变更、跨日刷新、手机与手表重新连上。
     * 失败不抛异常 —— 手表没配对、没装 GMS、不在身边都是正常情况。
     */
    fun pushToday(context: Context, reason: String = "manual") {
        runCatching {
            val repository = CourseRepository()
            val courses = CourseReminderHelper.getTodayCourses(context)

            val nowMinutes = Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
            // 只留还没下课的：已经结束的课手表不需要
            val remaining = courses.mapNotNull { course ->
                val start = CourseReminderHelper.getCourseStartTime(course, repository) ?: return@mapNotNull null
                val end = CourseReminderHelper.getCourseEndTime(course, repository) ?: return@mapNotNull null
                val startMin = parseMinutes(start) ?: return@mapNotNull null
                val endMin = parseMinutes(end) ?: return@mapNotNull null
                if (endMin <= startMin) return@mapNotNull null
                if (endMin <= nowMinutes) return@mapNotNull null
                WatchCoursePayload(
                    name = course.name,
                    classroom = course.classroom,
                    teacher = course.teacher,
                    startMinute = startMin,
                    endMinute = endMin,
                    sectionText = if (course.hasValidCustomTime()) "" else course.getSectionText(),
                )
            }

            val request = PutDataMapRequest.create(PATH_TODAY).apply {
                dataMap.putLong(KEY_SENT_AT, System.currentTimeMillis())
                dataMap.putInt(KEY_COUNT, remaining.size)
                remaining.forEachIndexed { i, c ->
                    dataMap.putString("name$i", c.name)
                    dataMap.putString("classroom$i", c.classroom)
                    dataMap.putString("teacher$i", c.teacher)
                    dataMap.putInt("startMinute$i", c.startMinute)
                    dataMap.putInt("endMinute$i", c.endMinute)
                    dataMap.putString("section$i", c.sectionText)
                }
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request)
                .addOnSuccessListener {
                    NexioLog.i(TAG, "push ok ($reason): ${remaining.size} courses")
                }
                .addOnFailureListener { e ->
                    NexioLog.w(TAG, "push failed ($reason): ${e.message}")
                }
        }.onFailure { e ->
            NexioLog.w(TAG, "push error ($reason): ${e.message}")
        }
    }

    /** 手表主动发消息来要数据时调用 */
    fun pushTodayFromWatch(context: Context) = pushToday(context, "watch-request")

    private data class WatchCoursePayload(
        val name: String,
        val classroom: String,
        val teacher: String,
        val startMinute: Int,
        val endMinute: Int,
        val sectionText: String,
    )

    private fun parseMinutes(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
