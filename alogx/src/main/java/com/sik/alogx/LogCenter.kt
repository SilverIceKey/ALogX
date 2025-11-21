package com.sik.alogx

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


/**
 * ALogX 核心类。
 *
 * 职责：
 * 1. 日志写入主文件 main.log
 * 2. 每日滚动：main.log → yyyy-MM-dd/app.log
 * 3. 自动清理超过 maxKeepDays 的历史目录
 * 4. 捕获 logcat（可选，写入 yyyy-MM-dd/logcat.log）
 * 5. 打包某一天日志为 zip 文件
 *
 * 真正的“落盘 + 打 logcat”都在这里做。
 */
object LogCenter {

    /** 全局配置实例 */
    private lateinit var config: LogConfig

    /** 日志存放根路径：/sdcard/{appName}/logs */
    private lateinit var baseDir: File

    /** 当前 "主日志文件" 属于哪一天，用于日切判断（yyyy-MM-dd） */
    @Volatile
    private var currentDay = ""

    /** 主日志 writer（用于写 main.log 或当天 app.log） */
    @Volatile
    private var mainWriter: BufferedWriter? = null

    /** logcat 捕获 writer（写 yyyy-MM-dd/logcat.log） */
    @Volatile
    private var logcatWriter: BufferedWriter? = null

    /** logcat 捕获后台线程 */
    @Volatile
    private var logcatThread: Thread? = null

    /** 当前应用包名，用于过滤 logcat */
    private lateinit var packageName: String

    /**
     * 大块数据（blob）信息：
     * - relativePath：相对于 baseDir 的路径，写进日志用
     * - size：字节大小
     * - hash：md5 摘要，方便你排查/去重
     */
    data class BlobInfo(
        val relativePath: String,
        val size: Int,
        val hash: String
    )

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * 初始化 ALogX。建议在 Application.onCreate 调用。
     */
    fun init(context: Context, cfg: LogConfig) {
        config = cfg
        packageName = context.packageName

        // /sdcard/app_name/logs
        baseDir = File(
            Environment.getExternalStorageDirectory(),
            "${cfg.appName}/logs"
        )
        if (!baseDir.exists()) baseDir.mkdirs()

        // 启动时只做一次“状态恢复 / 当天检测”
        rolloverIfNeeded(false)

        // 开启 logcat 采集（系统 logcat → 本地 logcat.log）
        if (cfg.enableLogcat) {
            startLogcatCollector()
        }
    }


    // ============================================================
    // 日志写入（文件 + logcat）
    // ============================================================

    /**
     * 写一条日志到 main.log，并同步输出到 Android logcat。
     *
     * @param level 日志等级字符：V/D/I/W/E/F
     * @param tag   日志 TAG
     * @param msg   日志内容
     */
    @Synchronized
    fun log(level: Char, tag: String, msg: String) {
        rolloverIfNeeded() // 检查是否需要日切/恢复 writer
        val writer = mainWriter ?: return

        // 统一日志格式（写文件用这条）
        val line = "${Utils.now()} | $level/$tag | ${Thread.currentThread().name} | $msg"

        // 1. 写文件
        writer.write(line)
        writer.newLine()
        writer.flush()

        // 2. 顺便打到 Android logcat（调试方便）
        when (level) {
            'V' -> Log.v(tag, line)
            'D' -> Log.d(tag, line)
            'I' -> Log.i(tag, line)
            'W' -> Log.w(tag, line)
            'E' -> Log.e(tag, line)
            'F' -> Log.wtf(tag, line)
            else -> Log.d(tag, line)
        }
    }

    /**
     * 将一段二进制数据保存到“当天目录/blobs/”下，并返回引用信息。
     *
     * 目录结构示例：
     *   /sdcard/ALogX/logs/2025-11-14/blobs/ab23cd9f.png
     *
     * @param bytes  要保存的二进制数据（已经是解码后的）
     * @param suffix 文件后缀，例如 ".png"、".jpg"、".bin"
     *
     * @return BlobInfo（包含相对路径、大小、hash）
     */
    @Synchronized
    fun saveBlob(bytes: ByteArray, suffix: String = ".bin"): BlobInfo? {
        // 确保当前 day 状态正确
        rolloverIfNeeded()

        // 如果还没初始化 baseDir，直接放弃
        if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

        // 当天目录：/logs/yyyy-MM-dd
        val dayDir = File(baseDir, currentDay)
        if (!dayDir.exists()) dayDir.mkdirs()

        // blobs 子目录：/logs/yyyy-MM-dd/blobs
        val blobDir = File(dayDir, "blobs")
        if (!blobDir.exists() && !blobDir.mkdirs()) {
            return null
        }

        // md5 做文件名，避免重复 + 方便排查
        val hash = md5(bytes)
        val file = File(blobDir, "$hash$suffix")

        try {
            // 这里按你原来的逻辑，用 bytes.toHex()，假设你自己有扩展
            file.writeText(bytes.toHex(), Charsets.UTF_8)
        } catch (e: IOException) {
            Log.e("ALogX", "saveBlob error: ${e.message}", e)
            return null
        }

        val relPath = file.relativeTo(baseDir).invariantSeparatorsPath
        return BlobInfo(
            relativePath = relPath,
            size = bytes.size,
            hash = hash
        )
    }

    /**
     * 将一段字符串作为 blob 保存到“当天目录/blobs/”下，并返回引用信息。
     */
    @Synchronized
    fun saveBlobString(content: String, suffix: String = ".txt"): BlobInfo? {
        rolloverIfNeeded()

        if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

        val dayDir = File(baseDir, currentDay)
        if (!dayDir.exists()) dayDir.mkdirs()

        val blobDir = File(dayDir, "blobs")
        if (!blobDir.exists() && !blobDir.mkdirs()) {
            return null
        }

        // 用内容算 md5，当成文件名，方便去重和排查
        val bytes = content.toByteArray(Charsets.UTF_8)
        val hash = md5(bytes)
        val file = File(blobDir, "$hash$suffix")

        return try {
            file.writeText(content, Charsets.UTF_8)
            val relPath = file.relativeTo(baseDir).invariantSeparatorsPath
            BlobInfo(
                relativePath = relPath,
                size = bytes.size,
                hash = hash
            )
        } catch (e: IOException) {
            Log.e("ALogX", "saveBlobString error: ${e.message}", e)
            null
        }
    }

    /**
     * 计算 md5 摘要，用于 blob 文件名。
     */
    private fun md5(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val dig = md.digest(bytes)
        return dig.joinToString("") { "%02x".format(it) }
    }

    /**
     * 把文件的 lastModified 转成 yyyy-MM-dd，和 Utils.today() 同格式。
     */
    private fun dayOf(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", y, m, d)
    }

    // ============================================================
    // 日切逻辑（核心）
    // ============================================================

    /**
     * 判断是否跨天，然后执行滚动：
     * - main.log → yyyy-MM-dd/app.log
     * - 创建新 main.log
     * - 清理过期天数
     *
     * 额外行为：
     * - 同一天内多次 init / 多次调用，只会以 append 模式接着写，不会清空 main.log/logcat.log。
     */
    @Synchronized
    private fun rolloverIfNeeded(force: Boolean = false) {
        if (!::baseDir.isInitialized) return

        val today = Utils.today()
        val mainFile = File(baseDir, "main.log")

        // 1. 进程重启情况下，currentDay 可能是 ""，尝试从 main.log 推断日期
        if (currentDay.isEmpty() && mainFile.exists()) {
            currentDay = dayOf(mainFile.lastModified())
        }

        // 2. 如果不是强制，并且已经是今天 -> 不需要日切，只要确保 writer 打开且是 append
        if (!force && today == currentDay) {
            // main.log 追加模式
            if (mainWriter == null) {
                mainWriter = FileOutputStream(mainFile, /* append = */ true)
                    .bufferedWriter(Charsets.UTF_8)
            }

            // logcat.log 追加模式
            if (config.enableLogcat && logcatWriter == null) {
                val dayDir = File(baseDir, today)
                if (!dayDir.exists()) dayDir.mkdirs()
                val logcatFile = File(dayDir, "logcat.log")
                logcatWriter = FileOutputStream(logcatFile, true)
                    .bufferedWriter(Charsets.UTF_8)
            }
            return
        }

        // 3. 下面是真正要“切日”的分支（force = true 或 today != currentDay）

        // 先关旧 writer
        mainWriter?.close()
        mainWriter = null
        logcatWriter?.close()
        logcatWriter = null

        // 有已知的 currentDay，且 main.log 存在 -> 归档成那一天的 app.log
        if (currentDay.isNotEmpty() && mainFile.exists()) {
            val dayDir = File(baseDir, currentDay)
            if (!dayDir.exists()) dayDir.mkdirs()
            mainFile.renameTo(File(dayDir, "app.log"))
        }

        // 切换到今天
        currentDay = today

        // 新的一天：main.log 从空文件开始写（覆盖模式）
        mainWriter = FileOutputStream(mainFile, /* append = */ false)
            .bufferedWriter(Charsets.UTF_8)

        if (config.enableLogcat) {
            val dayDir = File(baseDir, currentDay)
            if (!dayDir.exists()) dayDir.mkdirs()
            val logcatFile = File(dayDir, "logcat.log")
            logcatWriter = FileOutputStream(logcatFile, false)
                .bufferedWriter(Charsets.UTF_8)
        }

        // 清理历史日志
        cleanupOldDays()
    }


    // ============================================================
    // 清理超期天数
    // ============================================================

    /**
     * 删除超过 maxKeepDays 的日志目录。
     * 目录名格式必须是 yyyy-MM-dd 才会被识别。
     */
    private fun cleanupOldDays() {
        val dirs = baseDir.listFiles { f ->
            f.isDirectory && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
        }?.sortedBy { it.name } ?: return

        val overflow = dirs.size - config.maxKeepDays
        if (overflow > 0) {
            dirs.take(overflow).forEach { it.deleteRecursively() }
        }
    }


    // ============================================================
    // logcat 捕获
    // ============================================================

    /**
     * 开启 logcat 捕获线程（可过滤包名）。
     * 把系统日志写入 yyyy-MM-dd/logcat.log。
     */
    private fun startLogcatCollector() {
        if (logcatThread != null) return

        logcatThread = Thread({
            try {
                // 清空旧 logcat 缓存（如果你想保留系统之前的 log，可以把这行删掉）
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

                // 启动 logcat 流
                val proc = Runtime.getRuntime().exec(config.logcatCmd)
                val reader = proc.inputStream.bufferedReader()

                var line: String? = null

                while (!Thread.currentThread().isInterrupted &&
                    reader.readLine().also { line = it } != null
                ) {

                    val t = line ?: break

                    // 是否只记录与本包相关的 logcat 行
                    if (config.onlyPackageLogcat && !t.contains(packageName)) continue

                    synchronized(this) {
                        rolloverIfNeeded()
                        logcatWriter?.apply {
                            write(t)
                            newLine()
                            flush()
                        }
                    }
                }

            } catch (e: Exception) {
                // 注意：这里不能再调 ALog / LogCenter.log，不然递归玩死你
                Log.e("ALogX", "Logcat collector error: ${e.message}", e)
            }

        }, "ALogX-Logcat").apply {
            isDaemon = true
            start()
        }
    }


    // ============================================================
    // 打包某一日的日志
    // ============================================================

    /**
     * 压缩某一天的日志文件目录为 zip。
     * 目录结构不改变。
     *
     * 额外逻辑：
     * 如果 day == currentDay，且 main.log 还在写这一天的日志，
     * 则在 zip 里生成一个“合并版”的 app.log：
     *   [ dayDir/app.log(如果存在) ] + [ 当前 main.log ]
     * 不会修改磁盘上的任何日志文件，避免多次 zipDay 造成重复追加。
     *
     * @param day 格式 "yyyy-MM-dd"
     * @return 生成的 zip 文件，若当天不存在返回 null
     */
    @Synchronized
    fun zipDay(day: String): File? {
        // 确保初始化过
        if (!::baseDir.isInitialized) return null

        val dayDir = File(baseDir, day)
        if (!dayDir.exists()) return null

        val out = File(baseDir, "logs_$day.zip")
        if (out.exists()) out.delete()

        // 如果 zip 的就是当前这一天，先 flush 一下 mainWriter，拿一个快照文件引用
        val mainLogForDay: File? = if (day == currentDay) {
            // 保证缓冲区刷到文件
            mainWriter?.flush()
            val mainFile = File(baseDir, "main.log")
            if (mainFile.exists()) mainFile else null
        } else {
            null
        }

        ZipOutputStream(FileOutputStream(out)).use { zip ->

            // 先把 dayDir 下所有文件打进去（但如果是当前天，先跳过已有的 app.log，后面用“合并版”替换）
            dayDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    // 如果是当前天，并且是 app.log，就先跳过，后面单独写合并内容
                    if (day == currentDay && file.name == "app.log") {
                        return@forEach
                    }

                    val rel = file.relativeTo(baseDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

            // 如果是当前天，需要在 zip 里生成一个合并版 app.log：
            // [ dayDir/app.log(如果有) ] + [ main.log ]
            if (day == currentDay) {
                val appLogFile = File(dayDir, "app.log")
                val zipEntryPath = appLogFile
                    .relativeTo(baseDir)
                    .invariantSeparatorsPath

                zip.putNextEntry(ZipEntry(zipEntryPath))

                // 1. 先写老的 app.log（如果存在）
                if (appLogFile.exists()) {
                    appLogFile.inputStream().use { it.copyTo(zip) }
                }

                // 2. 再写当前 main.log 的内容（最新完整快照）
                mainLogForDay?.inputStream()?.use { it.copyTo(zip) }

                zip.closeEntry()
            }
        }

        return out
    }


    // ============================================================
    // 停止服务（可选）
    // ============================================================

    /**
     * 强行关闭日志系统（一般不用）
     */
    fun shutdown() {
        mainWriter?.close()
        logcatWriter?.close()
        logcatThread?.interrupt()
    }
}
