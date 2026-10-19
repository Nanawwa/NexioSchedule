package com.haooz.chedule.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import java.time.LocalTime

/**
 * 表盘数据源。
 *
 * 所有支持 complication 的表盘（三星自家表盘、WFF 表盘、第三方表盘）
 * 都可以把这一项挂上去，显示当前课程与倒计时 —— 这是「适配各表盘」的做法：
 * 不自己做表盘，而是给系统提供一个标准数据源。
 *
 * 注册了五类槽位，覆盖表盘作者可能给出的各种形状：
 * - SHORT_TEXT        小圆槽
 * - LONG_TEXT         大槽 / 矩形槽
 * - RANGED_VALUE      进度环 / 进度条
 * - MONOCHROMATIC_IMAGE / SMALL_IMAGE   纯图标槽
 *
 * 具体渲染见 [ComplicationRenderer]；倒计时不靠刷新，交给
 * TimeDifferenceComplicationText 由系统每秒绘制（含息屏）。
 */
class CourseComplicationService : ComplicationDataSourceService() {

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

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val snapshot = SnapshotStore.load(this)
        val nowMillis = System.currentTimeMillis()
        val now = LocalTime.now()
        val nowMinute = now.hour + now.minute / 60.0 + now.second / 3600.0

        val state = snapshot.resolve(nowMillis, nowMinute)
        val data = ComplicationRenderer.render(
            context = this,
            type = request.complicationType,
            state = state,
            settings = settings,
            nowMillis = nowMillis,
        )

        listener.onComplicationData(data)

        // 到下一次状态切换（上课 / 下课）时再请求一次更新。
        // 倒计时数字本身由系统按 TimeDifference 自刷，不依赖这次刷新。
        snapshot.nextTransitionMillis(nowMinute)?.let { requestUpdateAfter(it) }
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
