package com.haooz.chedule.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.complications.datasource.ComplicationDataSourceService
import androidx.wear.complications.datasource.ComplicationRequest
import androidx.wear.complications.data.ComplicationData
import androidx.wear.complications.data.ComplicationType
import androidx.wear.complications.data.LongTextComplicationData
import androidx.wear.complications.data.PlainComplicationText
import androidx.wear.complications.data.ShortTextComplicationData
import androidx.wear.complications.data.TimeDifferenceComplicationText
import androidx.wear.complications.data.TimeDifferenceStyle
import androidx.wear.complications.data.CountDownTimeReference
import androidx.wear.complications.data.TimeRange
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * 表盘数据源。
 *
 * 所有支持 complication 的表盘（三星自家表盘、WFF 表盘、第三方表盘）
 * 都可以把这一项挂上去，显示当前课程与倒计时 —— 这是「适配各表盘」的做法：
 * 不自己做表盘，而是给系统提供一个标准数据源。
 *
 * 倒计时不靠刷新：用 [TimeDifferenceComplicationText] 告诉系统「数到某个时刻」，
 * 由表盘自己每秒走字。Complication 的刷新频率被系统限制在 5 分钟一次，
 * 自己刷新做不出秒级倒计时。息屏(AOD)下同样由系统绘制，不需要应用参与。
 */
class CourseComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        // 表盘选择器里的预览：给一节示例课，让用户知道这一项长什么样
        val sample = WatchCourse(
            name = "大学英语Ⅱ",
            classroom = "博A201",
            teacher = "李老师",
            startMinute = 8 * 60,
            endMinute = 9 * 60 + 40,
            sectionText = "第1-2节",
        )
        return buildInClass(type, sample, remainMillis = 25 * 60_000L, isPreview = true)
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val snapshot = SnapshotStore.load(this)
        val now = LocalTime.now()
        val nowMinute = now.hour + now.minute / 60.0 + now.second / 3600.0

        val data = when (val state = snapshot.resolve(System.currentTimeMillis(), nowMinute)) {
            is WatchState.InClass -> buildInClass(request.complicationType, state.course, state.remainToEndMillis)
            is WatchState.OnBreak -> buildBreak(request.complicationType, state)
            WatchState.Done -> buildPlain(request.complicationType, "今天没课", "")
            WatchState.NoData -> buildPlain(request.complicationType, "Nexio 课表", "打开手机同步")
        }

        listener.onComplicationData(data)

        // 到下一次状态切换（上课 / 下课）时再请求一次更新。
        // 倒计时数字本身由系统按 TimeDifference 自刷，不依赖这次刷新。
        snapshot.nextTransitionMillis(nowMinute)?.let { requestUpdateAfter(it) }
    }

    /**
     * 正在上课：主文本是距下课倒计时，标题是课名，长文本带地点/教师/节次。
     *
     * 倒计时用 [TimeDifferenceComplicationText] 指向「下课时刻」，
     * 表盘会自己每秒重绘这一行 —— Complication 的主动刷新被限制在 5 分钟一次，
     * 只有交给系统计时才能做到秒级。息屏下同样生效。
     */
    private fun buildInClass(
        type: ComplicationType,
        course: WatchCourse,
        remainMillis: Long,
        isPreview: Boolean = false,
    ): ComplicationData? {
        val countdown = if (isPreview) {
            PlainComplicationText.Builder("25分钟").build()
        } else {
            TimeDifferenceComplicationText.Builder(
                TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                CountDownTimeReference(Instant.now().plusMillis(remainMillis.coerceAtLeast(0))),
            ).build()
        }
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = countdown,
                contentDescription = PlainComplicationText.Builder("距下课").build(),
            )
                .setTitle(PlainComplicationText.Builder(course.name).build())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(buildLongText(course)).build(),
                contentDescription = PlainComplicationText.Builder("正在上课 ${course.name}").build(),
            )
                .setTitle(PlainComplicationText.Builder(course.name).build())
                .build()

            else -> null
        }
    }

    /** 课间：主文本是距上课倒计时，标题是下一节课名 */
    private fun buildBreak(type: ComplicationType, state: WatchState.OnBreak): ComplicationData? {
        val next = state.next
        val countdown = TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountDownTimeReference(Instant.now().plusMillis(state.remainToStartMillis.coerceAtLeast(0))),
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = countdown,
                contentDescription = PlainComplicationText.Builder("距上课").build(),
            )
                .setTitle(PlainComplicationText.Builder(next.name).build())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(buildLongText(next)).build(),
                contentDescription = PlainComplicationText.Builder("下一节 ${next.name}").build(),
            )
                .setTitle(PlainComplicationText.Builder("下一节").build())
                .build()

            else -> null
        }
    }

    private fun buildPlain(type: ComplicationType, title: String, body: String): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(title).build(),
                contentDescription = PlainComplicationText.Builder(title).build(),
            ).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(body.ifEmpty { title }).build(),
                contentDescription = PlainComplicationText.Builder(title).build(),
            ).setTitle(PlainComplicationText.Builder(title).build()).build()

            else -> null
        }

    private fun buildLongText(course: WatchCourse): String = buildString {
        append(course.name)
        if (course.classroom.isNotEmpty()) append(" · ").append(course.classroom)
        if (course.teacher.isNotEmpty()) append(" · ").append(course.teacher)
    }

    /** 距下一次状态变化还有多久；没有后续课程时返回 null */
    private fun TodaySnapshot.nextTransitionMillis(nowMinute: Double): Long? {
        val current = courses.firstOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
        if (current != null) return ((current.endMinute - nowMinute) * 60_000L).toLong()
        val next = courses.firstOrNull { it.startMinute > nowMinute } ?: return null
        return ((next.startMinute - nowMinute) * 60_000L).toLong()
    }

    /**
     * 到点后请系统再问一次数据。
     *
     * 系统对刷新频率有配额（平均 5 分钟一次），这里只在状态真正切换的时刻请求，
     * 一天最多十来次，不会超。
     */
    private fun requestUpdateAfter(delayMillis: Long) {
        if (delayMillis <= 0) return
        ComplicationUpdater.scheduleRefresh(this, delayMillis)
    }
}
