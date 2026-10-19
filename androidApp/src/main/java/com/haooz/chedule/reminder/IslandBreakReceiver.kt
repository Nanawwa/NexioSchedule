package com.haooz.chedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.haooz.chedule.data.NexioLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下课瞬间挂课间倒计时岛。
 *
 * 靠每分钟对账也能补上，但下课到下一次对账之间最多差一分钟，课间短的时候
 * 用户会看到岛先空一阵再出现。这里用精确闹钟在下课那一刻直接发出。
 *
 * 闹钟只带「这是哪一轮」的标识，下一节课的内容在触发时按当前课表现查 ——
 * 用户中途改课表时不会挂出旧数据。
 */
class IslandBreakReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IslandBreakReceiver"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_COURSE_END_MILLIS = "course_end_millis"
        // 测试课用：真实课表里查不到「下一节」，由测试流程直接把内容带过来
        const val EXTRA_NEXT_COURSE_NAME = "next_course_name"
        const val EXTRA_NEXT_CLASSROOM = "next_classroom"
        const val EXTRA_NEXT_SECTION = "next_section"
        const val EXTRA_NEXT_START_MILLIS = "next_start_millis"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!IslandNotificationHelper.isBreakCountdownEnabled(context)) {
            NexioLog.d(TAG, "Break countdown disabled, ignored")
            return
        }

        val notificationId = intent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            IslandNotificationHelper.ISLAND_NOTIFICATION_ID
        )
        val testMode = IslandNotificationHelper.isIslandTestId(notificationId)
        val now = System.currentTimeMillis()

        val syntheticName = intent.getStringExtra(EXTRA_NEXT_COURSE_NAME).orEmpty()
        if (syntheticName.isNotEmpty()) {
            val nextStartMillis = intent.getLongExtra(EXTRA_NEXT_START_MILLIS, 0L)
            if (nextStartMillis <= now) {
                NexioLog.d(TAG, "Synthetic break already over, ignored")
                return
            }
            IslandNotificationHelper.sendBreakIslandNotification(
                context = context,
                nextCourseName = syntheticName,
                nextClassroom = intent.getStringExtra(EXTRA_NEXT_CLASSROOM).orEmpty(),
                nextSection = intent.getStringExtra(EXTRA_NEXT_SECTION).orEmpty(),
                nextStartTime = clock(nextStartMillis),
                breakStartMillis = now,
                breakEndMillis = nextStartMillis,
                testMode = testMode,
            )
            return
        }

        if (testMode) return

        val breakNow = CourseReminderHelper.findCurrentBreak(context)
        if (breakNow == null) {
            // 课表已改、连堂课、或闹钟迟到跨过了整个课间：不挂岛
            NexioLog.d(TAG, "No break at fire time, ignored")
            return
        }
        IslandNotificationHelper.sendBreakIslandNotification(
            context = context,
            nextCourseName = breakNow.next.name,
            nextClassroom = breakNow.next.classroom,
            nextSection = breakNow.next.getSectionText(),
            nextStartTime = CourseReminderHelper.getCourseStartTime(
                breakNow.next,
                com.haooz.chedule.data.CourseRepository()
            ).orEmpty(),
            breakStartMillis = breakNow.startMillis,
            breakEndMillis = breakNow.endMillis,
            testMode = false,
        )
    }
    private fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(millis))
}
