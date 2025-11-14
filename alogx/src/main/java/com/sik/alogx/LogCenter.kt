package com.sik.alogx

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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

        // 第一次启动强制滚动一次，确保 writer 准备完毕
        rolloverIfNeeded(true)

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
        rolloverIfNeeded() // 检查是否需要日切
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
     *
     * 场景：你已经有一个很长的字符串（比如 base64 / HEX / 压缩后的 JSON 等），
     *       不想直接打日志，只想落到文件里，然后日志里打一条引用。
     *
     * 目录结构示例：
     *   /sdcard/ALogX/logs/2025-11-14/blobs/ab23cd9f.txt
     *
     * @param content 要保存的字符串（已经是你处理好的 blob）
     * @param suffix  文件后缀，例如 ".txt"、".b64"、".hex"
     *
     * @return BlobInfo（包含相对路径、大小、hash），失败返回 null
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

    // ============================================================
    // 日切逻辑（核心）
    // ============================================================

    /**
     * 判断是否跨天，然后执行滚动：
     * - main.log → yyyy-MM-dd/app.log
     * - 创建新 main.log
     * - 清理过期天数
     */
    @Synchronized
    private fun rolloverIfNeeded(force: Boolean = false) {

        val today = Utils.today()

        // 不需要滚动
        if (!force && today == currentDay) return

        // 关闭旧 writer
        mainWriter?.close()
        mainWriter = null
        logcatWriter?.close()
        logcatWriter = null

        // 如果 currentDay 不为空，表示不是第一次启动，需要把 main.log 移到当日目录
        if (currentDay.isNotEmpty()) {
            val oldMain = File(baseDir, "main.log")
            if (oldMain.exists()) {
                val dayDir = File(baseDir, currentDay)
                if (!dayDir.exists()) dayDir.mkdirs()
                oldMain.renameTo(File(dayDir, "app.log"))
            }
        }

        // 更新到新的一天
        currentDay = today

        // 创建新的 main.log writer（写“今日主日志”）
        mainWriter = File(baseDir, "main.log").bufferedWriter(Charsets.UTF_8, 8192)

        // 同步创建 logcat 当天文件（如果启用）
        if (config.enableLogcat) {
            val dayDir = File(baseDir, currentDay)
            if (!dayDir.exists()) dayDir.mkdirs()
            logcatWriter = File(dayDir, "logcat.log").bufferedWriter(Charsets.UTF_8, 8192)
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
                // 清空旧 logcat 缓存
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
     * @param day 格式 "yyyy-MM-dd"
     * @return 生成的 zip 文件，若当天不存在返回 null
     */
    fun zipDay(day: String): File? {

        val dayDir = File(baseDir, day)
        if (!dayDir.exists()) return null

        val out = File(baseDir, "logs_$day.zip")
        if (out.exists()) out.delete()

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            dayDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(baseDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zip) }
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
