package com.haooz.chedule.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WatchState 判定边界。
 *
 * 重点盯住早八之前那条路径：`nextIndex == 0` 时若仍去取 `courses[nextIndex - 1]`，
 * 会直接抛 IndexOutOfBoundsException —— 手表一导入课表就闪退，就是这里。
 */
class WatchStateTest {

    private fun course(name: String, startMinute: Int, endMinute: Int) =
        WatchCourse(name, "博A201", "张老师", startMinute, endMinute, "第1-2节")

    private fun snapshot(vararg courses: WatchCourse, sentAt: Long = 1L) =
        TodaySnapshot(courses.toList(), sentAt)

    @Test
    fun `早八之前不崩溃`() {
        val state = snapshot(course("高等数学", 8 * 60, 9 * 60 + 40))
            .resolve(nowMillis = 0L, nowMinuteOfDay = 7.5 * 60)
        assertTrue("早八前应是课间态", state is WatchState.OnBreak)
        state as WatchState.OnBreak
        assertEquals("高等数学", state.next.name)
        // 没有上一节时用 next 自身兜底，下游算课间长度会得到 0 而不是越界
        assertNotNull(state.previous)
    }

    @Test
    fun `课间取到正确的上下节`() {
        val state = snapshot(
            course("高数", 8 * 60, 9 * 60),
            course("英语", 10 * 60, 11 * 60),
        ).resolve(nowMillis = 0L, nowMinuteOfDay = 9.5 * 60) as WatchState.OnBreak
        assertEquals("高数", state.previous.name)
        assertEquals("英语", state.next.name)
    }

    @Test
    fun `连堂课交界点算下一节`() {
        val state = snapshot(
            course("A", 8 * 60, 9 * 60),
            course("B", 9 * 60, 10 * 60),
        ).resolve(nowMillis = 0L, nowMinuteOfDay = 9.0 * 60)
        assertEquals("B", (state as WatchState.InClass).course.name)
    }

    @Test
    fun `上课中剩余时间正确`() {
        val state = snapshot(course("高数", 8 * 60, 9 * 60))
            .resolve(nowMillis = 0L, nowMinuteOfDay = 8.5 * 60) as WatchState.InClass
        assertEquals(30 * 60_000L, state.remainToEndMillis)
    }

    @Test
    fun `全部上完返回Done`() {
        val state = snapshot(course("高数", 8 * 60, 9 * 60))
            .resolve(nowMillis = 0L, nowMinuteOfDay = 23.0 * 60)
        assertTrue(state is WatchState.Done)
    }

    @Test
    fun `从未同步返回NoData而不是Done`() {
        val state = TodaySnapshot.empty().resolve(nowMillis = 0L, nowMinuteOfDay = 10.0 * 60)
        assertTrue("没收到过快照应该提示同步，不能说今天没课", state is WatchState.NoData)
    }

    @Test
    fun `同步过但今天没课返回Done`() {
        val state = TodaySnapshot(emptyList(), sentAtMillis = 1_700_000_000_000L)
            .resolve(nowMillis = 0L, nowMinuteOfDay = 10.0 * 60)
        assertTrue(state is WatchState.Done)
    }

    @Test
    fun `跨午夜的自定义课仍算上课中`() {
        val state = snapshot(course("夜课", 23 * 60 + 30, 25 * 60))
            .resolve(nowMillis = 0L, nowMinuteOfDay = 24.0 * 60 + 10) as WatchState.InClass
        assertEquals("夜课", state.course.name)
    }

    @Test
    fun `只有一节课的三种时态`() {
        val s = snapshot(course("唯一", 8 * 60, 9 * 60))
        assertTrue(s.resolve(0L, 7.0 * 60) is WatchState.OnBreak)
        assertTrue(s.resolve(0L, 8.2 * 60) is WatchState.InClass)
        assertTrue(s.resolve(0L, 9.5 * 60) is WatchState.Done)
    }
}
