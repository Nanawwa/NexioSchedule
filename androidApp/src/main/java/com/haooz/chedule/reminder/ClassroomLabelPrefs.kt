package com.haooz.chedule.reminder

import android.content.SharedPreferences
import com.haooz.chedule.data.ClassroomLabel
import com.haooz.chedule.data.ClassroomLabelRule
import com.haooz.chedule.data.asKeyValueStore

/**
 * 缩略态（超级岛槽位 / 灵动岛胶囊 / 实况通知右侧 / 息屏标题）地点精简规则的读取口。
 *
 * 规则本身（怎么精简、可用宽度怎么算）在 :core 的 [ClassroomLabel] 里，两端共用。
 * 这里把 SharedPreferences 交给 [ClassroomLabel.read]，不逐个字段手抄：手抄一份就多一处
 * 要同步的地方，漏掉某个键的表现是设置里改了、通知里没反应，编译期看不出来。
 *
 * 发通知的是 Receiver / Service，直读 SharedPreferences 比走 AppStorage 少一层
 * 「Application 是否已 init」的假设。
 */
internal fun SharedPreferences.classroomLabelRule(): ClassroomLabelRule =
    ClassroomLabel.read(asKeyValueStore())

/** 缩略态用的地点文案。规则为「原样显示」且没开保留尾部时，与课表原文逐字相同。 */
internal fun SharedPreferences.shortClassroom(classroom: String): String =
    ClassroomLabel.apply(classroom, classroomLabelRule())

/**
 * 息屏（AOD）标题：左右两区按用户选择拼成 `左 | 右`。
 *
 * 宽度分配与截断规则在 :core 的 [ClassroomLabel.aodSlotText]，两端共用。
 *
 * 区内容约定（与设置页下拉一一对应）：
 * - 0 课程名称
 * - 1 上课地点（已按地点精简规则处理）
 * - 2 时刻，形如 "15:35下课" / "08:30上课"
 *
 * @param timeLabel 该岛对应的时刻文案；空串表示这节课没有可用时间
 */
internal fun SharedPreferences.aodTitle(
    courseName: String,
    shortClassroom: String,
    timeLabel: String,
    leftMode: Int,
    rightMode: Int,
): String {
    fun contentOf(mode: Int): String = when (mode) {
        1 -> shortClassroom
        2 -> timeLabel
        else -> courseName
    }
    return ClassroomLabel.aodSlotText(
        left = contentOf(leftMode),
        right = contentOf(rightMode),
        slotWidth = classroomLabelRule().slotWidth,
    )
}

/* 便捷重载：三个岛各自知道自己的时刻语义 */

/** 课前岛：时刻是「即将上课」，不是下课 */
internal fun SharedPreferences.aodTitleForStart(
    courseName: String,
    shortClassroom: String,
    startTime: String,
): String = aodTitle(
    courseName = courseName,
    shortClassroom = shortClassroom,
    timeLabel = if (startTime.isBlank()) "" else "${startTime}上课",
    leftMode = getInt("island_aod_left_mode", getInt("island_aod_mode", 0)),
    rightMode = getInt("island_aod_right_mode", 2),
)

/** 课中岛：时刻是本节下课 */
internal fun SharedPreferences.aodTitleForEnd(
    courseName: String,
    shortClassroom: String,
    endTime: String,
): String = aodTitle(
    courseName = courseName,
    shortClassroom = shortClassroom,
    timeLabel = if (endTime.isBlank()) "" else "${endTime}下课",
    leftMode = getInt("island_aod_left_mode", getInt("island_aod_mode", 0)),
    rightMode = getInt("island_aod_right_mode", 2),
)
