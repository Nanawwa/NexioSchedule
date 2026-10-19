package com.haooz.chedule.data

/**
 * 课间倒计时：两节课之间的间隔还剩多久。
 *
 * 只做纯计算，不碰通知、不读偏好，方便在 `:core` 的 commonTest 里覆盖跨天、连堂、
 * 末节课这些边界。调用方负责把「今天有哪些课」按开始时间排好序传进来。
 *
 * 判定为课间的条件：当前时间落在某节课结束之后、下一节课开始之前，且两者不是同一节课。
 * 连堂课（第一节的下课时间等于第二节的上课时间）不算课间，不会产生倒计时。
 */
object BreakCountdown {

    /** 一次课间。 */
    data class Break(
        /** 上一节刚结束的课 */
        val previous: Course,
        /** 下一节要开始的课 */
        val next: Course,
        /** 课间开始（上一节下课）的毫秒时间戳 */
        val startMillis: Long,
        /** 课间结束（下一节上课）的毫秒时间戳 */
        val endMillis: Long,
    ) {
        /** 课间总长度（毫秒） */
        val durationMillis: Long get() = endMillis - startMillis

        /** 距下一节上课还有多少毫秒；已上课返回 0 */
        fun remainingMillis(nowMillis: Long): Long =
            (endMillis - nowMillis).coerceAtLeast(0L)
    }

    /**
     * 列出 [courses] 当天的全部课间，按开始时间升序。
     *
     * 相邻关系按「开始时刻」排序后两两配对，与 [find] 同一口径：连堂课
     * （前一节下课 = 后一节上课）与时间重叠都不产生课间。
     */
    fun breaks(
        courses: List<Course>,
        sectionTimes: Map<Int, String>,
        dayStartMillis: Long,
    ): List<Break> {
        val ordered = courses
            .mapNotNull { course ->
                val start = course.getEffectiveStartTime(sectionTimes)?.toMillisOfDay()
                val end = course.getEffectiveEndTime(sectionTimes)?.toMillisOfDay()
                if (start == null || end == null || end <= start) null
                else Triple(course, start, end)
            }
            .sortedBy { it.second }

        val result = mutableListOf<Break>()
        for (i in 0 until ordered.size - 1) {
            val (previous, _, previousEnd) = ordered[i]
            val (next, nextStart, _) = ordered[i + 1]
            val breakStart = dayStartMillis + previousEnd
            val breakEnd = dayStartMillis + nextStart
            if (breakEnd <= breakStart) continue
            result += Break(previous, next, breakStart, breakEnd)
        }
        return result
    }

    /**
     * 找出 [nowMillis] 所在的课间，不在课间时返回 null。
     *
     * @param courses 当天的课，必须已按开始时间升序排列（[DayScheduleResolver.resolveDaySchedule]
     *   返回的就是这个顺序）。乱序会让相邻判定失效，所以这里再排一次，代价可忽略。
     * @param sectionTimes 全局绝对节次号 -> "HH:mm-HH:mm"，用来把节次换成具体时刻。
     * @param dayStartMillis 当天 00:00 的毫秒时间戳，用来把 "HH:mm" 拼成绝对时间。
     */
    fun find(
        courses: List<Course>,
        sectionTimes: Map<Int, String>,
        dayStartMillis: Long,
        nowMillis: Long,
    ): Break? = breaks(courses, sectionTimes, dayStartMillis)
        .firstOrNull { nowMillis in it.startMillis until it.endMillis }

    /** "HH:mm" 转成当天零点起的毫秒数；格式不对返回 null。 */
    private fun String.toMillisOfDay(): Long? {
        val parts = split(":")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return (hour * 60L + minute) * 60_000L
    }
}
