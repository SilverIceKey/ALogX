package com.sik.alogx

import java.text.SimpleDateFormat
import java.util.*

/**
 * ALogX 内部日期格式工具。
 *
 * 不做扩展功能，仅提供：
 * - 获取当天日期（yyyy-MM-dd）
 * - 获取当前时间（yyyy-MM-dd HH:mm:ss.SSS）
 */
internal object Utils {

    /** 统一用这个时区，默认取系统当前时区 */
    private val zone: TimeZone
        get() = TimeZone.getDefault()

    /** 按天分目录的日期格式：2025-11-13 */
    val dayFmt: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = zone
    }

    /** 单条日志前缀时间：2025-11-13 12:30:33.123 */
    val timeFmt: SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply {
            timeZone = zone
        }

    /** 返回今日日期字符串 */
    fun today(): String {
        dayFmt.timeZone = zone   // 防止运行时用户改了时区，重新刷新
        return dayFmt.format(Date())
    }

    /** 返回当前完整时间（含毫秒） */
    fun now(): String {
        timeFmt.timeZone = zone
        return timeFmt.format(Date())
    }
}
