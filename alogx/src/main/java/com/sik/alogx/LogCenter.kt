package com.sik.alogx

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
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

    /** Application Context，用于获取系统服务 */
    private var appContext: Context? = null

    /** 缓存检测到的 su 路径，避免重复探测 */
    @Volatile
    private var suPath: String? = null

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
        appContext = context.applicationContext

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
    // root / su 探测
    // ============================================================

    /**
     * 探测设备上可用的 su 二进制路径。
     * 尝试多个常见位置，用 `echo root_test` 做真实验证。
     *
     * @return su 完整路径（如 "/system/xbin/su"），探测不到返回 null
     */
    private fun detectSu(): String? {
        suPath?.let { return it }

        val candidates = listOf(
            "su",
            "/system/xbin/su",
            "/system/bin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/sbin/su"
        )

        for (path in candidates) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(path, "-c", "echo root_test"))
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                val exit = proc.waitFor()
                if (exit == 0 && output.trim() == "root_test") {
                    suPath = path
                    return path
                }
            } catch (_: Exception) {
                // 继续试下一个路径
            }
        }
        return null
    }

    /**
     * 用已探测到的 su 执行命令。
     *
     * @param cmd 要执行的命令字符串（如 "dumpsys meminfo"）
     * @return Process 对象，调用方自行读取输出
     */
    private fun execWithSu(cmd: String): Process? {
        val su = detectSu() ?: return null
        return Runtime.getRuntime().exec(arrayOf(su, "-c", cmd))
    }

    // ============================================================
    // logcat 捕获
    // ============================================================

    /**
     * 开启 logcat 捕获线程（可过滤包名）。
     * 把系统日志写入 yyyy-MM-dd/logcat.log。
     *
     * 策略：
     * 1. 先尝试用 su 权限执行，获取完整系统 logcat
     * 2. 无 su 则降级为普通权限，只能拿到本应用日志
     */
    private fun startLogcatCollector() {
        if (logcatThread != null) return

        logcatThread = Thread({
            try {
                // 先探测 root 可用性
                val su = detectSu()
                val hasRoot = su != null

                if (hasRoot) {
                    // 用 su 清空 logcat（忽略失败）
                    try {
                        execWithSu("logcat -c")?.waitFor()
                    } catch (_: Exception) {
                    }
                } else {
                    // 降级：普通权限清空（可能失败，忽略）
                    try {
                        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
                    } catch (_: Exception) {
                    }
                }

                // 启动 logcat 流：优先 su，降级普通权限
                val proc = if (hasRoot) {
                    execWithSu(config.logcatCmd.joinToString(" "))
                        ?: Runtime.getRuntime().exec(config.logcatCmd.toTypedArray())
                } else {
                    Runtime.getRuntime().exec(config.logcatCmd.toTypedArray())
                }
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
        out.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
        if (out.exists()) out.delete()

        // 如果 zip 的就是当前这一天，先 flush 一下 mainSink，拿一个快照文件引用
        val mainLogForDay: File? = if (day == currentDay) {
            try { mainSink?.flush() } catch (_: Throwable) {}
            File(baseDir, "main.log").takeIf { it.exists() }
        } else null

        ZipOutputStream(out.outputStream()).use { zip ->
            // ✅ 关键：只包一次 Okio Sink，全程复用
            val zipSink = zip.sink().buffer()

            // 先把 dayDir 下所有文件打进去
            dayDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    // 当前天：跳过已有 app.log，后面生成“合并版”覆盖进 zip
                    if (day == currentDay && file.name == "app.log") return@forEach

                    val rel = file.relativeTo(baseDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(rel))

                    file.source().use { src ->
                        // ✅ 正确：writeAll 直接把 src 写进 zipSink
                        zipSink.writeAll(src)
                        // ✅ 关键：每个 entry 写完 flush，避免小文件卡在 buffer 里变 0KB
                        zipSink.flush()
                    }

                    zip.closeEntry()
                }

            // 当前天：生成合并版 app.log = [dayDir/app.log(如果有)] + [main.log]
            if (day == currentDay) {
                val appLogFile = File(dayDir, "app.log")
                val zipEntryPath = appLogFile.relativeTo(baseDir).invariantSeparatorsPath

                zip.putNextEntry(ZipEntry(zipEntryPath))

                if (appLogFile.exists()) {
                    appLogFile.source().use { src ->
                        zipSink.writeAll(src)
                    }
                }
                mainLogForDay?.takeIf { it.exists() }?.let { mainFile ->
                    mainFile.source().use { src ->
                        zipSink.writeAll(src)
                    }
                }

                zipSink.flush()
                zip.closeEntry()
            }

            // 结束前再 flush 一次，稳
            zipSink.flush()
        }

        return out
    }

    // ============================================================
    // 内存 dump
    // ============================================================

    /**
     * 收集当前内存状态并保存到日期目录下的 meminfo.txt。
     *
     * 包含：
     * - JVM 堆内存（Runtime）
     * - 本进程详细内存（Debug.MemoryInfo）
     * - 系统整体内存（ActivityManager.MemoryInfo）
     * - dumpsys meminfo（需要 root，无 root 则标注不可用）
     *
     * 文件位置：yyyy-MM-dd/meminfo.txt（和 app.log / logcat.log 同级）
     * 每次调用覆盖前一次，不保留历史版本。
     *
     * @param tag 日志 TAG，用于在主日志中打引用
     * @return BlobInfo 文件引用（hash 字段为空，因为覆盖模式不做去重），失败返回 null
     */
    @Synchronized
    fun dumpMeminfo(tag: String): BlobInfo? {
        rolloverIfNeeded()

        if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

        val dayDir = File(baseDir, currentDay)
        if (!dayDir.exists() && !dayDir.mkdirs()) {
            Log.e("ALogX", "dumpMeminfo: mkdirs dayDir failed: ${dayDir.absolutePath}")
            return null
        }

        val sb = StringBuilder()
        sb.appendLine("========== Memory Dump ==========")
        sb.appendLine("Time:    ${Utils.now()}")
        sb.appendLine("Package: $packageName")
        sb.appendLine("PID:     ${android.os.Process.myPid()}")
        sb.appendLine()

        // 1. JVM 堆内存
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val usedMem = totalMem - freeMem

        sb.appendLine("--- JVM Heap ---")
        sb.appendLine("Max:    ${formatBytes(maxMem)}")
        sb.appendLine("Total:  ${formatBytes(totalMem)}")
        sb.appendLine("Free:   ${formatBytes(freeMem)}")
        sb.appendLine("Used:   ${formatBytes(usedMem)}")
        sb.appendLine()

        // 2. 本进程 Native / Dalvik 内存
        try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            sb.appendLine("--- Process MemoryInfo ---")
            sb.appendLine("Dalvik Pss:          ${formatBytes(mi.dalvikPss * 1024L)}")
            sb.appendLine("Native Pss:          ${formatBytes(mi.nativePss * 1024L)}")
            sb.appendLine("Total Pss:           ${formatBytes(mi.totalPss * 1024L)}")
            sb.appendLine("Dalvik PrivateDirty: ${formatBytes(mi.dalvikPrivateDirty * 1024L)}")
            sb.appendLine("Native PrivateDirty: ${formatBytes(mi.nativePrivateDirty * 1024L)}")
            sb.appendLine("Total PrivateDirty:  ${formatBytes(mi.totalPrivateDirty * 1024L)}")
            sb.appendLine()
        } catch (e: Exception) {
            sb.appendLine("--- Process MemoryInfo ---")
            sb.appendLine("Error: ${e.message}")
            sb.appendLine()
        }

        // 3. 系统整体内存
        appContext?.let { ctx ->
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                sb.appendLine("--- System Memory ---")
                sb.appendLine("Total:      ${formatBytes(memInfo.totalMem)}")
                sb.appendLine("Available:  ${formatBytes(memInfo.availMem)}")
                sb.appendLine("Threshold:  ${formatBytes(memInfo.threshold)}")
                sb.appendLine("Low Memory: ${memInfo.lowMemory}")
                sb.appendLine()
            } catch (e: Exception) {
                sb.appendLine("--- System Memory ---")
                sb.appendLine("Error: ${e.message}")
                sb.appendLine()
            }
        } ?: run {
            sb.appendLine("--- System Memory ---")
            sb.appendLine("Context not available (call LogCenter.init first)")
            sb.appendLine()
        }

        // 4. dumpsys meminfo（需要 root，输出所有进程）
        sb.appendLine("--- dumpsys meminfo (all processes) ---")
        val dumpsys = try {
            execWithSu("dumpsys meminfo")
                ?.inputStream?.bufferedReader()
                ?.use { it.readText() }
                ?: "Unavailable (root not detected)"
        } catch (e: Exception) {
            "Unavailable (root required): ${e.javaClass.simpleName}"
        }
        sb.append(dumpsys)
        sb.appendLine()
        sb.appendLine("=================================")

        // 直接写到 yyyy-MM-dd/meminfo.txt，覆盖模式
        val file = File(dayDir, "meminfo.txt")
        return try {
            file.sink(append = false).buffer().use { sink ->
                sink.writeUtf8(sb.toString())
                sink.flush()
            }
            val rel = file.relativeTo(baseDir).invariantSeparatorsPath
            BlobInfo(relativePath = rel, size = file.length().toInt(), hash = "-")
        } catch (e: IOException) {
            Log.e("ALogX", "dumpMeminfo write error: ${e.message}", e)
            null
        }
    }

    /**
     * 字节数格式化为人类可读字符串。
     */
    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            else -> String.format("%.2f KB", kb)
        }
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
