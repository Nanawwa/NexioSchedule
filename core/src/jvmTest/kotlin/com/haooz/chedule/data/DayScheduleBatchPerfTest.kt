package com.haooz.chedule.data

import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 批量解析的性能护栏。
 *
 * 周课表页一次要算 19 周 × 7 天 = 133 格。批量入口把共享项提到循环外，
 * 这条测试守住「批量必须明显快于逐日」——将来有人把共享项搬回循环里，
 * 这里会红。
 */
class DayScheduleBatchPerfTest {

    private lateinit var storage: InMemoryKeyValueStore

    @BeforeTest
    fun setUp() {
        storage = InMemoryKeyValueStore()
        AppStorage.init { storage }
    }

    @Test
    fun `批量解析明显快于逐日解析`() {
        val repository = CourseRepository()
        val dates = (0 until 133).map { LocalDate(2026, 9, 1).plusDays(it.toLong()) }

        // 预热：JIT 与仓库缓存都要先热起来，否则量的是冷启动
        repeat(5) { DayScheduleResolver.resolveDaySchedules(dates, repository) }
        repeat(5) { dates.forEach { DayScheduleResolver.resolveDaySchedule(it, repository) } }

        val repeats = 20
        val batchStart = System.nanoTime()
        repeat(repeats) { DayScheduleResolver.resolveDaySchedules(dates, repository) }
        val batchMs = (System.nanoTime() - batchStart) / repeats / 1_000_000.0

        val singleStart = System.nanoTime()
        repeat(repeats) {
            dates.forEach { DayScheduleResolver.resolveDaySchedule(it, repository) }
        }
        val singleMs = (System.nanoTime() - singleStart) / repeats / 1_000_000.0

        println("批量 ${"%.2f".format(batchMs)}ms / 逐日 ${"%.2f".format(singleMs)}ms")
        assertTrue(
            batchMs < singleMs,
            "批量(${"%.2f".format(batchMs)}ms) 不应慢于逐日(${"%.2f".format(singleMs)}ms)",
        )
    }
}
