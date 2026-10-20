package com.haooz.chedule.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant
import java.time.LocalTime

/**
 * 表盘数据源。
 *
 * ## 为什么用「时间线」而不是「实时计算 + 推送刷新」
 *
 * 之前的实现是：每次系统来问，就按 `System.currentTimeMillis()` 算一次当时的状态，
 * 然后靠 `requestUpdateAll` 在状态切换时主动推。问题在于：
 *
 * - 系统对主动推送并不可靠，某些表盘/省电策略下不响应，表盘就停在旧内容；
 * - 息屏（AOD）和「直接启动」阶段系统可能随时来取，这时再算一次也可能取不到数据；
 * - 每分钟的状态变化全压在这条链上，一旦断掉就永远不更新。
 *
 * 官方给的正解是 [SuspendingTimelineComplicationDataSourceService]：把**未来的
 * 状态变化**一次性算成时间线交给系统，由表盘自己在到点时切换。
 * 我们这一天最多十几节课，时间线也就几十条，完全可以提前算完。
 *
 * 这样即使手机再也不推数据、应用进程被杀，表盘上「上课中 → 课间 → 下一节」
 * 也能自己走完一整天。
 */
class CourseComplicationService : SuspendingTimelineComplicationDataSourceService() {

    private val settings by lazy { WearSettings(this) }

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
        return ComplicationRenderer.render(
            context = this,
            type = type,
            state = WatchState.InClass(sample, remainToEndMillis = 25 * 60_000L),
            settings = settings,
            isPreview = true,
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val snapshot = SnapshotStore.load(this)
        val now = System.currentTimeMillis()
        val type = request.complicationType

        // 时间线覆盖今天剩余的全部状态切换；没有后续课程时只给一条当前态。
        val entries = buildTimeline(type, snapshot, now)

        val current = snapshot.resolve(now, minuteOfDay(now))
        // defaultComplicationData 不可为空：优先当前态，渲染不出来就退回「还没同步」。
        // 两者都渲染不出（类型不支持）时返回 null —— 父类允许，系统会显示空槽位。
        val fallback = ComplicationRenderer.render(
            context = this,
            type = type,
            state = current,
            settings = settings,
            nowMillis = now,
        ) ?: ComplicationRenderer.render(
            context = this,
            type = type,
            state = WatchState.NoData,
            settings = settings,
            nowMillis = now,
        ) ?: return null

        return ComplicationDataTimeline(
            defaultComplicationData = fallback,
            timelineEntries = entries,
        )
    }

    /**
     * 把今天剩余的状态切换预先算成时间线。
     *
     * 每条 `TimelineEntry` 表示「从 start 到 end 这段区间显示这份数据」。
     * 系统在区间切换时自己换内容，不需要我们参与。
     */
    private fun buildTimeline(
        type: ComplicationType,
        snapshot: TodaySnapshot,
        nowMillis: Long,
    ): List<TimelineEntry> {
        if (snapshot.courses.isEmpty()) return emptyList()

        val entries = mutableListOf<TimelineEntry>()
        val midnight = startOfDayMillis(nowMillis)
        // 只看今天剩下的部分：已经结束的课不必再排
        for (course in snapshot.courses) {
            val startMs = midnight + course.startMinute * 60_000L
            val endMs = midnight + course.endMinute * 60_000L
            if (endMs <= nowMillis) continue

            // 上课区间 [start, end)
            if (startMs >= nowMillis) {
                val state = WatchState.InClass(course, remainToEndMillis = 0L)
                // 这条的「剩余时间」必须按段起点算，否则倒计时会从错误的值开始。
                // 时间线每条各算一次，系统到点直接切。
                renderAt(type, state, startMs)?.let { data ->
                    entries += TimelineEntry(TimeInterval(
                        Instant.ofEpochMilli(startMs),
                        Instant.ofEpochMilli(endMs),
                    ), data)
                }
            } else {
                // 正在上的课：从当前时刻到下课
                val state = WatchState.InClass(course, remainToEndMillis = endMs - nowMillis)
                renderAt(type, state, nowMillis)?.let { data ->
                    entries += TimelineEntry(TimeInterval(
                        Instant.ofEpochMilli(nowMillis),
                        Instant.ofEpochMilli(endMs),
                    ), data)
                }
            }

            // 课间：这节课结束到下一节开始
            val next = snapshot.courses.firstOrNull { it.startMinute >= course.endMinute }
            if (next != null && next.startMinute > course.endMinute) {
                val breakStart = endMs
                val breakEnd = midnight + next.startMinute * 60_000L
                if (breakEnd > nowMillis) {
                    val from = maxOf(breakStart, nowMillis)
                    val state = WatchState.OnBreak(
                        previous = course,
                        next = next,
                        remainToStartMillis = breakEnd - from,
                    )
                    renderAt(type, state, from)?.let { data ->
                        entries += TimelineEntry(TimeInterval(
                            Instant.ofEpochMilli(from),
                            Instant.ofEpochMilli(breakEnd),
                        ), data)
                    }
                }
            }
        }

        // 「今天没课了」：最后一节结束后
        snapshot.courses.maxOfOrNull { it.endMinute }?.let { lastEnd ->
            val lastEndMs = midnight + lastEnd * 60_000L
            if (lastEndMs > nowMillis) {
                renderAt(type, WatchState.Done, lastEndMs)?.let { data ->
                    entries += TimelineEntry(TimeInterval(
                        Instant.ofEpochMilli(lastEndMs),
                        Instant.ofEpochMilli(midnight + 24 * 60 * 60_000L),
                    ), data)
                }
            }
        }

        return entries.sortedBy { it.validity.start }
    }

    /**
     * 渲染时间线的某一段。
     *
     * [atMillis] 是**那一段的起点**，不是"现在"：倒计时要以它为基准开始数。
     * 因此这里不能走 resolve() 的过期判定 —— 那会把整条时间线都判成过期。
     * 过期只在给出 defaultComplicationData 时判断一次。
     */
    private fun renderAt(
        type: ComplicationType,
        state: WatchState,
        atMillis: Long,
    ): ComplicationData? = ComplicationRenderer.render(
        context = this,
        type = type,
        state = state,
        settings = settings,
        nowMillis = atMillis,
    )

    private fun minuteOfDay(millis: Long): Double {
        val t = LocalTime.ofInstant(Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
        return t.hour * 60.0 + t.minute + t.second / 60.0
    }

    private fun startOfDayMillis(millis: Long): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }
}
