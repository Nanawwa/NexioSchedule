package com.haooz.chedule.data

import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 批量日期解析：结果必须与逐日解析**完全一致**。
 *
 * 周课表页现在用批量入口（133 格一次算完）。批量入口把教学周重组规则、
 * 学期起始日、节次表、临时调课这些共享项提到循环外，只要有一处算错，
 * 整个学期的课表都会错位 —— 所以这里逐日对照，而不是只看几个样本。
 */
class DayScheduleBatchTest {

    private lateinit var storage: InMemoryKeyValueStore

    @BeforeTest
    fun setUp() {
        storage = InMemoryKeyValueStore()
        AppStorage.init { storage }
    }

    @AfterTest
    fun tearDown() {
        // 其他测试不依赖已初始化的 AppStorage，这里无需恢复
    }

    private fun course(id: String, day: Int, start: Int, end: Int) = Course(
        id = id,
        name = "课$id",
        classroom = "A$id",
        teacher = "",
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        startWeek = 1,
        endWeek = 16,
        weekType = Course.WEEK_TYPE_ALL,
        colorRes = 0,
    )

    @Test
    fun `批量结果与逐日结果一致`() {
        val repository = CourseRepository()
        val dates = (0 until 30).map { LocalDate(2026, 10, 1).plusDays(it.toLong()) }
        val batch = DayScheduleResolver.resolveDaySchedules(dates, repository)
        dates.forEach { date ->
            val single = DayScheduleResolver.resolveDaySchedule(date, repository)
            assertEquals(
                single.courses.map { it.id },
                batch[date]?.courses?.map { it.id } ?: emptyList(),
                "日期 $date 的课程列表不一致",
            )
            assertEquals(single.displayWeek, batch[date]?.displayWeek, "日期 $date 的周次不一致")
            assertEquals(single.displayDayOfWeek, batch[date]?.displayDayOfWeek)
            assertEquals(single.isWorkSwap, batch[date]?.isWorkSwap)
        }
    }

    @Test
    fun `有课表时批量结果与逐日结果一致`() {
        val repository = CourseRepository()
        // 往仓储里塞一份真实规模的课表：周一到周五各两门，跨 16 周
        val courses = (1..5).flatMap { day ->
            listOf(
                course("d${day}a", day, 1, 2),
                course("d${day}b", day, 5, 6),
            )
        }
        repository.saveCourses(courses, notify = false)
        repository.setCurrentWeek(8)
        repository.setTotalWeeks(19)

        val dates = (0 until 40).map { LocalDate(2026, 9, 1).plusDays(it.toLong()) }
        val batch = DayScheduleResolver.resolveDaySchedules(dates, repository)
        dates.forEach { date ->
            val single = DayScheduleResolver.resolveDaySchedule(date, repository)
            assertEquals(
                single.courses.map { it.id },
                batch[date]?.courses?.map { it.id } ?: emptyList(),
                "日期 $date 的课程列表不一致",
            )
        }
    }

    @Test
    fun `批量解析空日期列表返回空`() {
        assertEquals(
            emptyMap(),
            DayScheduleResolver.resolveDaySchedules(emptyList(), CourseRepository()),
        )
    }

    @Test
    fun `重复日期只算一次`() {
        val repository = CourseRepository()
        val date = LocalDate(2026, 10, 21)
        val batch = DayScheduleResolver.resolveDaySchedules(listOf(date, date, date), repository)
        assertEquals(1, batch.size)
    }
}
