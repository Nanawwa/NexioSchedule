package com.haooz.chedule.wear

import android.content.Context

/**
 * 手表端本地设置。
 *
 * 只影响「表盘上显示什么」，不改手机端的课表数据 —— 手表是可穿戴端的展示层，
 * 想改课程内容仍然回手机。这样两边职责清楚，也不会出现手表改了手机不知道的情况。
 */
class WearSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 长文本里是否带授课教师 */
    var showTeacher: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TEACHER, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TEACHER, value).apply()

    /** 长文本里是否带教室 */
    var showClassroom: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLASSROOM, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CLASSROOM, value).apply()

    /**
     * 课间是否显示「距下一节上课」倒计时。
     *
     * 关掉后课间只显示下一节课名与上课时刻，适合课间很短、倒计时跳来跳去反而碍眼的情况。
     */
    var breakCountdown: Boolean
        get() = prefs.getBoolean(KEY_BREAK_COUNTDOWN, true)
        set(value) = prefs.edit().putBoolean(KEY_BREAK_COUNTDOWN, value).apply()

    /** 短文本用整点倒计时（"25分"）还是带秒（"25:13"） */
    var preciseCountdown: Boolean
        get() = prefs.getBoolean(KEY_PRECISE_COUNTDOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_PRECISE_COUNTDOWN, value).apply()

    private companion object {
        const val PREFS_NAME = "wear_settings"
        const val KEY_SHOW_TEACHER = "show_teacher"
        const val KEY_SHOW_CLASSROOM = "show_classroom"
        const val KEY_BREAK_COUNTDOWN = "break_countdown"
        const val KEY_PRECISE_COUNTDOWN = "precise_countdown"
    }
}
