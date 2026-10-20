package com.haooz.chedule.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/**
 * 某日课表解析（节假日 / 调休映射 / 末日或假期前节次排除）—— 平台中立的纯计算。
 *
 * ⚠ 提醒排课算法，行为一位都不能改：算错会让用户上课收不到通知，且极难复现。
 */
object DayScheduleResolver {

    /**
     * 某日课表解析结果。
     *
     * 小组件 / ContentProvider / 今日页 / 次日提醒共用。
     * 节假日默认空课，末日例外只返回命中课程；调休按 `followWeekday` / `followWeek` 映射。
     */
    data class DayScheduleResolution(
        val courses: List<Course>,
        /** 实际用于查课的星期（调休映射后） */
        val displayDayOfWeek: Int,
        /** 实际用于查课的周次 */
        val displayWeek: Int,
        /** 日历上的星期（未映射） */
        val calendarDayOfWeek: Int,
        /** 目标日是否为节假日 */
        val isHolidayDate: Boolean,
        /** 目标日是否配置了调休映射 */
        val isWorkSwap: Boolean,
        /** 目标日是节假日末日，且配置启用了节次课程排除 */
        val isHolidayEndCourseExclusionActive: Boolean = false,
        /** 目标日是假期前一天，且配置启用了节次课程排除 */
        val isHolidayBeforeCourseExclusionActive: Boolean = false,
    )

    /**
     * 与主课表「当前周」对齐的目标日周次。学期起始日带手动周调整，而存下的 current_week
     * 在应用跨周未打开时不会推进；调休 `followWeek` 仍由调用方优先覆盖。
     */
    fun alignedStoredWeekForDate(repository: CourseRepository, date: LocalDate): Int =
        repository.getLiveTeachingWeek(date)

    internal fun liveTeachingWeek(
        semesterStartDate: LocalDate,
        date: LocalDate,
        rules: List<TeachingWeekReorganizationRule>,
    ): Int = TeachingWeekReorganization.mapDate(semesterStartDate, date, rules)
        .week.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()

    /** 智能周末：某周里真正有课的周六 / 周日（6=周六，7=周日）。 */
    fun effectiveWeekendDays(
        semesterStartDate: LocalDate,
        week: Int,
        rules: List<TeachingWeekReorganizationRule>,
        hasCoursesOnDate: (LocalDate) -> Boolean,
    ): Set<Int> = (6..7).filterTo(mutableSetOf()) { weekday ->
        TeachingWeekReorganization
            .dateForPosition(semesterStartDate, week, weekday, rules)
            ?.let(hasCoursesOnDate) == true
    }

    fun canResolveCourseCandidates(
        datePosition: TeachingWeekPosition,
        workSwapEntry: HolidayEntry?,
        displayWeek: Int,
        totalWeeks: Int,
        lastWeekWithCourses: Int,
    ): Boolean {
        val hasTeachingPosition = !datePosition.isReorganizationPause && datePosition.weekday != null
        val explicitlyMappedPause = workSwapEntry?.let {
            it.type == HolidayManager.TYPE_WORKSWAP && it.hasFollowMapping()
        } == true
        return (hasTeachingPosition || explicitlyMappedPause) &&
            displayWeek in 1..totalWeeks && displayWeek <= lastWeekWithCourses
    }

    /**
     * 解析目标日的课表。
     *
     * @param holidayEntriesByYear 预先按年缓存的节假日条目，批量计算时传入避免重复读；
     *   为 null 时内部现读 `HolidayManager.loadAllByYear()`。
     */
    fun resolveDaySchedule(
        date: LocalDate,
        repository: CourseRepository,
        holidayEntriesByYear: Map<Int, List<HolidayEntry>>? = null,
        /**
         * 临时调课规则。`null` = 用存储里的当天规则（正常业务路径）；
         * 传 `emptyList()` 可以拿到「未叠加调课」的基准课表，
         * 临时调课页面靠它把已停上的课也留在列表里，否则无法恢复。
         */
        tempChanges: List<TempAdjustments.Change>? = null,
    ): DayScheduleResolution {
        val calendarDay = date.dayOfWeek.isoDayNumber
        val entriesByYear = holidayEntriesByYear ?: HolidayManager.loadAllByYear()
        val holidayEntries = HolidayManager.entriesForDate(entriesByYear, date)
        val targetEntry = holidayEntries.firstOrNull { it.type == HolidayManager.TYPE_WORKSWAP }
        val datePosition = repository.teachingWeekPositionForDate(date)
        // 跟随绝对日期 → 按当前课表换算成 (周次, 星期)；换课表自动跟随那一周的课
        val follow = targetEntry?.let { repository.resolveWorkSwapFollow(it) }
        val isWorkSwap = follow != null
        val displayDay = follow?.weekday
            ?: datePosition.weekday ?: calendarDay
        // 未配置调休覆盖时，用教学周映射相对今天的偏移，并避免今日调休跟随周次污染。
        val displayWeek = follow?.week
            ?: alignedStoredWeekForDate(repository, date)
        val exclusion = HolidayManager.loadEndCourseExclusion()
        val beforeExclusion = HolidayManager.loadBeforeCourseExclusion()
        return resolveDaySchedule(
            date = date,
            entriesByYear = entriesByYear,
            exclusion = exclusion,
            beforeExclusion = beforeExclusion,
            displayDayOfWeek = displayDay,
            displayWeek = displayWeek,
            calendarDayOfWeek = calendarDay,
            isWorkSwap = isWorkSwap,
            candidates = {
                val totalWeeks = repository.getTotalWeeks()
                val lastWeekWithCourses = repository.getLastWeekWithCourses()
                if (!canResolveCourseCandidates(
                        datePosition = datePosition,
                        workSwapEntry = targetEntry,
                        displayWeek = displayWeek,
                        totalWeeks = totalWeeks,
                        lastWeekWithCourses = lastWeekWithCourses,
                    )
                ) {
                    emptyList()
                } else {
                    repository.getAllCourses()
                        .filter { it.dayOfWeek == displayDay && it.isActiveInWeek(displayWeek) }
                        .sortedBy { getCourseStartTime(it, repository).toMinutes() }
                }
            },
            sectionTimes = repository::getCurrentSectionTimes,
            sectionCount = {
                repository.getMorningSections() +
                    repository.getAfternoonSections() + repository.getEveningSections()
            },
            tempChanges = tempChanges ?: TempAdjustments.load(date),
        )
    }

    /**
     * 一次算多天。给「按周铺满整个学期」这类场景用。
     *
     * 逐日调用 [resolveDaySchedule] 时，每一天都要重新读一次
     * 教学周重组规则、解码一次、算一次教学周位置，还要各读一遍
     * 节假日排除配置。19 周 × 7 天 = 133 次，重复的读和解析是纯浪费。
     *
     * 这里把只与课表有关、整批共享的部分提到循环外：
     * 教学周重组规则、学期起始日、节次表、节次数量、节假日排除配置、
     * 课表总周数与最后有课周次。逐日剩下的只有「这一天是什么」。
     */
    fun resolveDaySchedules(
        dates: List<LocalDate>,
        repository: CourseRepository,
        holidayEntriesByYear: Map<Int, List<HolidayEntry>>? = null,
    ): Map<LocalDate, DayScheduleResolution> {
        if (dates.isEmpty()) return emptyMap()

        val entriesByYear = holidayEntriesByYear ?: HolidayManager.loadAllByYear()
        val exclusion = HolidayManager.loadEndCourseExclusion()
        val beforeExclusion = HolidayManager.loadBeforeCourseExclusion()

        // 以下几项整批只取一次
        val rules = repository.getTeachingWeekReorganizations()
        val startDate = runCatching {
            LocalDate.parse(repository.getClassStartTime().replace("/", "-"))
        }.getOrNull()
        val sectionTimes = repository.getCurrentSectionTimes()
        val sectionCount = repository.getMorningSections() +
            repository.getAfternoonSections() + repository.getEveningSections()
        val totalWeeks = repository.getTotalWeeks()
        val lastWeekWithCourses = repository.getLastWeekWithCourses()
        val allCourses = repository.getAllCourses()
        // 临时调课只有「有调整的那几天」才有内容，一次读完，别逐日读 SP
        val tempByDate = TempAdjustments.loadMany(dates)

        // 每门课的起始时刻整批只算一次。
        // 逐格算的话，每格都会对当天的课重算一遍开始时间，而 getCourseStartTime
        // 每次都要重读三段作息（getPeriodTimes → getTimeConfig.effective()），
        // 133 格 × 每天几门课 = 几百次重复计算。
        val startMinutesById = HashMap<String, Int>(allCourses.size)
        allCourses.forEach { course ->
            startMinutesById[course.id] = getCourseStartTime(course, repository).toMinutes()
        }
        // 按「星期 + 起始时刻」预先分组：每格只需要挑出自己那组再排序
        val coursesByDayAndWeekday = allCourses.groupBy { it.dayOfWeek }

        return dates.distinct().associateWith { date ->
            val calendarDay = date.dayOfWeek.isoDayNumber
            val holidayEntries = HolidayManager.entriesForDate(entriesByYear, date)
            val targetEntry = holidayEntries.firstOrNull { it.type == HolidayManager.TYPE_WORKSWAP }
            val datePosition = startDate?.let {
                TeachingWeekReorganization.mapDate(it, date, rules)
            } ?: TeachingWeekPosition(0L, calendarDay, isReorganizationPause = false)
            val follow = targetEntry?.let { repository.resolveWorkSwapFollow(it) }
            val isWorkSwap = follow != null
            val displayDay = follow?.weekday
                ?: datePosition.weekday ?: calendarDay
            // alignedStoredWeekForDate 会再算一次教学周位置（重复解析学期起始日
            // 和重组规则），这里已经算过 datePosition，直接复用。
            val displayWeek = follow?.week
                ?: datePosition.week.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

            val candidates = if (!canResolveCourseCandidates(
                    datePosition = datePosition,
                    workSwapEntry = targetEntry,
                    displayWeek = displayWeek,
                    totalWeeks = totalWeeks,
                    lastWeekWithCourses = lastWeekWithCourses,
                )
            ) {
                emptyList()
            } else {
                coursesByDayAndWeekday[displayDay]
                    .orEmpty()
                    .filter { it.isActiveInWeek(displayWeek) }
                    .sortedBy { startMinutesById[it.id] ?: Int.MAX_VALUE }
            }

            val dayResolution = HolidayCourseExclusion.resolveDayCourses(
                entriesByYear = entriesByYear,
                date = date,
                exclusion = exclusion,
                candidates = { candidates },
                sectionTimes = { sectionTimes },
                sectionCount = { sectionCount },
                beforeExclusion = beforeExclusion,
            )
            DayScheduleResolution(
                courses = TempAdjustments.apply(
                    dayResolution.courses,
                    tempByDate[date] ?: emptyList(),
                ),
                displayDayOfWeek = displayDay,
                displayWeek = displayWeek,
                calendarDayOfWeek = calendarDay,
                isHolidayDate = dayResolution.isHolidayDate,
                isWorkSwap = isWorkSwap,
                isHolidayEndCourseExclusionActive = dayResolution.isHolidayEndCourseExclusionActive,
                isHolidayBeforeCourseExclusionActive =
                    dayResolution.isHolidayBeforeCourseExclusionActive,
            )
        }
    }

    fun resolveDaySchedule(
        date: LocalDate,
        entriesByYear: Map<Int, List<HolidayEntry>>,
        exclusion: HolidayEndCourseExclusion,
        displayDayOfWeek: Int,
        displayWeek: Int,
        calendarDayOfWeek: Int,
        isWorkSwap: Boolean,
        candidates: () -> List<Course>,
        sectionTimes: () -> Map<Int, String>,
        sectionCount: () -> Int,
        beforeExclusion: HolidayBeforeCourseExclusion = HolidayBeforeCourseExclusion(),
        tempChanges: List<TempAdjustments.Change> = TempAdjustments.load(date),
    ): DayScheduleResolution {
        val dayResolution = HolidayCourseExclusion.resolveDayCourses(
            entriesByYear = entriesByYear,
            date = date,
            exclusion = exclusion,
            candidates = candidates,
            sectionTimes = sectionTimes,
            sectionCount = sectionCount,
            beforeExclusion = beforeExclusion,
        )
        return DayScheduleResolution(
            // 临时调课叠在节假日/调休解析之后：用户的临时改动优先级最高
            courses = TempAdjustments.apply(dayResolution.courses, tempChanges),
            displayDayOfWeek = displayDayOfWeek,
            displayWeek = displayWeek,
            calendarDayOfWeek = calendarDayOfWeek,
            isHolidayDate = dayResolution.isHolidayDate,
            isWorkSwap = isWorkSwap,
            isHolidayEndCourseExclusionActive = dayResolution.isHolidayEndCourseExclusionActive,
            isHolidayBeforeCourseExclusionActive =
                dayResolution.isHolidayBeforeCourseExclusionActive,
        )
    }

    private fun getCourseStartTime(course: Course, repository: CourseRepository): String? =
        CourseTimeResolver.getStartTime(course, repository)

    /** 解析失败返回 `Int.MAX_VALUE` 而非 0 —— 否则没时间的课会被排到最前面。 */
    private fun String?.toMinutes(): Int {
        if (this.isNullOrBlank()) return Int.MAX_VALUE
        val parts = this.split(":")
        if (parts.size != 2) return Int.MAX_VALUE
        val h = parts[0].toIntOrNull() ?: return Int.MAX_VALUE
        val m = parts[1].toIntOrNull() ?: return Int.MAX_VALUE
        return h * 60 + m
    }
}
