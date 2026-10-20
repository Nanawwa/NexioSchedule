package com.haooz.chedule.wear

/**
 * 手表端课表快照。
 *
 * 手机把「已经算好的今天」发过来：不需要手表再理解周次、调休、节假日。
 * 手表只负责按当前时间挑出「现在这节课 / 下一节课」，把它变成表盘能显示的数据。
 *
 * 为什么在手机端算：课表解析依赖调休映射、节假日、作息切换等一堆规则，
 * 全量搬过来等于把 :core 的解析层复制一份，两边一旦不一致就是「手机显示有课、
 * 手表显示没课」这类无法解释的问题。
 *
 * @param sentAtMillis 手机生成这份数据的时刻，用于判断快照是否还新鲜
 */
data class TodaySnapshot(
    /** 今天剩余的课，按开始时间升序；已经下课的不会再发过来 */
    val courses: List<WatchCourse>,
    val sentAtMillis: Long,
) {
    companion object {
        /** 快照超过这个时长视为过期：手机可能一整天没打开过应用 */
        const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L

        fun empty(sentAtMillis: Long = 0L) = TodaySnapshot(emptyList(), sentAtMillis)
    }

    /**
     * 快照是否已经过期。
     *
     * 手机端只在应用启动 / 课表变更时推送，如果用户一整天没开过手机 App，
     * 手表上留着的可能是昨天的数据。过期后不应再拿着旧课表显示「正在上课」，
     * 否则用户看到的课名和时间都是错的 —— 比不显示更糟。
     */
    fun isStale(nowMillis: Long): Boolean =
        sentAtMillis > 0L && nowMillis - sentAtMillis > STALE_AFTER_MS
}

/**
 * 一节课在手表上需要的最小字段。
 *
 * 刻意不含周次和调休信息 —— 那些已经在手机端解析掉了，
 * 手表拿到的是「今天确实要上的课」。
 */
data class WatchCourse(
    val name: String,
    val classroom: String,
    val teacher: String,
    /** 距零点的分钟数，比 "HH:mm" 更好算 */
    val startMinute: Int,
    val endMinute: Int,
    /** "第3-4节"，自定义时间的课为空 */
    val sectionText: String,
) {
    val startMillis: Long get() = startMinute * 60_000L
    val endMillis: Long get() = endMinute * 60_000L

    /** 时间跨度，用于判断是否跨午夜 */
    val durationMinutes: Int get() = endMinute - startMinute
}

/**
 * 某一时刻手表应该显示什么。表盘和 Tile 共用这一份判定。
 */
sealed interface WatchState {
    /** 正在上课 */
    data class InClass(
        val course: WatchCourse,
        /** 距下课还剩多少毫秒；<=0 表示已到下课点但还没刷新 */
        val remainToEndMillis: Long,
    ) : WatchState

    /** 课间 */
    data class OnBreak(
        val previous: WatchCourse,
        val next: WatchCourse,
        /** 距下一节上课还剩多少毫秒 */
        val remainToStartMillis: Long,
    ) : WatchState

    /** 今天没有课了 */
    data object Done : WatchState

    /** 还没收到手机数据 */
    data object NoData : WatchState
}

/**
 * 把快照和当前时刻算成显示状态。
 *
 * 判定口径与手机端 `BreakCountdown` 保持一致：
 * 上课区间是 [start, end)，课间是 (end, nextStart)，
 * 连堂课（前一节 end == 后一节 start）不产生课间。
 *
 * @param nowMinuteOfDay 当前时刻距零点的分钟数（含小数，用毫秒精度算倒计时）
 */
fun TodaySnapshot.resolve(nowMillis: Long, nowMinuteOfDay: Double): WatchState {
    // 没收到过任何快照 ≠ 今天没课。之前两者都返回 Done，界面就会出现
    // 「今天没课了」+「打开手机 App 同步一次」这种自相矛盾的提示。
    if (courses.isEmpty()) return if (sentAtMillis <= 0L) WatchState.NoData else WatchState.Done
    // 快照太旧说明手机很久没推过：旧课表的课名/时间都可能已经不对，
    // 提示同步比照着它显示「正在上某某课」更诚实。
    if (isStale(nowMillis)) return WatchState.NoData

    // 正在上的课：取最先开始且尚未结束的那节
    val current = courses.firstOrNull { nowMinuteOfDay >= it.startMinute && nowMinuteOfDay < it.endMinute }
    if (current != null) {
        return WatchState.InClass(
            course = current,
            remainToEndMillis = ((current.endMinute - nowMinuteOfDay) * 60_000L).toLong(),
        )
    }

    // 下一节还没开始的课
    val nextIndex = courses.indexOfFirst { it.startMinute > nowMinuteOfDay }
    if (nextIndex < 0) return WatchState.Done

    val next = courses[nextIndex]
    // ⚠ courses[nextIndex - 1] 必须在 nextIndex > 0 时才算：早八之前 nextIndex == 0，
    //   先取下标再 takeIf 会直接越界崩溃（-1 取不到元素，takeIf 的 lambda 根本轮不到）。
    val previous = if (nextIndex > 0) courses[nextIndex - 1] else null
    val isBreak = previous != null && previous.endMinute <= next.startMinute
    return if (isBreak) {
        WatchState.OnBreak(
            previous = previous,
            next = next,
            remainToStartMillis = ((next.startMinute - nowMinuteOfDay) * 60_000L).toLong(),
        )
    } else {
        // 今天的课还没开始（nextIndex == 0），或者前面那节已经结束很久：
        // 仍按「距下一节」处理。没有上一节时用 next 自己顶上，
        // 让下游算课间长度时得到 0 而不是 -1 取下标。
        WatchState.OnBreak(
            previous = previous ?: next,
            next = next,
            remainToStartMillis = ((next.startMinute - nowMinuteOfDay) * 60_000L).toLong(),
        )
    }
}
