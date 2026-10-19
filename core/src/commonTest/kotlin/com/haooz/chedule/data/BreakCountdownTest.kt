package com.haooz.chedule.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BreakCountdownTest {

    // 08:30-09:15、09:25-10:10、10:20-11:05
    private val sectionTimes = mapOf(
        1 to "08:30-09:15",
        2 to "09:25-10:10",
        3 to "10:20-11:05",
    )

    private fun course(start: Int, end: Int, name: String = "课$start") =
        Course(
            id = name,
            name = name,
            classroom = "A$start",
            teacher = "",
            dayOfWeek = 1,
            startSection = start,
            endSection = end,
            startWeek = 1,
            endWeek = 16,
            weekType = 0,
            colorRes = 0,
            selectedWeeks = emptyList(),
        )

    private val dayStart = 0L

    private fun at(hour: Int, minute: Int): Long =
        ((hour * 60L + minute) * 60_000L)

    @Test
    fun `两节课之间的间隔算课间`() {
        val courses = listOf(course(1, 1, "第一节"), course(2, 2, "第二节"))
        val brk = BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 20))
        assertNotNull(brk)
        assertEquals("第一节", brk.previous.name)
        assertEquals("第二节", brk.next.name)
        assertEquals(at(9, 15), brk.startMillis)
        assertEquals(at(9, 25), brk.endMillis)
        assertEquals(10 * 60_000L, brk.durationMillis)
        assertEquals(5 * 60_000L, brk.remainingMillis(at(9, 20)))
    }

    @Test
    fun `上课中不算课间`() {
        val courses = listOf(course(1, 1), course(2, 2))
        assertNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(8, 40)))
        assertNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 30)))
    }

    @Test
    fun `连堂课没有课间`() {
        // 第二节下课即第三节上课
        val backToBack = mapOf(1 to "08:30-09:15", 2 to "09:15-10:00")
        val courses = listOf(course(1, 1), course(2, 2))
        assertNull(BreakCountdown.find(courses, backToBack, dayStart, at(9, 15)))
        assertNull(BreakCountdown.find(courses, backToBack, dayStart, at(9, 14)))
    }

    @Test
    fun `只有一节课时没有课间`() {
        val courses = listOf(course(1, 1))
        assertNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 20)))
    }

    @Test
    fun `空课表返回 null`() {
        assertNull(BreakCountdown.find(emptyList(), sectionTimes, dayStart, at(9, 20)))
    }

    @Test
    fun `课间边界：下课瞬间算课间，上课瞬间不算`() {
        val courses = listOf(course(1, 1), course(2, 2))
        // 09:15 整 = 下课那一刻，课间开始
        assertNotNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 15)))
        // 09:25 整 = 上课那一刻，课间结束
        assertNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 25)))
        // 09:24:59 仍在课间
        assertNotNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 24) + 59_000L))
    }

    @Test
    fun `三节课时能定位到正确的那段课间`() {
        val courses = listOf(course(1, 1, "一"), course(2, 2, "二"), course(3, 3, "三"))
        val first = BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 20))
        assertEquals("一", first?.previous?.name)
        assertEquals("二", first?.next?.name)
        val second = BreakCountdown.find(courses, sectionTimes, dayStart, at(10, 15))
        assertEquals("二", second?.previous?.name)
        assertEquals("三", second?.next?.name)
    }

    @Test
    fun `乱序传入也能正确判定`() {
        val courses = listOf(course(2, 2, "二"), course(1, 1, "一"))
        val brk = BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 20))
        assertEquals("一", brk?.previous?.name)
        assertEquals("二", brk?.next?.name)
    }

    @Test
    fun `末节课之后不算课间`() {
        val courses = listOf(course(1, 1), course(2, 2))
        assertNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(10, 30)))
    }

    @Test
    fun `节次时间缺失的课被跳过`() {
        val courses = listOf(course(1, 1), course(9, 9))
        // 第 9 节没有时间配置，只剩一节课，判不出课间
        assertNull(BreakCountdown.find(courses, sectionTimes, dayStart, at(9, 20)))
    }

    @Test
    fun `自定义时间优先于节次时间`() {
        // 第一节自定义成 08:30-09:50，第二节仍是节次表的 09:25 开始。
        // 时间重叠，没有课间可算，不能把 09:50 之后当成课间。
        val custom = course(1, 1, "自定义").copy(
            isCustomTime = true,
            customStartTime = "08:30",
            customEndTime = "09:50",
        )
        val next = course(2, 2, "下一节")
        assertNull(BreakCountdown.find(listOf(custom, next), sectionTimes, dayStart, at(9, 55)))
    }

    @Test
    fun `自定义时间正常产生课间`() {
        // 第一节自定义成 08:00-09:00，第二节节次表 09:25 开始，中间 25 分钟
        val custom = course(1, 1, "自定义").copy(
            isCustomTime = true,
            customStartTime = "08:00",
            customEndTime = "09:00",
        )
        val next = course(2, 2, "下一节")
        val brk = BreakCountdown.find(listOf(custom, next), sectionTimes, dayStart, at(9, 10))
        assertEquals("自定义", brk?.previous?.name)
        assertEquals(at(9, 0), brk?.startMillis)
        assertEquals(at(9, 25), brk?.endMillis)
        assertEquals(25 * 60_000L, brk?.durationMillis)
    }

    @Test
    fun `breaks 列出当天全部课间`() {
        val courses = listOf(course(1, 1, "一"), course(2, 2, "二"), course(3, 3, "三"))
        val breaks = BreakCountdown.breaks(courses, sectionTimes, dayStart)
        assertEquals(2, breaks.size)
        assertEquals(at(9, 15), breaks[0].startMillis)
        assertEquals(at(9, 25), breaks[0].endMillis)
        assertEquals(at(10, 10), breaks[1].startMillis)
        assertEquals(at(10, 20), breaks[1].endMillis)
    }

    @Test
    fun `breaks 跳过连堂与重叠，不产生空课间`() {
        val backToBack = mapOf(1 to "08:30-09:15", 2 to "09:15-10:00", 3 to "10:20-11:05")
        val courses = listOf(course(1, 1), course(2, 2), course(3, 3))
        val breaks = BreakCountdown.breaks(courses, backToBack, dayStart)
        // 只有第二个交界（10:00 → 10:20）有课间
        assertEquals(1, breaks.size)
        assertEquals(at(10, 0), breaks[0].startMillis)
    }

    @Test
    fun `breaks 按开始时间配对，不受结束时间颠倒影响`() {
        // 第一节拖到 09:40，第二节 09:25 就开始了：按开始时间排序后仍是 1→2，
        // 但两者重叠，没有课间。这正是 scheduleBreakAlarms 不能假设
        //「最后一节结束得最晚」的原因。
        val overlapping = mapOf(1 to "08:30-09:40", 2 to "09:25-10:10", 3 to "10:20-11:05")
        val courses = listOf(course(1, 1), course(2, 2), course(3, 3))
        val breaks = BreakCountdown.breaks(courses, overlapping, dayStart)
        // 1→2 重叠无课间；2→3 之间 10:10 → 10:20 有
        assertEquals(1, breaks.size)
        assertEquals(at(10, 10), breaks[0].startMillis)
        assertEquals(at(10, 20), breaks[0].endMillis)
    }
}
