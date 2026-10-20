package com.haooz.chedule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.data.Course
import com.haooz.chedule.data.CourseRepository
import com.haooz.chedule.data.DayScheduleResolver
import com.haooz.chedule.data.TempAdjustments
import com.haooz.chedule.data.plusDays
import com.haooz.chedule.data.todayLocalDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NativeMiuixTextField
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.CollapsibleTopAppBarDefaults
import top.yukonga.miuix.kmp.layout.SharedScrollBehavior
import top.yukonga.miuix.kmp.layout.collapsibleTopInset
import top.yukonga.miuix.kmp.layout.isTabletWidth
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 临时调课。
 *
 * 与「调课」（拖拽后按周拆分，会真的改写课表）不同，这里只改**选中那一天**：
 * 停上、改节次/教室、临时加一节。课表原数据一个字节不动，日期过后自动失效。
 *
 * 版式跟其他设置页一致：LazyColumn + SmallTitle 分组 + Card 承载内容，
 * 底部留 60dp 让最后一张卡片能滚到顶栏下方。
 */
@Composable
fun TempAdjustScreen(
    scrollBehavior: SharedScrollBehavior,
    liquidGlassBackdrop: com.kyant.backdrop.Backdrop,
    onChanged: () -> Unit,
) {
    val repository = remember { CourseRepository() }
    val today = remember { todayLocalDate() }
    var selectedDate by remember { mutableStateOf(today) }
    var editing by remember { mutableStateOf<Pair<Course, LocalDate>?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // 跨天移动的输入错误提示
    var moveError by remember { mutableStateOf<String?>(null) }

    val isTablet = isTabletWidth()
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val haptic = LocalHapticFeedback.current

    // 基准课表（不叠加临时调课）+ 当天规则 → 显示列表。
    // 已停上的课在 apply 后会被删掉，这里再把它们补回列表，
    // 否则「停上」之后就再也找不到恢复入口了。
    val (baseCourses, courses, changes) = remember(selectedDate, refreshKey) {
        runCatching {
            val changes = TempAdjustments.load(selectedDate)
            val base = DayScheduleResolver.resolveDaySchedule(
                selectedDate,
                repository,
                tempChanges = emptyList(),
            ).courses
            val resolved = TempAdjustments.apply(base, changes)
            val cancelledIds = changes
                .filterIsInstance<TempAdjustments.Change.Cancel>()
                .map { it.courseId }
                .toSet()
            val display = (resolved + base.filter { it.id in cancelledIds })
                .sortedWith(compareBy({ it.startSection }, { it.name }))
            Triple(base, display, changes)
        }.getOrElse {
            Triple(emptyList<Course>(), emptyList<Course>(), emptyList())
        }
    }

    /** 重新读数据并通知宿主刷新提醒 / 小组件 */
    fun reload() {
        refreshKey++
        onChanged()
    }

    /**
     * 把当前编辑的课挪到目标日期。
     *
     * 拆成两条规则存在两天：源日加「停上」（那节课从源日消失），
     * 目标日加「迁入」（这节课在目标日出现）。这样每天的解析只读自己那天的规则，
     * 不用为了找「有没有课从别处挪来」而把整个学期的规则全读一遍。
     */
    fun moveCourseToDate(target: Pair<Course, LocalDate>?, text: String) {
        if (target == null) return
        val (course, sourceDate) = target
        val targetDate = parseMoveTargetDate(text, today)
        if (targetDate == null) {
            moveError = "日期看不懂，试试 10-21 或 2026-10-21"
            return
        }
        if (targetDate == sourceDate) {
            moveError = "目标日期和原来的是同一天"
            return
        }
        moveError = null

        // 源日：取消这节课
        val sourceChanges = TempAdjustments.load(sourceDate).filterNot { change ->
            when (change) {
                is TempAdjustments.Change.Cancel -> change.courseId == course.id
                is TempAdjustments.Change.Move -> change.courseId == course.id
                is TempAdjustments.Change.MovedIn -> change.courseId == course.id
                is TempAdjustments.Change.Add -> false
            }
        }
        TempAdjustments.save(
            sourceDate,
            sourceChanges + TempAdjustments.Change.Cancel(course.id),
        )

        // 目标日：迁入这节课
        val targetChanges = TempAdjustments.load(targetDate).filterNot { change ->
            change is TempAdjustments.Change.MovedIn && change.courseId == course.id
        }
        TempAdjustments.save(
            targetDate,
            targetChanges + TempAdjustments.Change.MovedIn(
                courseId = course.id,
                name = course.name,
                classroom = course.classroom,
                teacher = course.teacher,
                startSection = course.startSection,
                endSection = course.endSection,
            ),
        )
        editing = null
        reload()
    }


    Scaffold(topBar = {}) { padding ->
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic(hapticFeedbackType = HapticFeedbackType.TextHandleMove)
            .collapsibleTopInset(scrollBehavior)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = horizontalPadding,
            top = padding.calculateTopPadding() + CollapsibleTopAppBarDefaults.CollapsedHeight +
                (if (isTablet) 24.dp else 12.dp),
            end = horizontalPadding,
            bottom = 60.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "date_nav") {
            DateNavCard(
                date = selectedDate,
                today = today,
                changeCount = changes.size,
                canGoNext = selectedDate < today.plusDays(MAX_FORWARD_DAYS),
                onPrevious = { selectedDate = selectedDate.plusDays(-1) },
                onNext = { selectedDate = selectedDate.plusDays(1) },
                onToday = { selectedDate = today },
            )
        }

        item(key = "courses_title") {
            SmallTitle(text = "当天课程")
        }

        if (courses.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "这一天没有课，可在下方新增一节临时课",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }

        items(courses, key = { it.id }) { course ->
            val cancelled = changes.any {
                it is TempAdjustments.Change.Cancel && it.courseId == course.id
            }
            val moved = changes.any {
                it is TempAdjustments.Change.Move && it.courseId == course.id
            }
            val tempAdded = changes.any {
                it is TempAdjustments.Change.Add && TempAdjustments.identityOf(it) == course.id
            }
            CourseAdjustCard(
                course = course,
                cancelled = cancelled,
                moved = moved,
                tempAdded = tempAdded,
                onAdjust = { editing = course to selectedDate },
                onToggleCancel = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    val current = TempAdjustments.load(selectedDate)
                    // 先摘掉这门课自己的规则，再按语义写回
                    val others = current.filterNot { change ->
                        when (change) {
                            is TempAdjustments.Change.Cancel -> change.courseId == course.id
                            is TempAdjustments.Change.Move -> change.courseId == course.id
                            is TempAdjustments.Change.MovedIn -> change.courseId == course.id
                            // 临时加课没有「停上」，只有「删除」：直接从规则里移除
                            is TempAdjustments.Change.Add ->
                                tempAdded && TempAdjustments.identityOf(change) == course.id
                        }
                    }
                    TempAdjustments.save(
                        selectedDate,
                        when {
                            cancelled -> others                 // 恢复
                            tempAdded -> others                 // 删除临时课
                            else -> others + TempAdjustments.Change.Cancel(course.id)
                        },
                    )
                    reload()
                },
            )
        }

        item(key = "add") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(0.dp),
            ) {
                ArrowPreference(
                    title = "新增一节临时课",
                    summary = "只在${selectedDate.monthNumber}月${selectedDate.dayOfMonth}日生效",
                    onClick = { showAdd = true },
                )
            }
        }

        moveError?.let { error ->
            item(key = "move_error") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }

        item(key = "hint") {
            Text(
                text = "临时调整只影响选中的这一天，课表原数据不会被修改；" +
                    "可以往后翻 $MAX_FORWARD_DAYS 天提前安排，" +
                    "改节次时还能把后面的课一起顺延。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    }

    TempAdjustDialog(
        target = editing,
        baseCourses = baseCourses,
        onMoveToDate = { text ->
            moveCourseToDate(editing, text)
        },
        onDismiss = { editing = null },
        onSaved = {
            editing = null
            reload()
        },
    )

    TempAdjustAddDialog(
        show = showAdd,
        date = selectedDate,
        onDismiss = { showAdd = false },
        onSaved = {
            showAdd = false
            reload()
        },
    )
}

/**
 * 解析用户填的目标日期。接受 10-21 / 10/21 / 2026-10-21 三种写法；
 * 缺年份时按 [today] 的年份补。
 */
private fun parseMoveTargetDate(text: String, today: LocalDate): LocalDate? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    runCatching { LocalDate.parse(trimmed) }.getOrNull()?.let { return it }
    val parts = trimmed.replace('/', '-').split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size == 2) {
        val month = parts[0]
        val day = parts[1]
        if (month in 1..12 && day in 1..31) {
            return runCatching { LocalDate(today.year, month, day) }.getOrNull()
        }
    }
    if (parts.size == 3) {
        return runCatching { LocalDate(parts[0], parts[1], parts[2]) }.getOrNull()
    }
    return null
}

/** 允许提前安排的最远天数：再远的话教学周/调休都可能还没定下来 */
private const val MAX_FORWARD_DAYS = 30L

private fun weekName(date: LocalDate): String = when (date.dayOfWeek.isoDayNumber) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

private fun dateLabel(date: LocalDate): String =
    "${date.monthNumber}月${date.dayOfMonth}日 ${weekName(date)}"

/**
 * 日期导航：一行里左「前一天」、中间日期、右「后一天」。
 *
 * 之前「后一天」在今天就禁用，导致只能改今天和过去的日期 —— 而临时调课最常见的
 * 用法恰恰是提前把明天的变动记下来。现在可以往后翻 [MAX_FORWARD_DAYS] 天，
 * 并保留一个「回到今天」的快捷键。
 */
@Composable
private fun DateNavCard(
    date: LocalDate,
    today: LocalDate,
    changeCount: Int,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val isToday = date == today
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "前一天",
                    onClick = onPrevious,
                    minWidth = 72.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = dateLabel(date),
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            // 顺延一条规则会改动多节课，按「条」计数才不会被误解成只改了一节
                            changeCount > 0 -> "已调整 $changeCount 条"
                            isToday -> "今天"
                            else -> "无调整"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (changeCount > 0) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                TextButton(
                    text = "后一天",
                    onClick = onNext,
                    enabled = canGoNext,
                    minWidth = 72.dp,
                )
            }
            if (!isToday) {
                TextButton(
                    text = "回到今天",
                    onClick = onToday,
                    minWidth = 96.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                )
            }
        }
    }
}

/** 单节课的调整卡片：标题行 + 时间/地点 + 状态行 + 两个操作 */
@Composable
private fun CourseAdjustCard(
    course: Course,
    cancelled: Boolean,
    moved: Boolean,
    tempAdded: Boolean,
    onAdjust: () -> Unit,
    onToggleCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = course.name,
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 17.sp),
                    fontWeight = FontWeight.Medium,
                    color = if (cancelled) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = course.getTimeDisplayText(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                )
            }

            val detail = buildList {
                if (course.classroom.isNotEmpty()) add(course.classroom)
                if (course.teacher.isNotEmpty()) add(course.teacher)
            }.joinToString(" | ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            val status = when {
                cancelled -> "今天已停上"
                tempAdded && moved -> "临时课，已调整节次或教室"
                tempAdded -> "临时添加，只在这一天"
                moved -> "已调整节次或教室"
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "改时间 / 教室",
                    onClick = onAdjust,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = when {
                        cancelled -> "恢复"
                        tempAdded -> "删除"
                        else -> "停上"
                    },
                    onClick = onToggleCancel,
                    textColor = if (cancelled) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 新增一节临时课。
 *
 * 教室可留空 —— 临时加的课经常只是「几点到哪里」，强制填全反而增加操作成本。
 */
@Composable
private fun TempAdjustAddDialog(
    show: Boolean,
    date: LocalDate,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    if (!show) return
    var name by remember(show) { mutableStateOf("") }
    var classroom by remember(show) { mutableStateOf("") }
    var startSection by remember(show) { mutableIntStateOf(1) }
    var endSection by remember(show) { mutableIntStateOf(2) }

    OverlayDialog(
        title = "新增临时课",
        summary = "只加在 ${dateLabel(date)}",
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            NativeMiuixTextField(
                value = name,
                onValueChange = { name = it },
                label = "课程名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                requestFocus = false,
            )
            Spacer(Modifier.height(10.dp))
            NativeMiuixTextField(
                value = classroom,
                onValueChange = { classroom = it },
                label = "地点（可留空）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                requestFocus = false,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "节次",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberPicker(
                    value = startSection,
                    onValueChange = {
                        startSection = it
                        if (endSection < it) endSection = it
                    },
                    range = 1..12,
                    visibleItemCount = 3,
                    itemHeight = 40.dp,
                    label = { "第${it}节" },
                    modifier = Modifier.weight(1f),
                )
                NumberPicker(
                    value = endSection,
                    onValueChange = { if (it >= startSection) endSection = it },
                    range = startSection..12,
                    visibleItemCount = 3,
                    itemHeight = 40.dp,
                    label = { "第${it}节" },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "添加",
                    onClick = {
                        if (name.isBlank()) return@TextButton
                        TempAdjustments.save(
                            date,
                            TempAdjustments.load(date) + TempAdjustments.Change.Add(
                                name = name.trim(),
                                classroom = classroom.trim(),
                                startSection = startSection,
                                endSection = endSection,
                                id = TempAdjustments.newAddId(),
                            ),
                        )
                        onSaved()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
