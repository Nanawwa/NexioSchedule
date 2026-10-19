package com.haooz.chedule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.layout.SharedScrollBehavior
import com.haooz.chedule.data.Course
import com.haooz.chedule.data.CourseRepository
import com.haooz.chedule.data.DayScheduleResolver
import com.haooz.chedule.data.TempAdjustments
import com.haooz.chedule.data.plusDays
import com.haooz.chedule.data.todayLocalDate
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NativeMiuixTextField
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 临时调课。
 *
 * 设计取舍：
 * - **只改某一天**，课表原数据一个字节不动。日期过后自动失效，用户也能随时恢复。
 * - 入口独立（我的 → 临时调课），不藏在课程详情里 —— 详情页只显示某一周，
 *   要改「明天」得先翻周，路径太长。
 * - 三种操作：停上本节、改节次/教室、新增一节临时课。
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

    // 该日解析结果已叠加调休与临时调课 —— 与今日页、小组件、提醒走同一条链路
    val courses = remember(selectedDate, refreshKey) {
        runCatching {
            DayScheduleResolver.resolveDaySchedule(selectedDate, repository).courses
        }.getOrDefault(emptyList())
    }
    val changes = remember(selectedDate, refreshKey) {
        TempAdjustments.load(selectedDate)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = "前一天",
                onClick = { selectedDate = selectedDate.plusDays(-1) },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${selectedDate.monthNumber}月${selectedDate.dayOfMonth}日 ${weekName(selectedDate)}",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (changes.isNotEmpty()) {
                    Text(
                        text = "已调整 ${changes.size} 处",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(
                text = if (selectedDate == today) "今天" else "后一天",
                onClick = {
                    selectedDate = if (selectedDate == today) today else selectedDate.plusDays(1)
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (courses.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "这一天没有课",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = course.name,
                                style = MiuixTheme.textStyles.body1.copy(fontSize = 17.sp),
                                color = if (cancelled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = course.getSectionText(),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
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
                            )
                        }
                        if (cancelled) {
                            Text(
                                text = "今天已停上",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        } else if (moved) {
                            Text(
                                text = "已调整节次/教室",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "调整",
                                onClick = { editing = course to selectedDate },
                            )
                            TextButton(
                                text = if (cancelled) "恢复" else "停上",
                                onClick = {
                                    val others = TempAdjustments.load(selectedDate).filter {
                                        when (it) {
                                            is TempAdjustments.Change.Cancel ->
                                                it.courseId != course.id

                                            is TempAdjustments.Change.Move ->
                                                it.courseId != course.id

                                            is TempAdjustments.Change.Add -> true
                                        }
                                    }
                                    TempAdjustments.save(
                                        selectedDate,
                                        if (cancelled) {
                                            others
                                        } else {
                                            others + TempAdjustments.Change.Cancel(course.id)
                                        },
                                    )
                                    refreshKey++
                                    onChanged()
                                },
                            )
                        }
                    }
                }
            }

            item {
                TextButton(
                    text = "新增一节临时课",
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    TempAdjustDialog(
        target = editing,
        onDismiss = { editing = null },
        onSaved = {
            editing = null
            refreshKey++
            onChanged()
        },
    )

    TempAdjustAddDialog(
        show = showAdd,
        date = selectedDate,
        onDismiss = { showAdd = false },
        onSaved = {
            showAdd = false
            refreshKey++
            onChanged()
        },
    )
}

private fun weekName(date: LocalDate): String = when (date.dayOfWeek.isoDayNumber) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

/**
 * 新增一节临时课：只在选中那天生效，不写进课表。
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
    var name by remember { mutableStateOf("") }
    var classroom by remember { mutableStateOf("") }
    var startSection by remember { mutableIntStateOf(1) }
    var endSection by remember { mutableIntStateOf(2) }

    OverlayDialog(
        title = "新增临时课",
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${date.monthNumber}月${date.dayOfMonth}日 ${weekName(date)}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))

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
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("节次", style = MiuixTheme.textStyles.body1)
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
                        ),
                    )
                    onSaved()
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
