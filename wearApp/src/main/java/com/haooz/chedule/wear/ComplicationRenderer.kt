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
        // 小圆槽在多数表盘上只画主文本（标题常常被省略）。所以主文本必须自解释：
        // 倒计时单独一个「25分」放在槽里，用户根本不知道在数什么。
        // 这里一律带上语境（课名 / 「距上课」），把纯数字留给大槽和进度环。
        val (main, title, description) = when (state) {
            is WatchState.InClass -> Triple(
                if (isPreview) text("高数 25分") else countdownWithLabel(
                    nowMillis + state.remainToEndMillis,
                    "${shortName(state.course.name)} ^1",
                    unit,
                ),
                text(state.course.name),
                "${state.course.name} 距下课",
            )

            is WatchState.OnBreak -> {
                if (!settings.breakCountdown) {
                    Triple(
                        text(shortName(state.next.name)),
                        text(clockOf(state.next.startMinute)),
                        "下一节 ${state.next.name} ${clockOf(state.next.startMinute)}",
                    )
                } else {
                    Triple(
                        if (isPreview) text("英语 10分") else countdownWithLabel(
                            nowMillis + state.remainToStartMillis,
                            "${shortName(state.next.name)} ^1",
                            unit,
                        ),
                        text(state.next.name),
                        "距 ${state.next.name} 上课",
                    )
                }
            }

            WatchState.Done -> Triple(text("没课"), text("今天"), "今天没有课了")
            WatchState.NoData -> Triple(text("待同步"), text("Nexio"), "还没收到课表，点一下同步")
        }
        return ShortTextComplicationData.Builder(main, text(description))
            .setTitle(title)
            .setTapAction(tapIntent(context))
            .build()
    }

    /**
     * 小槽位能塞下的课名：CJK 按 2 个半角宽算，总宽超过 [SHORT_NAME_WIDTH] 时截断。
     *
     * 小圆槽大约放得下 5~7 个汉字，再长就会被系统从尾部硬截 ——
     * 而倒计时通常在后面，先截课名反而保住了关键信息。
     */
    private fun shortName(name: String): String {
        if (name.isEmpty()) return name
        var width = 0
        val out = StringBuilder()
        for (ch in name) {
            val w = if (ch.code > 0x2E80) 2 else 1
            if (width + w > SHORT_NAME_WIDTH) break
            width += w
            out.append(ch)
        }
        return out.toString()
    }

    private const val SHORT_NAME_WIDTH = 8

    // 所有状态文案集中在这里。之前各渲染函数各写一套，同一张图上会出现
    // 「今天没课了」和「今天没有课」这样的同义重复。
    private const val STATE_REMAIN_END = "距下课"
    private const val STATE_REMAIN_START = "距上课"
    private const val STATE_CLASS = "上课中"
    private const val STATE_DONE = "今天没课了"
    private const val STATE_NO_DATA = "还未同步"
    private const val SYNC_HINT = "点一下手表同步"

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
                text("今天的课已经全部上完"),
                text("今天的课已全部结束"),
            ).setTitle(text("今天")).setTapAction(tapIntent(context)).build()

            WatchState.NoData -> LongTextComplicationData.Builder(
                text("在手表上点一下可手动同步"),
                text("还没收到课表，点一下可手动同步"),
            ).setTitle(text("还没同步"))
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

            // 空状态不能画满环：满环的含义是「100% 完成」，而没数据是「不知道」。
            WatchState.Done -> RangedValueComplicationData.Builder(
                value = 1f,
                min = 0f,
                max = 1f,
                contentDescription = text("今天的课已全部结束"),
            ).setTitle(text("今天"))
                .setText(text("没课了"))
                .setMonochromaticImage(bookImage(context))
                .setTapAction(tapIntent(context))
                .build()

            WatchState.NoData -> RangedValueComplicationData.Builder(
                value = 0f,
                min = 0f,
                max = 1f,
                contentDescription = text("还没收到课表，点一下同步"),
            ).setTitle(text("Nexio"))
                .setText(text("待同步"))
                .setMonochromaticImage(bookImage(context))
                .setTapAction(tapIntent(context))
                .build()
        }
    }

    // ── 纯图片槽位 ───────────────────────────────────────────────────────────

    private fun monochromeImage(context: Context, state: WatchState): ComplicationData {
        return MonochromaticImageComplicationData.Builder(
            bookImage(context),
            text(describeState(state)),
        ).setTapAction(tapIntent(context)).build()
    }

    private fun smallImage(context: Context, state: WatchState): ComplicationData {
        // 图标槽位没有文字位置，只有 status 会被读屏念出来，用同一张课程图标即可
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(
                Icon.createWithResource(context, R.drawable.ic_complication_book),
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

    /**
     * 中央大槽位的位图。
     *
     * 版式遵循表盘设计的「一眼可读」原则：槽位只有约 40mm 直径，
     * 用户抬手 1~2 秒就要读懂，所以固定成四层，且每层只放一件事：
     *
     * ```
     *   距下课          ← 小字，说明下面那个数字是什么（语境）
     *    25            ← 最大字号，倒计时数字，视觉焦点
     *   分钟
     * 大学英语         ← 中等，课名
     * 博A201          ← 小字，次要信息
     * ```
     *
     * 之前把状态词（「正在上课」）和课名、倒计时分三行平铺，字号还都差不多，
     * 结果没有主次；「今天没课了」和「今天没有课」更是同一句话写了两遍。
     * 这里状态词只在没有倒计时可显示时才出现，不再与内容重复。
     */
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
        val centerX = size / 2f

        // 半透明圆角底板：叠在任意表盘背景上都能读，又不会糊死槽位
        canvas.drawColor(Color.TRANSPARENT)
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        val inset = size * 0.05f
        canvas.drawRoundRect(inset, inset, size - inset, size - inset, 56f, 56f, scrimPaint)

        val white = 0xFFFFFFFF.toInt()
        val dim = 0xCCFFFFFF.toInt()

        fun drawCentered(text: String, y: Float, px: Float, color: Int, bold: Boolean) {
            paint.color = color
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = px
            paint.isFakeBoldText = bold
            canvas.drawText(text, centerX, y, paint)
        }

        // 有倒计时可显示（上课中 / 课间）时，用「标签 + 大数字 + 单位」的焦点式版式
        val countdown = when (state) {
            is WatchState.InClass -> Triple(
                STATE_REMAIN_END,
                state.remainToEndMillis,
                state.course,
            )
            is WatchState.OnBreak ->
                if (settings.breakCountdown) {
                    Triple(STATE_REMAIN_START, state.remainToStartMillis, state.next)
                } else null
            else -> null
        }

        if (countdown != null) {
            val (label, remainMillis, course) = countdown
            val minutes = ((remainMillis.coerceAtLeast(0) + 59_999) / 60_000).toInt()

            drawCentered(label, 96f, 30f, dim, false)

            // 倒计时是主角：数字放到最大，单位单独一行压在小字里
            val numberText = if (minutes >= 60) (minutes / 60).toString() else minutes.toString()
            val unitText = if (minutes >= 60) "小时" else "分钟"
            drawCentered(numberText, 210f, 116f, white, true)
            drawCentered(unitText, 252f, 28f, dim, false)

            drawCentered(shortName(course.name), 320f, 36f, white, true)
            val sub = subtitleOf(course, settings)
            if (sub.isNotEmpty()) {
                drawCentered(sub, 356f, 26f, dim, false)
            } else {
                // 没有地点/教师时，把「距上课」的剩余小时数补在末行，避免大片空白
                if (minutes >= 60) {
                    drawCentered("${minutes % 60}分", 356f, 26f, dim, false)
                }
            }
            return bitmap
        }

        // 没有倒计时：今天是空状态或已下课，用居中两行，绝不重复同一句话
        val (headline, detail) = when (state) {
            WatchState.Done -> STATE_DONE to ""
            WatchState.NoData -> STATE_NO_DATA to SYNC_HINT
            // 课间但关掉了倒计时：直接给下一节的上课时刻
            is WatchState.OnBreak -> clockOf(state.next.startMinute) to shortName(state.next.name)
            is WatchState.InClass -> STATE_CLASS to shortName(state.course.name)
        }
        if (detail.isEmpty()) {
            drawCentered(headline, size / 2f + 18f, 52f, white, true)
        } else {
            drawCentered(headline, size / 2f - 14f, 64f, white, true)
            drawCentered(detail, size / 2f + 44f, 30f, dim, false)
        }
        return bitmap
    }

    private fun subtitleOf(course: WatchCourse, settings: WearSettings): String =
        listOfNotNull(
            course.classroom.takeIf { settings.showClassroom && it.isNotEmpty() },
            course.teacher.takeIf { settings.showTeacher && it.isNotEmpty() },
        ).joinToString(" · ")

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
        // Builder 的第三个参数是 contentDescription（读屏用），不是可见正文。
        // 之前把它当成了正文、又从未调 setText，结果表盘上只有进度环和课名，
        // 中间那行时间一直是空的 —— 这就是「同步了也不显示信息」的一种。
        val parts = when (state) {
            is WatchState.InClass -> {
                val totalMin = state.course.durationMinutes.toFloat().coerceAtLeast(1f)
                val elapsedMin = (totalMin - state.remainToEndMillis / 60_000f).coerceIn(0f, totalMin)
                ProgressParts(
                    value = elapsedMin,
                    target = totalMin,
                    title = state.course.name,
                    body = if (isPreview) text("距下课 25分") else countdownWithLabel(
                        nowMillis + state.remainToEndMillis, "距下课 ^1", unit,
                    ),
                    desc = "${STATE_CLASS} ${state.course.name}，距下课",
                )
            }

            is WatchState.OnBreak -> {
                val totalMin = (state.next.startMinute - state.previous.endMinute)
                    .toFloat().coerceAtLeast(1f)
                val remainMin = (state.remainToStartMillis / 60_000f).coerceIn(0f, totalMin)
                ProgressParts(
                    value = totalMin - remainMin,
                    target = totalMin,
                    title = "课间",
                    body = if (!settings.breakCountdown) text("${clockOf(state.next.startMinute)} 上课")
                    else if (isPreview) text("距上课 10分")
                    else countdownWithLabel(
                        nowMillis + state.remainToStartMillis, "距上课 ^1", unit,
                    ),
                    desc = "课间，下一节 ${state.next.name}",
                )
            }

            WatchState.Done -> ProgressParts(
                value = 1f, target = 1f, title = "今天", body = text(STATE_DONE), desc = STATE_DONE,
            )

            WatchState.NoData -> ProgressParts(
                value = 0f, target = 1f, title = "Nexio", body = text("待同步"),
                desc = "$STATE_NO_DATA，$SYNC_HINT",
            )
        }

        return GoalProgressComplicationData.Builder(
            parts.value.coerceIn(0f, parts.target),
            parts.target,
            text(parts.desc),
        )
            .setTitle(text(parts.title))
            .setText(parts.body)
            .setMonochromaticImage(bookImage(context))
            .setTapAction(tapIntent(context))
            .build()
    }

    private data class ProgressParts(
        val value: Float,
        val target: Float,
        val title: String,
        /** 表盘中央那行可见文字 */
        val body: ComplicationText,
        /** 读屏念的整句，不显示 */
        val desc: String,
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
        // 分段条只表达一件事：这一节课（或这个课间）已经过去多少。
        // 之前固定三等分，与真实进度无关，纯粹是装饰性的假数据。
        val elements = when (state) {
            is WatchState.InClass -> {
                val done = progressOf(state) ?: 0f
                listOf(
                    WeightedElementsComplicationData.Element(done.coerceAtLeast(0.001f), colors[2]),
                    WeightedElementsComplicationData.Element((1f - done).coerceAtLeast(0.001f), colors[0]),
                )
            }

            is WatchState.OnBreak -> {
                val done = progressOf(state) ?: 0f
                listOf(
                    WeightedElementsComplicationData.Element(done.coerceAtLeast(0.001f), colors[2]),
                    WeightedElementsComplicationData.Element((1f - done).coerceAtLeast(0.001f), colors[1]),
                )
            }

            // 没课 / 没数据：整条留白，不用假色块填满
            WatchState.Done -> listOf(
                WeightedElementsComplicationData.Element(1f, colors[2]),
            )

            WatchState.NoData -> listOf(
                WeightedElementsComplicationData.Element(1f, colors[3]),
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
            Icon.createWithResource(context, R.drawable.ic_complication_book),
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

    /** 读屏软件念的整句；与可见文案共用同一套状态词，避免两种说法 */
    private fun describeState(state: WatchState): String = when (state) {
        is WatchState.InClass -> "$STATE_CLASS ${state.course.name}"
        is WatchState.OnBreak -> "下一节 ${state.next.name}"
        WatchState.Done -> STATE_DONE
        WatchState.NoData -> "$STATE_NO_DATA，$SYNC_HINT"
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
