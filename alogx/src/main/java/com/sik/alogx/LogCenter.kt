package com.sik.alogx

import android.content.Context
import android.os.Environment
import android.util.Log
import okio.BufferedSink
import okio.HashingSink
import okio.Sink
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import okio.use
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * 主日志 sink（写 main.log）
     * 用 Okio BufferedSink 替代 BufferedWriter，减少 String 拼接与中间拷贝
     */
    @Volatile
    private var mainSink: BufferedSink? = null

    /**
     * logcat 捕获 sink（写 yyyy-MM-dd/logcat.log）
     */
    @Volatile
    private var logcatSink: BufferedSink? = null

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

        if (!baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                Log.e("ALogX", "init: mkdirs baseDir failed: ${baseDir.absolutePath}")
                // 目录都建不出来，后续直接 return，避免一堆 FileNotFound 崩溃
                return
            }
        }

        // 启动时只做一次“状态恢复 / 当天检测”
        rolloverIfNeeded(force = false)

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
        rolloverIfNeeded() // 检查是否需要日切/恢复 sink
        val sink = mainSink ?: return

        // 注意：这里不拼接一个巨大的 line 字符串，改为分段写入，减少临时对象/拷贝
        // 但 logcat 输出仍然使用最终 line（Android Log API 只能收 String）
        val ts = Utils.now()
        val threadName = Thread.currentThread().name

        // 1) 写文件（逻辑不变：每条一行，写完 flush 保证稳定落盘）
        try {
            sink.writeUtf8(ts)
            sink.writeUtf8(" | ")
            sink.writeUtf8(level.toString())
            sink.writeUtf8("/")
            sink.writeUtf8(tag)
            sink.writeUtf8(" | ")
            sink.writeUtf8(threadName)
            sink.writeUtf8(" | ")
            sink.writeUtf8(msg)
            sink.writeByte('\n'.code)
            sink.flush()
        } catch (e: IOException) {
            // 写盘失败别递归打 ALog
            Log.e("ALogX", "log write error: ${e.message}", e)
        }

        // 2) 顺便打到 Android logcat（保持你原来的格式）
        val lineForLogcat = "$ts | $level/$tag | $threadName | $msg"
        when (level) {
            'V' -> Log.v(tag, lineForLogcat)
            'D' -> Log.d(tag, lineForLogcat)
            'I' -> Log.i(tag, lineForLogcat)
            'W' -> Log.w(tag, lineForLogcat)
            'E' -> Log.e(tag, lineForLogcat)
            'F' -> Log.wtf(tag, lineForLogcat)
            else -> Log.d(tag, lineForLogcat)
        }
    }

    // ============================================================
    // Blob 落盘
    // ============================================================

    /**
     * 将一段二进制数据保存到“当天目录/blobs/”下，并返回引用信息。
     *
     * 目录结构示例：
     *   /sdcard/ALogX/logs/2025-11-14/blobs/ab23cd9f.png
     */
    @Synchronized
    fun saveBlob(bytes: ByteArray, suffix: String = ".bin"): BlobInfo? {
        rolloverIfNeeded()

        if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            Log.e("ALogX", "saveBlob: mkdirs baseDir failed: ${baseDir.absolutePath}")
            return null
        }

        // 当天目录：/logs/yyyy-MM-dd
        val dayDir = File(baseDir, currentDay)
        if (!dayDir.exists() && !dayDir.mkdirs()) {
            Log.e("ALogX", "saveBlob: mkdirs dayDir failed: ${dayDir.absolutePath}")
            return null
        }

        // blobs 子目录：/logs/yyyy-MM-dd/blobs
        val blobDir = File(dayDir, "blobs")
        if (!blobDir.exists() && !blobDir.mkdirs()) {
            Log.e("ALogX", "saveBlob: mkdirs blobDir failed: ${blobDir.absolutePath}")
            return null
        }

        // md5 做文件名，避免重复 + 方便排查
        val hash = md5(bytes)
        val file = File(blobDir, "$hash$suffix")

        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                Log.e("ALogX", "saveBlob: mkdirs parent failed: ${parent.absolutePath}")
                return null
            }
        }

        return try {
            // okio 写入，避免额外缓冲层对象
            file.sink(append = false).buffer().use { sink ->
                sink.write(bytes)
                sink.flush()
            }
            val relPath = file.relativeTo(baseDir).invariantSeparatorsPath
            BlobInfo(
                relativePath = relPath,
                size = bytes.size,
                hash = hash
            )
        } catch (e: IOException) {
            Log.e("ALogX", "saveBlob error: ${e.message}", e)
            null
        }
    }

    /**
     * 将一段字符串作为 blob 保存到“当天目录/blobs/”下，并返回引用信息。
     *
     * 关键点：不再 content.toByteArray() 先整块转 bytes（那一下很吃内存）
     * 改为：边写 UTF-8 边做 MD5，写完后按 hash 重命名。
     */
    @Synchronized
    fun saveBlobString(content: String, suffix: String = ".txt"): BlobInfo? {
        rolloverIfNeeded()

        if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            Log.e("ALogX", "saveBlobString: mkdirs baseDir failed: ${baseDir.absolutePath}")
            return null
        }

        val dayDir = File(baseDir, currentDay)
        if (!dayDir.exists() && !dayDir.mkdirs()) {
            Log.e("ALogX", "saveBlobString: mkdirs dayDir failed: ${dayDir.absolutePath}")
            return null
        }

        val blobDir = File(dayDir, "blobs")
        if (!blobDir.exists() && !blobDir.mkdirs()) {
            Log.e("ALogX", "saveBlobString: mkdirs blobDir failed: ${blobDir.absolutePath}")
            return null
        }

        // 先写到 tmp，再按 md5 改名（和 openTextBlobStream 的逻辑一致）
        val tmp = File(blobDir, "tmp_${System.currentTimeMillis()}$suffix")
        val raw: Sink = tmp.sink(append = false)
        val hashing = HashingSink.md5(raw)
        val buffered = hashing.buffer()

        return try {
            buffered.writeUtf8(content)
            buffered.flush()
            buffered.close() // 这里会同时 close hashing + raw

            val hash = hashing.hash.hex()
            val dst = File(blobDir, "$hash$suffix")
            if (dst.exists()) {
                // 已存在则删 tmp（去重）
                tmp.delete()
            } else {
                tmp.renameTo(dst)
            }

            val relPath = dst.relativeTo(baseDir).invariantSeparatorsPath

            // 精确 size：直接拿最终文件长度，避免估算 UTF-8 byte 数导致偏差
            BlobInfo(
                relativePath = relPath,
                size = dst.length().toInt(),
                hash = hash
            )
        } catch (e: IOException) {
            try {
                buffered.close()
            } catch (_: Throwable) {
            }
            tmp.delete()
            Log.e("ALogX", "saveBlobString error: ${e.message}", e)
            null
        }
    }

    /**
     * 打开一个“文本 blob 输出流”（UTF-8 bytes），调用者边写边落盘。
     * 写完必须调用 commit(writtenBytes) 来按 md5 重命名并返回 BlobInfo。
     *
     * 注意：这里不做任何编码转换，调用者写什么 bytes 就保存什么 bytes。
     *
     * 实现改为 Okio HashingSink：边写边算 md5，无需把内容读回内存。
     */
    @Synchronized
    fun openTextBlobStream(suffix: String = ".txt"): OpenBlobResult? {
        rolloverIfNeeded()
        if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

        val dayDir = File(baseDir, currentDay).apply { if (!exists()) mkdirs() }
        val blobDir = File(dayDir, "blobs").apply { if (!exists()) mkdirs() }

        val tmp = File(blobDir, "tmp_${System.currentTimeMillis()}$suffix")

        val rawSink = tmp.sink(append = false)
        val hashingSink = HashingSink.md5(rawSink)
        val buffered = hashingSink.buffer()

        // 提供 OutputStream 给外部写（保持你原有的对外类型不变）
        val os: OutputStream = buffered.outputStream()

        val committed = AtomicBoolean(false)

        val commit = { writtenBytes: Long ->
            if (committed.compareAndSet(false, true)) {
                try {
                    // 关闭输出（会 flush 并关闭底层 sink）
                    try {
                        os.flush()
                    } catch (_: Throwable) {
                    }
                    try {
                        os.close()
                    } catch (_: Throwable) {
                    }

                    val hash = hashingSink.hash.hex()
                    val dst = File(blobDir, "$hash$suffix")
                    if (dst.exists()) {
                        tmp.delete()
                    } else {
                        tmp.renameTo(dst)
                    }
                    val rel = dst.relativeTo(baseDir).invariantSeparatorsPath
                    BlobInfo(relativePath = rel, size = writtenBytes.toInt(), hash = hash)
                } catch (e: Throwable) {
                    tmp.delete()
                    Log.e("ALogX", "openTextBlobStream commit error: ${e.message}", e)
                    null
                }
            } else {
                // 重复 commit 就返回 null（避免多次 rename/close 出幺蛾子）
                null
            }
        }

        return OpenBlobResult(output = os, commit = commit)
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
     * - 同一天内多次 init / 多次调用，只会以 append 模式接着写，不会otni清空 main.log/logcat.log。
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

        // 2. 如果不是强制，并且已经是今天 -> 不需要日切，只要确保 sink 打开且是 append
        if (!force && today == currentDay) {
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            // main.log 追加模式
            if (mainSink == null) {
                mainFile.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                mainSink = mainFile.sink(append = true).buffer()
            }

            // logcat.log 追加模式
            if (config.enableLogcat && logcatSink == null) {
                val dayDir = File(baseDir, today)
                if (!dayDir.exists()) dayDir.mkdirs()
                val logcatFile = File(dayDir, "logcat.log")

                logcatFile.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                logcatSink = logcatFile.sink(append = true).buffer()
            }
            return
        }

        // 3. 下面是真正要“切日”的分支（force = true 或 today != currentDay）

        // 先关旧 sink
        try {
            mainSink?.flush()
            mainSink?.close()
        } catch (_: Throwable) {
        }
        mainSink = null

        try {
            logcatSink?.flush()
            logcatSink?.close()
        } catch (_: Throwable) {
        }
        logcatSink = null

        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        // 有已知的 currentDay，且 main.log 存在 -> 归档成那一天的 app.log
        if (currentDay.isNotEmpty() && mainFile.exists()) {
            val dayDir = File(baseDir, currentDay)
            if (!dayDir.exists()) dayDir.mkdirs()
            val appLogFile = File(dayDir, "app.log")

            appLogFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            mainFile.renameTo(appLogFile)
        }

        // 切换到今天
        currentDay = today

        // 新的一天：main.log 从空文件开始写（覆盖模式）
        mainFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        mainSink = mainFile.sink(append = false).buffer()

        if (config.enableLogcat) {
            val dayDir = File(baseDir, currentDay)
            if (!dayDir.exists()) dayDir.mkdirs()
            val logcatFile = File(dayDir, "logcat.log")

            logcatFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            logcatSink = logcatFile.sink(append = false).buffer()
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
        if (!::baseDir.isInitialized || !baseDir.exists()) return

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
                        try {
                            logcatSink?.apply {
                                writeUtf8(t)
                                writeByte('\n'.code)
                                flush()
                            }
                        } catch (e: IOException) {
                            // 注意：这里不能再调 ALog / LogCenter.log，不然递归玩死你
                            Log.e("ALogX", "Logcat sink write error: ${e.message}", e)
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
     */
    @Synchronized
    fun zipDay(day: String): File? {
        if (!::baseDir.isInitialized) return null

        val dayDir = File(baseDir, day)
        if (!dayDir.exists()) return null

        val out = File(baseDir, "logs_$day.zip")
        out.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        if (out.exists()) out.delete()

        // 如果 zip 的就是当前这一天，先 flush 一下 mainSink，拿一个快照文件引用
        val mainLogForDay: File? = if (day == currentDay) {
            try {
                mainSink?.flush()
            } catch (_: Throwable) {
            }
            val mainFile = File(baseDir, "main.log")
            if (mainFile.exists()) mainFile else null
        } else {
            null
        }

        ZipOutputStream(out.outputStream()).use { zip ->

            // 先把 dayDir 下所有文件打进去（但如果是当前天，先跳过已有的 app.log，后面用“合并版”替换）
            dayDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    if (day == currentDay && file.name == "app.log") {
                        return@forEach
                    }

                    val rel = file.relativeTo(baseDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(rel))
                    file.source().use { src ->
                        src.buffer().readAll(zip.sink().buffer()).also {
                            // 注意：这里不能用同一个 sink 复用，ZipOutputStream 直接写即可
                        }
                    }
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

                if (appLogFile.exists()) {
                    appLogFile.inputStream().use { it.copyTo(zip) }
                }
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
        try {
            mainSink?.flush()
            mainSink?.close()
        } catch (_: Throwable) {
        } finally {
            mainSink = null
        }

        try {
            logcatSink?.flush()
            logcatSink?.close()
        } catch (_: Throwable) {
        } finally {
            logcatSink = null
        }

        logcatThread?.interrupt()
        logcatThread = null
    }

    data class OpenBlobResult(
        val output: OutputStream,
        val commit: (writtenBytes: Long) -> BlobInfo?
    )
}
