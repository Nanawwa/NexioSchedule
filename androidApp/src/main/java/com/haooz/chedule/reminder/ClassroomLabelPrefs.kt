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
 * 息屏（AOD）标题：`主体 | xx:xx下课`。
 *
 * 宽度预算与裁剪规则在 :core 的 [ClassroomLabel.aodTitle]，两端共用一份实现，
 * 这里只负责把用户设置（地点精简档位）喂进去。
 *
 * @param endTime "HH:mm"；为空表示这节课没有可用时刻，此时只输出主体
 */
internal fun SharedPreferences.aodTitle(
    courseName: String,
    shortClassroom: String,
    endTime: String,
): String {
    val label = if (endTime.isBlank()) "" else "${endTime}下课"
    return ClassroomLabel.aodTitle(
        main = courseName,
        fallback = shortClassroom,
        timeLabel = label,
        slotWidth = classroomLabelRule().slotWidth,
    )
}

/**
 * 课前岛用的息屏标题：时刻是「上课」，不是「下课」。
 */
internal fun SharedPreferences.aodTitleForStart(
    courseName: String,
    shortClassroom: String,
    startTime: String,
): String {
    val label = if (startTime.isBlank()) "" else "${startTime}上课"
    return ClassroomLabel.aodTitle(
        main = courseName,
        fallback = shortClassroom,
        timeLabel = label,
        slotWidth = classroomLabelRule().slotWidth,
    )
}
