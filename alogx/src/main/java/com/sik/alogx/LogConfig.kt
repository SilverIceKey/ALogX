package com.sik.alogx

/**
 * ALogX 的全局配置。
 *
 * @property appName       应用名，用于生成日志根目录：/sdcard/{appName}/logs/
 * @property maxKeepDays   最多保留多少天的历史日志目录（自动清理）
 * @property enableLogcat  是否开启 logcat 捕获（需要 READ_LOGS 权限）
 * @property logcatCmd     logcat 命令，可以自定义过滤参数
 * @property onlyPackageLogcat  是否仅捕获包含当前包名的 logcat 行，降低噪音
 */
data class LogConfig(
    val appName: String = "ALogX",
    val maxKeepDays: Int = 7,
    val enableLogcat: Boolean = true,
    val logcatCmd: List<String> = listOf("logcat", "-v", "time"),
    val onlyPackageLogcat: Boolean = true
)
