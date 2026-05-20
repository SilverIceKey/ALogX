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

    private val dayFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }
        }
    }

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }
        }
    }

    fun today(): String = dayFmt.get()!!.format(Date())

    fun now(): String = timeFmt.get()!!.format(Date())
}
