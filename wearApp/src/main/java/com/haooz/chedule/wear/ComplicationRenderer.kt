package com.haooz.chedule.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import java.util.concurrent.TimeUnit

/**
 * 把 [WatchState] 渲染成各种 Complication 数据。
 *
 * 为什么单独拎出来：表盘槽位的种类由**表盘作者**决定，不取决于我们。三星自家表盘、
 * WFF 表盘、第三方表盘给出的槽位可以是小圆、大方、长条、进度环……我们注册的类型越多，
 * 能被挂上的位置就越多。这里集中做映射，改一处所有槽位一起生效。
 *
 * 各类型的分工：
 * - SHORT_TEXT：小槽位。主文本是倒计时，标题是课名（或"下一节"）。
 * - LONG_TEXT：大槽位 / 矩形槽。主文本带课名 + 教室 + 教师，标题是状态。
 * - RANGED_VALUE：进度环 / 进度条槽位。值 = 本节已过去的比例，中间文字是倒计时。
 *   只在这一节有明确起止时间时才有意义；课间用整段课间的比例。
 * - MONOCHROMATIC_IMAGE：纯图标槽位。没有文字位置，只放一个书/铃铛图标。
 * - SMALL_IMAGE：图片槽位。同上，用彩色图标版本。
 *
 * 倒计时一律交给 [TimeDifferenceComplicationText]：Complication 的主动刷新被系统
 * 限制在平均 5 分钟一次，自己做秒级倒计时根本刷不动。TimeDifference 是「告诉系统
 * 数到某个时刻」，由表盘自己每帧重绘，息屏（AOD）下同样由系统绘制。
 */
object ComplicationRenderer {

    /**
     * 各类型统一挂在数据上的点击动作。
     *
     * 手表上点表盘组件通常期望「打开对应 app」，但我们没有独立界面，
     * 这里改成请求手机重推一次课表 —— 用户换课表后手动点一下就能立刻同步，
     * 比等下一次自动推送直观。
     */
    private fun tapIntent(context: Context): PendingIntent? = runCatching {
        val intent = android.content.Intent(context, WearMainActivity::class.java).apply {
            action = WearMainActivity.ACTION_SYNC_NOW
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    /** 倒计时文本：数到 [targetEpochMillis] */
    private fun countdown(
        targetEpochMillis: Long,
        style: TimeDifferenceStyle = TimeDifferenceStyle.SHORT_SINGLE_UNIT,
        minimumTimeUnit: TimeUnit? = null,
    ): ComplicationText {
        val builder = TimeDifferenceComplicationText.Builder(
            style,
            CountDownTimeReference(java.time.Instant.ofEpochMilli(targetEpochMillis.coerceAtLeast(0))),
        )
        // 精确模式走秒；默认走系统给的单单位（"25分"），变化频率低、更省电也更好读
        if (minimumTimeUnit != null) builder.setMinimumTimeUnit(minimumTimeUnit)
        return builder.build()
    }

    private fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()

    /** 本节已过去多少（0..1），用于进度环 */
    private fun progressOf(state: WatchState): Float? = when (state) {
        is WatchState.InClass -> {
            val total = state.course.durationMinutes.toFloat()
            if (total <= 0f) null
            else (1f - state.remainToEndMillis / (total * 60_000f)).coerceIn(0f, 1f)
        }

        is WatchState.OnBreak -> {
            val total = (state.next.startMinute - state.previous.endMinute).toFloat()
            if (total <= 0f) null
            else (1f - state.remainToStartMillis / (total * 60_000f)).coerceIn(0f, 1f)
        }

        else -> null
    }

    /**
     * 主入口。
     *
     * @param settings 用户的手表端偏好（是否显示教师/教室、课间倒计时等）
     * @param nowMillis 当前时刻，用于算倒计时目标与进度
     */
    fun render(
        context: Context,
        type: ComplicationType,
        state: WatchState,
        settings: WearSettings,
        nowMillis: Long = System.currentTimeMillis(),
        isPreview: Boolean = false,
    ): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> shortText(context, state, settings, nowMillis, isPreview)
        ComplicationType.LONG_TEXT -> longText(context, state, settings, nowMillis, isPreview)
        ComplicationType.RANGED_VALUE -> rangedValue(context, state, settings, nowMillis, isPreview)
        ComplicationType.MONOCHROMATIC_IMAGE -> monochromeImage(context, state)
        ComplicationType.SMALL_IMAGE -> smallImage(context, state)
        // 中央大组件（"复杂"槽位）实际请求的是 GOAL_PROGRESS / WEIGHTED_ELEMENTS，
        // 这两种是 androidx.wear.watchface 才有的类型。只注册 PHOTO_IMAGE 不够。
        ComplicationType.GOAL_PROGRESS -> goalProgress(context, state, settings, nowMillis, isPreview)
        ComplicationType.WEIGHTED_ELEMENTS -> weightedElements(context, state, settings, nowMillis, isPreview)
        ComplicationType.PHOTO_IMAGE -> photoImage(context, state, settings, nowMillis, isPreview)
        // 没注册的类型不该被请求到；返回 null 让系统显示空槽位而不是崩
        else -> null
    }

    // ── SHORT_TEXT：小槽位 ───────────────────────────────────────────────────

    private fun shortText(
        context: Context,
        state: WatchState,
        settings: WearSettings,
        nowMillis: Long,
        isPreview: Boolean,
    ): ComplicationData {
        val unit = if (settings.preciseCountdown) TimeUnit.SECONDS else null
        val (main, title, description) = when (state) {
            is WatchState.InClass -> Triple(
                if (isPreview) text("25分") else countdown(
                    nowMillis + state.remainToEndMillis,
                    TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                    unit,
                ),
                text(state.course.name),
                "${state.course.name} 距下课",
            )

            is WatchState.OnBreak -> {
                if (!settings.breakCountdown) {
                    // 关了课间倒计时：直接显示上课时刻，不再跳数字
                    Triple(
                        text(clockOf(state.next.startMinute)),
                        text(state.next.name),
                        "下一节 ${state.next.name} ${clockOf(state.next.startMinute)}",
                    )
                } else {
                    Triple(
                        if (isPreview) text("10分") else countdown(
                            nowMillis + state.remainToStartMillis,
                            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                            unit,
                        ),
                        text(state.next.name),
                        "距 ${state.next.name} 上课",
                    )
                }
            }

            WatchState.Done -> Triple(text("没课"), text("今天"), "今天没有课了")
            WatchState.NoData -> Triple(text("同步"), text("Nexio"), "还没收到课表，点一下同步")
        }
        return ShortTextComplicationData.Builder(main, text(description))
            .setTitle(title)
            .setTapAction(tapIntent(context))
            .build()
    }

    // ── LONG_TEXT：大槽位 / 矩形槽 ───────────────────────────────────────────

    private fun longText(
        context: Context,
        state: WatchState,
        settings: WearSettings,
        nowMillis: Long,
        isPreview: Boolean,
    ): ComplicationData {
        val unit = if (settings.preciseCountdown) TimeUnit.SECONDS else null
        return when (state) {
            is WatchState.InClass -> {
                // 主文本用 "课名 · 教室 · 教师" 的完整信息，倒计时放标题行
                val detail = describe(state.course, settings)
                LongTextComplicationData.Builder(
                    text(detail),
                    text("正在上课 ${state.course.name}"),
                )
                    .setTitle(
                        if (isPreview) text("距下课 25分")
                        else countdown(
                            nowMillis + state.remainToEndMillis,
                            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                            unit,
                        ).let { lifetime ->
                            // TimeDifference 自身只有数字，用 ^1 拼上「距下课」
                            if (isPreview) lifetime else countdownWithLabel(
                                nowMillis + state.remainToEndMillis, "距下课 ^1", unit,
                            )
                        },
                    )
                    .setTapAction(tapIntent(context))
                    .build()
            }

            is WatchState.OnBreak -> {
                val next = state.next
                val title = if (!settings.breakCountdown) {
                    text("${clockOf(next.startMinute)} 上课")
                } else if (isPreview) {
                    text("距上课 10分")
                } else {
                    countdownWithLabel(nowMillis + state.remainToStartMillis, "距上课 ^1", unit)
                }
                LongTextComplicationData.Builder(
                    text(describe(next, settings)),
                    text("下一节 ${next.name}"),
                ).setTitle(title)
                    .setTapAction(tapIntent(context))
                    .build()
            }

            WatchState.Done -> LongTextComplicationData.Builder(
                text("今天没有课了"),
                text("今天没有课"),
            ).setTitle(text("Nexio")).setTapAction(tapIntent(context)).build()

            WatchState.NoData -> LongTextComplicationData.Builder(
                text("在手表上点一下可手动同步"),
                text("还没收到课表"),
            ).setTitle(text("打开手机 Nexio 同步"))
                .setTapAction(tapIntent(context))
                .build()
        }
    }

    /** 带 "文字 ^1" 形式的倒计时；^1 会被替换成时间差 */
    private fun countdownWithLabel(
        targetEpochMillis: Long,
        template: String,
        minimumTimeUnit: TimeUnit?,
    ): ComplicationText {
        val builder = TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountDownTimeReference(java.time.Instant.ofEpochMilli(targetEpochMillis.coerceAtLeast(0))),
        ).setText(template)
        if (minimumTimeUnit != null) builder.setMinimumTimeUnit(minimumTimeUnit)
        return builder.build()
    }

    // ── RANGED_VALUE：进度环 / 进度条 ────────────────────────────────────────

    private fun rangedValue(
        context: Context,
        state: WatchState,
        settings: WearSettings,
        nowMillis: Long,
        isPreview: Boolean,
    ): ComplicationData {
        val progress = progressOf(state) ?: 0f
        val unit = if (settings.preciseCountdown) TimeUnit.SECONDS else null
        return when (state) {
            is WatchState.InClass -> RangedValueComplicationData.Builder(
                value = if (isPreview) 0.4f else progress,
                min = 0f,
                max = 1f,
                contentDescription = text("${state.course.name} 课程进度"),
            )
                .setTitle(text(state.course.name))
                .setText(
                    if (isPreview) text("25分")
                    else countdown(
                        nowMillis + state.remainToEndMillis,
                        TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                        unit,
                    ),
                )
                .setMonochromaticImage(bookImage(context))
                .setTapAction(tapIntent(context))
                .build()

            is WatchState.OnBreak -> RangedValueComplicationData.Builder(
                value = if (isPreview) 0.5f else progress,
                min = 0f,
                max = 1f,
                contentDescription = text("课间进度"),
            )
                .setTitle(text("下一节"))
                .setText(
                    if (!settings.breakCountdown) text(clockOf(state.next.startMinute))
                    else if (isPreview) text("10分")
                    else countdown(
                        nowMillis + state.remainToStartMillis,
                        TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                        unit,
                    ),
                )
                .setMonochromaticImage(bookImage(context))
                .setTapAction(tapIntent(context))
                .build()

            else -> RangedValueComplicationData.Builder(
                value = 1f,
                min = 0f,
                max = 1f,
                contentDescription = text("今天没有课"),
            ).setTitle(text("Nexio"))
                .setText(text(if (state == WatchState.NoData) "同步" else "没课"))
                .setMonochromaticImage(bookImage(context))
                .setTapAction(tapIntent(context))
                .build()
        }
    }

    // ── 纯图片槽位 ───────────────────────────────────────────────────────────

    private fun monochromeImage(context: Context, state: WatchState): ComplicationData {
        val icon = if (state is WatchState.InClass || state is WatchState.OnBreak) {
            android.R.drawable.ic_menu_today
        } else {
            android.R.drawable.ic_menu_my_calendar
        }
        return MonochromaticImageComplicationData.Builder(
            MonochromaticImage.Builder(Icon.createWithResource(context, icon)).build(),
            text(describeState(state)),
        ).setTapAction(tapIntent(context)).build()
    }

    private fun smallImage(context: Context, state: WatchState): ComplicationData {
        val icon = if (state is WatchState.InClass || state is WatchState.OnBreak) {
            android.R.drawable.ic_menu_today
        } else {
            android.R.drawable.ic_menu_my_calendar
        }
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(
                Icon.createWithResource(context, icon),
                SmallImageType.ICON,
            ).build(),
            text(describeState(state)),
        ).setTapAction(tapIntent(context)).build()
    }

    /**
     * PHOTO_IMAGE：三星表盘**中央大组件**用的类型。
     *
     * 这一类只接受一张图，没有 title/text 槽位，所以课名、地点、倒计时都得画进图里。
     * 代价：图片不会自己跳秒。倒计时按整分钟显示，并在状态切换时由
     * [ComplicationUpdater] 触发重绘，避免显示一个僵住不动的秒数。
     */
    private fun photoImage(
        context: Context,
        state: WatchState,
        settings: WearSettings,
        nowMillis: Long,
        isPreview: Boolean,
    ): ComplicationData {
        val bitmap = renderPhotoBitmap(context, state, settings, isPreview)
        return PhotoImageComplicationData.Builder(
            Icon.createWithBitmap(bitmap),
            text(describeState(state)),
        ).setTapAction(tapIntent(context)).build()
    }

    /** 画一张方形图：状态 + 课名 + 地点/教师 + 倒计时 */
    private fun renderPhotoBitmap(
        context: Context,
        state: WatchState,
        settings: WearSettings,
        isPreview: Boolean,
    ): Bitmap {
        val size = 400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 表盘底色多为深色：深底浅字，AOD 与彩色模式下都能读
        canvas.drawColor(Color.BLACK)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        val centerX = size / 2f

        paint.textSize = 34f
        paint.isFakeBoldText = false
        canvas.drawText(headlineOf(state), centerX, 72f, paint)

        val (primary, secondary) = when (state) {
            is WatchState.InClass -> state.course.name to listOfNotNull(
                state.course.classroom.takeIf { settings.showClassroom && it.isNotEmpty() },
                state.course.teacher.takeIf { settings.showTeacher && it.isNotEmpty() },
            ).joinToString(" · ")

            is WatchState.OnBreak -> state.next.name to
                listOfNotNull(
                    clockOf(state.next.startMinute) + " 上课",
                    state.next.classroom.takeIf { settings.showClassroom && it.isNotEmpty() },
                ).joinToString(" · ")

            WatchState.Done -> "今天没有课" to ""
            WatchState.NoData -> "还没同步" to "打开手机 App"
        }

        paint.textSize = 44f
        paint.isFakeBoldText = true
        drawWrapped(canvas, paint, primary, centerX, 150f, size - 48f, 50f)

        if (secondary.isNotEmpty()) {
            paint.textSize = 28f
            paint.isFakeBoldText = false
            canvas.drawText(secondary, centerX, 268f, paint)
        }

        val remain = when (state) {
            is WatchState.InClass -> minutesLabel(state.remainToEndMillis)
            is WatchState.OnBreak ->
                if (settings.breakCountdown) minutesLabel(state.remainToStartMillis)
                else clockOf(state.next.startMinute)

            else -> ""
        }
        if (remain.isNotEmpty()) {
            paint.textSize = 42f
            paint.isFakeBoldText = false
            canvas.drawText(remain, centerX, size - 48f, paint)
        }
        return bitmap
    }

    private fun minutesLabel(remainMillis: Long): String {
        val minutes = (remainMillis.coerceAtLeast(0) + 59_999) / 60_000
        return if (minutes >= 60) "${minutes / 60}小时${minutes % 60}分" else "${minutes}分"
    }

    /** 按宽度折行，最多两行，超出补省略号 */
    private fun drawWrapped(
        canvas: Canvas,
        paint: Paint,
        value: String,
        centerX: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
    ) {
        val lines = mutableListOf<String>()
        var rest = value
        while (rest.isNotEmpty() && lines.size < 2) {
            val count = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
            if (count >= rest.length) {
                lines += rest
                rest = ""
                break
            }
            lines += rest.substring(0, count)
            rest = rest.substring(count)
        }
        if (lines.isEmpty()) return
        if (rest.isNotEmpty()) lines[lines.lastIndex] = lines.last().dropLast(1) + "…"
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, centerX, startY + i * lineHeight, paint)
        }
    }

    // ── GOAL_PROGRESS：三星中央大组件 ────────────────────────────────────────

    /**
     * 进度型大组件：值 = 本节已过去的分钟数，目标 = 本节总时长。
     *
     * 三星表盘会把这类数据画成环形进度 + 中间的大号文字，正好适合
     * 「还剩多久下课」。课间则用「已休息 / 课间总长」。
     */
    private fun goalProgress(
        context: Context,
        state: WatchState,
        settings: WearSettings,
        nowMillis: Long,
        isPreview: Boolean,
    ): ComplicationData {
        val unit = if (settings.preciseCountdown) TimeUnit.SECONDS else null
        val (elapsed, total, title, text) = when (state) {
            is WatchState.InClass -> {
                val totalMin = state.course.durationMinutes.toFloat().coerceAtLeast(1f)
                val elapsedMin = (totalMin - state.remainToEndMillis / 60_000f).coerceIn(0f, totalMin)
                ProgressParts(
                    elapsedMin,
                    totalMin,
                    state.course.name,
                    if (isPreview) text("25分") else countdown(
                        nowMillis + state.remainToEndMillis,
                        TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                        unit,
                    ),
                )
            }

            is WatchState.OnBreak -> {
                val totalMin = (state.next.startMinute - state.previous.endMinute)
                    .toFloat().coerceAtLeast(1f)
                val remainMin = (state.remainToStartMillis / 60_000f).coerceIn(0f, totalMin)
                ProgressParts(
                    totalMin - remainMin,
                    totalMin,
                    "课间",
                    if (!settings.breakCountdown) text(clockOf(state.next.startMinute))
                    else if (isPreview) text("10分")
                    else countdown(
                        nowMillis + state.remainToStartMillis,
                        TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                        unit,
                    ),
                )
            }

            WatchState.Done -> ProgressParts(1f, 1f, "Nexio", text("没课"))
            WatchState.NoData -> ProgressParts(0f, 1f, "Nexio", text("同步"))
        }

        return GoalProgressComplicationData.Builder(elapsed, total, text)
            .setTitle(text(title))
            .setMonochromaticImage(bookImage(context))
            .setTapAction(tapIntent(context))
            .build()
    }

    private data class ProgressParts(
        val value: Float,
        val target: Float,
        val title: String,
        val text: ComplicationText,
    )

    // ── WEIGHTED_ELEMENTS：分段式大组件 ──────────────────────────────────────

    /**
     * 分段型大组件：用一条由若干色块组成的进度条表示今天的课程分布。
     *
     * 已上完的课用暗色、当前这节用高亮色、还没上的用中间灰，剩余时间用背景色补齐。
     * 这类槽位通常画成横向长条，正好能一眼看出「今天还剩几节」。
     */
    private fun weightedElements(
        context: Context,
        state: WatchState,
        settings: WearSettings,
        nowMillis: Long,
        isPreview: Boolean,
    ): ComplicationData {
        val colors = intArrayOf(
            0xFF1A73E8.toInt(), // 当前
            0xFF9AA0A6.toInt(), // 未开始
            0xFF3C4043.toInt(), // 已完成
            0xFF202124.toInt(), // 分隔
        )
        val elements = when (state) {
            is WatchState.InClass -> listOf(
                WeightedElementsComplicationData.Element(1f, colors[2]),
                WeightedElementsComplicationData.Element(1f, colors[0]),
                WeightedElementsComplicationData.Element(1f, colors[1]),
            )

            is WatchState.OnBreak -> listOf(
                WeightedElementsComplicationData.Element(1f, colors[2]),
                WeightedElementsComplicationData.Element(1f, colors[3]),
                WeightedElementsComplicationData.Element(1f, colors[1]),
            )

            else -> listOf(
                WeightedElementsComplicationData.Element(0.01f, colors[3]),
                WeightedElementsComplicationData.Element(0.99f, colors[1]),
            )
        }
        return WeightedElementsComplicationData.Builder(elements, text(describeState(state)))
            .setTitle(text(headlineOf(state)))
            .setMonochromaticImage(bookImage(context))
            .setTapAction(tapIntent(context))
            .build()
    }

    private fun bookImage(context: Context): MonochromaticImage =
        MonochromaticImage.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_agenda),
        ).build()

    // ── 文案 ─────────────────────────────────────────────────────────────────

    private fun describe(course: WatchCourse, settings: WearSettings): String = buildString {
        append(course.name)
        if (settings.showClassroom && course.classroom.isNotEmpty()) {
            append(" · ").append(course.classroom)
        }
        if (settings.showTeacher && course.teacher.isNotEmpty()) {
            append(" · ").append(course.teacher)
        }
        if (course.sectionText.isNotEmpty()) {
            append('\n').append(course.sectionText)
        }
    }

    private fun describeState(state: WatchState): String = when (state) {
        is WatchState.InClass -> "正在上课 ${state.course.name}"
        is WatchState.OnBreak -> "下一节 ${state.next.name}"
        WatchState.Done -> "今天没有课"
        WatchState.NoData -> "还没收到课表"
    }

    /** 分钟数 → "HH:mm" */
    fun clockOf(minuteOfDay: Int): String {
        val h = (minuteOfDay / 60) % 24
        val m = minuteOfDay % 60
        return "%02d:%02d".format(h, m)
    }

    /** 供 UI 复用：当前状态的一句话描述 */
    fun headlineOf(state: WatchState): String = when (state) {
        is WatchState.InClass -> "正在上课"
        is WatchState.OnBreak -> "课间"
        WatchState.Done -> "今天没课了"
        WatchState.NoData -> "还没同步"
    }
}
