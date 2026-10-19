package com.haooz.chedule.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * 手表端主界面。
 *
 * 提供两件事：
 * 1. 看一眼今天剩下的课（手表屏幕小，不适合完整课表，只展示「现在 / 下一节」）
 * 2. 表盘数据源的显示选项 + 手动同步
 *
 * 为什么需要界面：表盘组件本身没法承载设置（三星/谷歌的表盘点击后通常只跳转 app），
 * 用户要关掉"课间倒计时"或"显示教师"时必须有个地方改。
 */
class WearMainActivity : ComponentActivity() {

    companion object {
        /** 表盘点击进来时带这个 action，进去后自动同步一次 */
        const val ACTION_SYNC_NOW = "com.haooz.chedule.wear.SYNC_NOW"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val syncOnLaunch = intent?.action == ACTION_SYNC_NOW
        setContent {
            WearApp(syncOnLaunch = syncOnLaunch)
        }
    }

}

/** 手表端主题 + 主界面 */
@Composable
private fun WearApp(syncOnLaunch: Boolean) {
    MaterialTheme {
        AppScaffold {
            val listState = rememberScalingLazyListState()
            ScreenScaffold(scrollState = listState, timeText = { TimeText() }) { contentPadding ->
                WearHome(
                    contentPadding = contentPadding,
                    listState = listState,
                    syncOnLaunch = syncOnLaunch,
                )
            }
        }
    }
}

@Composable
private fun WearHome(
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    syncOnLaunch: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { WearSettings(context) }
    var snapshot by remember { mutableStateOf(TodaySnapshot.empty()) }
    var syncing by remember { mutableStateOf(false) }
    /** 同步没拿到数据时给出明确反馈，而不是让界面一直停在「还没同步」 */
    var syncFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 每次进入界面先读本地缓存，再主动同步一次：
    // 只有 pull 路径能覆盖「节点列表为空但 DataItem 已在本地」的情况，
    // 这也是手表刚装好、手机不在身边时唯一的出路。
    LaunchedEffect(Unit) {
        snapshot = SnapshotStore.load(context)
        syncing = true
        val ok = SnapshotStore.syncNow(context)
        snapshot = SnapshotStore.load(context)
        syncFailed = !ok
        syncing = false
    }

    var showTeacher by remember { mutableStateOf(settings.showTeacher) }
    var showClassroom by remember { mutableStateOf(settings.showClassroom) }
    var breakCountdown by remember { mutableStateOf(settings.breakCountdown) }
    var precise by remember { mutableStateOf(settings.preciseCountdown) }

    LaunchedEffect(syncOnLaunch) {
        // 从表盘点进来时上面的 LaunchedEffect 已经同步过一次，这里只负责刷新显示
        if (!syncOnLaunch) return@LaunchedEffect
        snapshot = SnapshotStore.load(context)
    }

    val nowMinute = LocalTime.now().let { it.hour + it.minute / 60.0 + it.second / 3600.0 }
    val state = snapshot.resolve(System.currentTimeMillis(), nowMinute)

    ScalingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ListHeader { Text("Nexio 课程表") } }

        item {
            CurrentCourseCard(state = state, snapshot = snapshot, syncFailed = syncFailed)
        }

        if (state is WatchState.OnBreak && snapshot.courses.isNotEmpty()) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "下一节 ${ComplicationRenderer.clockOf(state.next.startMinute)}",
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = state.next.name,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item { ListHeader { Text("同步") } }
        item {
            Button(
                onClick = {
                    if (syncing) return@Button
                    syncing = true
                    scope.launch {
                        val ok = SnapshotStore.syncNow(context)
                        snapshot = SnapshotStore.load(context)
                        syncFailed = !ok
                        syncing = false
                    }
                },
            ) {
                Text(if (syncing) "同步中…" else "从手机同步")
            }
        }

        item { ListHeader { Text("表盘显示") } }

        item {
            SwitchButton(
                checked = showTeacher,
                onCheckedChange = {
                    showTeacher = it
                    settings.showTeacher = it
                    ComplicationUpdater.refresh(context)
                },
                label = { Text("显示教师") },
            )
        }
        item {
            SwitchButton(
                checked = showClassroom,
                onCheckedChange = {
                    showClassroom = it
                    settings.showClassroom = it
                    ComplicationUpdater.refresh(context)
                },
                label = { Text("显示教室") },
            )
        }
        item {
            SwitchButton(
                checked = breakCountdown,
                onCheckedChange = {
                    breakCountdown = it
                    settings.breakCountdown = it
                    ComplicationUpdater.refresh(context)
                },
                label = { Text("课间倒计时") },
            )
        }
        item {
            SwitchButton(
                checked = precise,
                onCheckedChange = {
                    precise = it
                    settings.preciseCountdown = it
                    ComplicationUpdater.refresh(context)
                },
                label = { Text("精确到秒") },
            )
        }
    }
}

/** 当前状态卡片：显示正在上的课 / 下一节 / 空状态 */
@Composable
private fun CurrentCourseCard(
    state: WatchState,
    snapshot: TodaySnapshot,
    syncFailed: Boolean,
) {
    val title = ComplicationRenderer.headlineOf(state)
    val (line1, line2) = when (state) {
        is WatchState.InClass -> state.course.name to buildString {
            append(state.course.classroom)
            if (state.course.teacher.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(state.course.teacher)
            }
        }.trim(' ', '·')

        is WatchState.OnBreak -> state.next.name to
            "${ComplicationRenderer.clockOf(state.next.startMinute)} 上课"

        WatchState.Done -> "今天没有课了" to
            if (snapshot.courses.isEmpty()) "打开手机 App 同步一次" else "好好休息"

        WatchState.NoData -> "还没收到课表" to
            if (syncFailed) "同步失败：确认手机蓝牙已连接且装了手表端"
            else "点下方按钮从手机同步"
    }

    Card(
        onClick = {},
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = line1,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (line2.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = line2,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
