package com.haooz.chedule.data

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 临时调课：只改动某一天，不碰课表本身。
 *
 * 和「调课」（拖拽后按周拆分/合并，会真的改写课表数据）是两件事：
 * 老师临时换了一节课的教室、或者本周某天课被冲掉，用户只想让今天和提醒跟着变，
 * 下周回到原样。所以这里存的是「某天 → 对当天课程的增删改」，解析当天课表时叠加。
 *
 * 存储格式（`temp_adjustments` prefs，键 `day_yyyy-MM-dd`）：
 * ```
 * { "date": "2026-10-19", "changes": [
 *     { "type": "move",  "courseId": "...", "startSection": 3, "endSection": 4, "classroom": "博A302" },
 *     { "type": "cancel","courseId": "..." },
 *     { "type": "add",   "name": "临时加课", "classroom": "博B101", "startSection": 7,
 *                        "endSection": 8, "startTime": "18:30", "endTime": "20:10" }
 * ] }
 * ```
 *
 * ⚠ 落盘是 add-only 语义：同一天只保留最后一次写入的整份 changes，不做差异合并。
 * 备份/恢复按 prefs 原样搬运，不需要额外格式兼容处理。
 */
object TempAdjustments {

    const val PREFS = "temp_adjustments"
    private const val KEY_PREFIX = "day_"
    private const val KEY_INDEX = "dates"

    /** 一条临时改动。 */
    sealed interface Change {
        /**
         * 当天某节课换了节次/教室。只改这一天，课表数据不动。
         *
         * [shiftFollowing] 打开时，原本排在这节课**之后**的课会整体平移同样的节次数
         * —— 一节拖堂/顺延，后面的课不会停在原地与它撞车。
         */
        data class Move(
            val courseId: String,
            val startSection: Int,
            val endSection: Int,
            val classroom: String? = null,
            /** 旧字段：老数据只有布尔值，位移按「相对原始课表」推导 */
            val shiftFollowing: Boolean = false,
            /**
             * 编辑当时用户看到的位移（相对当前显示的节次），优先于推导值。
             *
             * 一节课被移动过以后再编辑，用户是「从挪到的位置再挪 N 节」，
             * 而原始课表已经不知道这回事了；只有记下这个 N 才不会重复平移。
             */
            val shiftBy: Int? = null,
        ) : Change

        /** 当天某节课停上。 */
        data class Cancel(val courseId: String) : Change

        /** 临时加一节课。 */
        data class Add(
            val name: String,
            val classroom: String,
            val startSection: Int,
            val endSection: Int,
            /** 非空时按自定义时间展示，优先于节次时间 */
            val startTime: String? = null,
            val endTime: String? = null,
            /**
             * 稳定身份。没有它的话两节同名同节的临时课会生成相同 id：
             * LazyColumn 的 key 撞车直接崩，取消/改时间也无从对应。
             */
            val id: String = "",
        ) : Change
    }

    /**
     * 把改动叠加到某天的**基准课表**上。
     *
     * ⚠ 入参必须是未经调课的原始课程（`DayScheduleResolver` 里
     * `tempChanges = emptyList()` 那条路径），不是已经叠加过调课的结果 ——
     * 顺延的「后面的课」正是按基准节次判定的。
     *
     * 顺序：先取消，再移动，最后加课 —— 移动和新增互不影响，
     * 取消必须最先执行，否则被取消的课会被移动规则「复活」。
     */
    fun apply(courses: List<Course>, changes: List<Change>): List<Course> {
        if (changes.isEmpty()) return courses

        val cancelled = changes.filterIsInstance<Change.Cancel>().map { it.courseId }.toSet()
        val moves = changes.filterIsInstance<Change.Move>().associateBy { it.courseId }

        // 临时加课也是「当天的课」：先并进列表，取消/改时间才能作用在它身上。
        // 之前把 Add 放在最后追加，导致给临时课点「停上」或「改时间」完全没反应。
        val all = courses + changes.filterIsInstance<Change.Add>()
            .map { it.toCourse(courses.firstOrNull()?.dayOfWeek ?: 1) }

        // 顺延的位移量优先用编辑时记下的 shiftBy（相对用户当时看到的位置）；
        // 老数据没有该字段，才退回按「原始课表」推导。
        val shifts = mutableMapOf<String, Int>()
        for (move in moves.values) {
            if (move.shiftBy == null && !move.shiftFollowing) continue
            val anchor = all.firstOrNull { it.id == move.courseId } ?: continue
            val delta = move.shiftBy ?: (move.startSection - anchor.startSection)
            if (delta == 0) continue
            all.asSequence()
                .filter { it.id != anchor.id && it.startSection > anchor.endSection }
                .forEach { later ->
                    shifts[later.id] = (shifts[later.id] ?: 0) + delta
                }
        }

        return all
            .filter { it.id !in cancelled }
            .map { course ->
                val move = moves[course.id]
                if (move != null) {
                    return@map course.copy(
                        startSection = move.startSection,
                        endSection = move.endSection,
                        classroom = move.classroom ?: course.classroom,
                    )
                }
                // 自己另有一条 Move 的课以显式规则为准，不再叠加顺延
                val shift = shifts[course.id] ?: return@map course
                val start = (course.startSection + shift).coerceAtLeast(1)
                val end = (course.endSection + shift).coerceAtLeast(start)
                course.copy(startSection = start, endSection = end)
            }
            // 调课可能把课挪乱，按节次重新排一遍，保持解析结果的既有约定
            .sortedWith(compareBy({ it.startSection }, { it.name }))
    }

    /** 给临时加课生成一个不会撞的身份。 */
    fun newAddId(): String =
        "temp-${Clock.System.now().toEpochMilliseconds()}-${(0..9999).random()}"

    /** 临时加课在当天课程列表里的最终 id（老数据没有显式 id 时按原规则推导）。 */
    fun identityOf(add: Change.Add): String =
        add.id.ifBlank { "temp-${add.name.hashCode()}-${add.startSection}" }

    private fun Change.Add.toCourse(dayOfWeek: Int): Course {
        val hasTime = !startTime.isNullOrBlank() && !endTime.isNullOrBlank()
        return Course(
            id = identityOf(this),
            name = name,
            classroom = classroom,
            teacher = "",
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            startWeek = 0,
            endWeek = 0,
            weekType = Course.WEEK_TYPE_ALL,
            colorRes = 0,
            isCustomTime = hasTime,
            customStartTime = startTime,
            customEndTime = endTime,
        )
    }

    // —— 存储 ——

    fun load(date: LocalDate): List<Change> {
        // 存储未初始化时直接当作没有改动：解析当天课表是高频路径，
        // 不能因为还没 init 就在这里抛异常把整个课表带崩
        if (!AppStorage.isReady) return emptyList()
        val json = store().getStringOrNull(KEY_PREFIX + date.toString()) ?: return emptyList()
        return runCatching { decode(json) }.getOrDefault(emptyList())
    }

    fun save(date: LocalDate, changes: List<Change>) {
        val key = KEY_PREFIX + date.toString()
        store().edit {
            if (changes.isEmpty()) {
                remove(key)
            } else {
                putString(key, encode(changes))
            }
        }
        syncIndex(date, present = changes.isNotEmpty())
    }

    fun clear(date: LocalDate) {
        store().edit { remove(KEY_PREFIX + date.toString()) }
        syncIndex(date, present = false)
    }

    /**
     * 清掉今天之前的所有改动，避免长期堆积。
     *
     * [KeyValueStore] 没有列举键的能力，所以另存一份日期索引；
     * 索引与实际键不一致时以实际读取为准，索引只用于知道「该删哪些」。
     */
    fun pruneBefore(today: LocalDate) {
        val stale = indexedDates().filter { it < today }
        if (stale.isEmpty()) return
        store().edit { stale.forEach { remove(KEY_PREFIX + it.toString()) } }
        writeIndex(indexedDates().filter { it >= today }.toSet())
    }

    private fun syncIndex(date: LocalDate, present: Boolean) {
        val current = indexedDates()
        val next = if (present) current + date else current - date
        if (next != current) writeIndex(next)
    }

    private fun indexedDates(): Set<LocalDate> =
        store().getString(KEY_INDEX, "")
            .split(',')
            .mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            .toSet()

    private fun writeIndex(dates: Set<LocalDate>) {
        store().edit {
            putString(KEY_INDEX, dates.sorted().joinToString(",") { it.toString() })
        }
    }

    private fun store(): KeyValueStore = AppStorage.store(PREFS)

    // —— 序列化 ——

    private fun encode(changes: List<Change>): String {
        val arr = buildJsonArray {
            for (change in changes) {
                add(
                    buildJsonObject {
                        when (change) {
                            is Change.Move -> {
                                put("type", "move")
                                put("courseId", change.courseId)
                                put("startSection", change.startSection)
                                put("endSection", change.endSection)
                                change.classroom?.let { put("classroom", it) }
                                if (change.shiftFollowing) put("shiftFollowing", true)
                                change.shiftBy?.let { put("shiftBy", it) }
                            }

                            is Change.Cancel -> {
                                put("type", "cancel")
                                put("courseId", change.courseId)
                            }

                            is Change.Add -> {
                                put("type", "add")
                                put("name", change.name)
                                put("classroom", change.classroom)
                                put("startSection", change.startSection)
                                put("endSection", change.endSection)
                                change.startTime?.let { put("startTime", it) }
                                change.endTime?.let { put("endTime", it) }
                                if (change.id.isNotEmpty()) put("id", change.id)
                            }
                        }
                    }
                )
            }
        }
        return arr.toString()
    }

    private fun decode(json: String): List<Change> {
        val arr = Json.parseToJsonElement(json).jsonArray
        return arr.mapNotNull { element ->
            val obj = element.jsonObject
            when (obj.str("type")) {
                "move" -> {
                    val id = obj.str("courseId") ?: return@mapNotNull null
                    val start = obj.int("startSection") ?: return@mapNotNull null
                    val end = obj.int("endSection") ?: return@mapNotNull null
                    Change.Move(
                        courseId = id,
                        startSection = start,
                        endSection = end,
                        classroom = obj.str("classroom"),
                        shiftFollowing = obj.bool("shiftFollowing") == true,
                        shiftBy = obj.int("shiftBy"),
                    )
                }

                "cancel" -> obj.str("courseId")?.let { Change.Cancel(it) }

                "add" -> {
                    val name = obj.str("name") ?: return@mapNotNull null
                    val start = obj.int("startSection") ?: return@mapNotNull null
                    val end = obj.int("endSection") ?: return@mapNotNull null
                    Change.Add(
                        name = name,
                        classroom = obj.str("classroom").orEmpty(),
                        startSection = start,
                        endSection = end,
                        startTime = obj.str("startTime"),
                        endTime = obj.str("endTime"),
                        id = obj.str("id").orEmpty(),
                    )
                }

                else -> null
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
}
