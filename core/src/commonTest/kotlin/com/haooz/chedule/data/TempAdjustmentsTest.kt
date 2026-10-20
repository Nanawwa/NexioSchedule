package com.haooz.chedule.data

import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempAdjustmentsTest {

    private lateinit var storage: InMemoryKeyValueStore

    private fun course(
        id: String,
        name: String = id,
        classroom: String = "A101",
        start: Int = 1,
        end: Int = 1,
    ) = Course(
        id = id,
        name = name,
        classroom = classroom,
        teacher = "",
        dayOfWeek = 1,
        startSection = start,
        endSection = end,
        startWeek = 1,
        endWeek = 16,
        weekType = Course.WEEK_TYPE_ALL,
        colorRes = 0,
    )

    @BeforeTest
    fun setUp() {
        storage = InMemoryKeyValueStore()
        AppStorage.init { storage }
    }

    @AfterTest
    fun tearDown() {
        // 其他测试不依赖已初始化的 AppStorage，这里无需恢复
    }

    private val date = LocalDate(2026, 10, 19)

    @Test
    fun `取消的课从当天消失`() {
        val courses = listOf(course("a"), course("b", start = 2, end = 2))
        val result = TempAdjustments.apply(courses, listOf(TempAdjustments.Change.Cancel("a")))
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun `移动改变节次与教室`() {
        val courses = listOf(course("a", classroom = "A101", start = 1, end = 1))
        val result = TempAdjustments.apply(
            courses,
            listOf(TempAdjustments.Change.Move("a", startSection = 3, endSection = 4, classroom = "B202"))
        )
        assertEquals(3, result[0].startSection)
        assertEquals(4, result[0].endSection)
        assertEquals("B202", result[0].classroom)
    }

    @Test
    fun `移动时不传教室则沿用原值`() {
        val courses = listOf(course("a", classroom = "A101"))
        val result = TempAdjustments.apply(
            courses,
            listOf(TempAdjustments.Change.Move("a", startSection = 5, endSection = 5))
        )
        assertEquals("A101", result[0].classroom)
    }

    @Test
    fun `顺延把后面的课整体后移`() {
        val courses = listOf(
            course("a", start = 3, end = 4),
            course("b", start = 5, end = 6),
            course("c", start = 7, end = 8),
        )
        val result = TempAdjustments.apply(
            courses,
            listOf(
                TempAdjustments.Change.Move(
                    "a", startSection = 5, endSection = 6, shiftFollowing = true,
                )
            )
        )
        val byId = result.associateBy { it.id }
        assertEquals(5, byId.getValue("a").startSection)
        assertEquals(7, byId.getValue("b").startSection)
        assertEquals(9, byId.getValue("c").startSection)
    }

    @Test
    fun `顺延只影响原本排在它后面的课`() {
        val courses = listOf(
            course("before", start = 1, end = 2),
            course("a", start = 3, end = 4),
            course("after", start = 5, end = 6),
        )
        val result = TempAdjustments.apply(
            courses,
            listOf(
                TempAdjustments.Change.Move(
                    "a", startSection = 4, endSection = 5, shiftFollowing = true,
                )
            )
        ).associateBy { it.id }
        assertEquals(1, result.getValue("before").startSection)
        assertEquals(4, result.getValue("a").startSection)
        assertEquals(6, result.getValue("after").startSection)
    }

    @Test
    fun `顺延以原始课表为基准，重复保存不会叠加`() {
        val courses = listOf(course("a", start = 3, end = 4), course("b", start = 5, end = 6))
        val change = TempAdjustments.Change.Move(
            "a", startSection = 4, endSection = 5, shiftFollowing = true,
        )
        // 同一条规则连续应用两次（等价于用户反复编辑后保存），结果必须一致
        val once = TempAdjustments.apply(courses, listOf(change))
        val twice = TempAdjustments.apply(once, listOf(change))
        assertEquals(once, twice)
        assertEquals(4, twice.first { it.id == "a" }.startSection)
        assertEquals(6, twice.first { it.id == "b" }.startSection)
    }

    @Test
    fun `自己另有改动的课不被顺延叠加`() {
        val courses = listOf(course("a", start = 3, end = 4), course("b", start = 5, end = 6))
        val result = TempAdjustments.apply(
            courses,
            listOf(
                TempAdjustments.Change.Move(
                    "a", startSection = 5, endSection = 6, shiftFollowing = true,
                ),
                TempAdjustments.Change.Move("b", startSection = 9, endSection = 10),
            )
        ).associateBy { it.id }
        assertEquals(9, result.getValue("b").startSection)
    }

    @Test
    fun `顺延不会把课移到第一节之前`() {
        val courses = listOf(course("a", start = 5, end = 6), course("b", start = 7, end = 8))
        val result = TempAdjustments.apply(
            courses,
            listOf(
                TempAdjustments.Change.Move(
                    "a", startSection = 1, endSection = 2, shiftFollowing = true,
                )
            )
        ).associateBy { it.id }
        assertTrue(result.getValue("b").startSection >= 1)
    }

    @Test
    fun `加课进入当天列表`() {
        val courses = listOf(course("a"))
        val result = TempAdjustments.apply(
            courses,
            listOf(
                TempAdjustments.Change.Add(
                    name = "临时加课",
                    classroom = "B101",
                    startSection = 7,
                    endSection = 8,
                )
            )
        )
        assertEquals(2, result.size)
        val added = result.first { it.name == "临时加课" }
        assertEquals(7, added.startSection)
        assertEquals("B101", added.classroom)
    }

    @Test
    fun `加课带自定义时间时按自定义时间展示`() {
        val result = TempAdjustments.apply(
            emptyList(),
            listOf(
                TempAdjustments.Change.Add(
                    name = "讲座",
                    classroom = "",
                    startSection = 9,
                    endSection = 10,
                    startTime = "18:30",
                    endTime = "20:10",
                )
            )
        )
        assertTrue(result[0].hasValidCustomTime())
        assertEquals("18:30", result[0].customStartTime)
    }

    @Test
    fun `取消优先于移动，被取消的课不会复活`() {
        val courses = listOf(course("a", start = 1, end = 1))
        val result = TempAdjustments.apply(
            courses,
            listOf(
                TempAdjustments.Change.Move("a", startSection = 3, endSection = 3),
                TempAdjustments.Change.Cancel("a"),
            )
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `结果按节次重新排序`() {
        val courses = listOf(course("a", start = 1, end = 1), course("b", start = 2, end = 2))
        val result = TempAdjustments.apply(
            courses,
            listOf(TempAdjustments.Change.Move("a", startSection = 5, endSection = 5))
        )
        assertEquals(listOf("b", "a"), result.map { it.id })
    }

    @Test
    fun `没有任何改动时原样返回`() {
        val courses = listOf(course("a"))
        assertEquals(courses, TempAdjustments.apply(courses, emptyList()))
    }

    @Test
    fun `存进去再读出来内容一致`() {
        val changes = listOf(
            TempAdjustments.Change.Move("a", 3, 4, "B202"),
            TempAdjustments.Change.Cancel("b"),
            TempAdjustments.Change.Add("加课", "C303", 7, 8, "18:30", "20:10"),
        )
        TempAdjustments.save(date, changes)
        assertEquals(changes, TempAdjustments.load(date))
    }

    @Test
    fun `没存过的日期读出空列表`() {
        assertTrue(TempAdjustments.load(LocalDate(2020, 1, 1)).isEmpty())
    }

    @Test
    fun `清空某天会删掉整条记录`() {
        TempAdjustments.save(date, listOf(TempAdjustments.Change.Cancel("a")))
        TempAdjustments.clear(date)
        assertTrue(TempAdjustments.load(date).isEmpty())
    }

    @Test
    fun `坏掉的 JSON 当作没有改动，不抛异常`() {
        storage.edit { putString("day_$date", "{ not json") }
        assertTrue(TempAdjustments.load(date).isEmpty())
    }

    @Test
    fun `清理只删过去的日期，今天和以后保留`() {
        val yesterday = LocalDate(2026, 10, 18)
        val tomorrow = LocalDate(2026, 10, 20)
        TempAdjustments.save(yesterday, listOf(TempAdjustments.Change.Cancel("a")))
        TempAdjustments.save(date, listOf(TempAdjustments.Change.Cancel("b")))
        TempAdjustments.save(tomorrow, listOf(TempAdjustments.Change.Cancel("c")))

        TempAdjustments.pruneBefore(date)

        assertTrue(TempAdjustments.load(yesterday).isEmpty())
        assertEquals(1, TempAdjustments.load(date).size)
        assertEquals(1, TempAdjustments.load(tomorrow).size)
    }
}
