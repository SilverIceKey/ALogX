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

    /** 文件日志是否可用。目录不可写时关闭文件写入，避免日志系统拖垮调用方。 */
    @Volatile
    private var fileLoggingEnabled: Boolean = false

    /** 当前应用包名，用于过滤 logcat */
    private lateinit var packageName: String

    /** Application Context，用于获取系统服务 */
    private var appContext: Context? = null

    /** 缓存检测到的 su 路径，避免重复探测 */
    @Volatile
    private var suPath: String? = null

    /** 主日志锁：保护 mainSink / currentDay / rollover */
    private val mainLock = Any()

    /** blob 锁：保护 blob 文件写入（与 main 日志分离，避免大对象阻塞普通日志） */
    private val blobLock = Any()

    /** logcat 锁：保护 logcatSink 读写，避免 logcat 采集与主日志互抢 */
    private val logcatLock = Any()

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
        shutdown()
        config = cfg
        packageName = context.packageName
        appContext = context.applicationContext
        currentDay = ""

        baseDir = resolveWritableBaseDir(context, cfg)

        if (!prepareLogDir(baseDir)) {
            fileLoggingEnabled = false
            Log.e("ALogX", "init: log dir unavailable: ${baseDir.absolutePath}")
            return
        }

        fileLoggingEnabled = true

        // 启动时只做一次“状态恢复 / 当天检测”
        if (!safeRolloverIfNeeded(force = false)) return

        // 开启 logcat 采集（系统 logcat → 本地 logcat.log）
        if (cfg.enableLogcat) {
            startLogcatCollector()
        }
    }

    private fun resolveWritableBaseDir(context: Context, cfg: LogConfig): File {
        val publicDir = File(
            Environment.getExternalStorageDirectory(),
            "${cfg.appName}/logs"
        )
        if (prepareLogDir(publicDir)) return publicDir

        val externalFilesDir = context.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            val appSpecificDir = File(externalFilesDir, "${cfg.appName}/logs")
            if (prepareLogDir(appSpecificDir)) {
                Log.w("ALogX", "fallback to app-specific log dir: ${appSpecificDir.absolutePath}")
                return appSpecificDir
            }
        }

        val internalDir = File(context.filesDir, "${cfg.appName}/logs")
        if (prepareLogDir(internalDir)) {
            Log.w("ALogX", "fallback to internal log dir: ${internalDir.absolutePath}")
            return internalDir
        }

        return publicDir
    }

    private fun prepareLogDir(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false

            val probe = File(dir, ".alogx_probe")
            probe.outputStream().use { }
            if (probe.exists()) probe.delete()
            true
        } catch (e: Throwable) {
            Log.e("ALogX", "prepareLogDir failed: ${dir.absolutePath}, ${e.message}", e)
            false
        }
    }

    private fun safeRolloverIfNeeded(force: Boolean = false): Boolean {
        if (!fileLoggingEnabled || !::baseDir.isInitialized) return false
        return try {
            rolloverIfNeeded(force)
            mainSink != null && currentDay.isNotEmpty()
        } catch (e: Throwable) {
            disableFileLogging("rollover failed: ${e.message}", e)
            false
        }
    }

    private fun disableFileLogging(reason: String, throwable: Throwable? = null) {
        fileLoggingEnabled = false
        try { mainSink?.flush(); mainSink?.close() } catch (_: Throwable) {}
        mainSink = null
        synchronized(logcatLock) {
            try { logcatSink?.flush(); logcatSink?.close() } catch (_: Throwable) {}
            logcatSink = null
        }
        logcatThread?.interrupt()
        logcatThread = null
        Log.e("ALogX", reason, throwable)
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
    fun log(level: Char, tag: String, msg: String) {
        val ts: String
        val threadName: String

        // 1) 写文件：在 mainLock 内完成，避免多线程并发写 main.log
        synchronized(mainLock) {
            if (!safeRolloverIfNeeded()) return
            val sink = mainSink ?: return
            ts = Utils.now()
            threadName = Thread.currentThread().name
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
                Log.e("ALogX", "log write error: ${e.message}", e)
            }
        }

        // 2) 打 Android logcat：在锁外，避免 Android Log API 阻塞文件写入
        val logcatMsg = if (msg.length > 4000) msg.take(4000) + "…" else msg
        val lineForLogcat = "$ts | $level/$tag | $threadName | $logcatMsg"
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

    /**
     * 长日志写入：按 chunkSize 分段写文件，文件侧不创建子串（用 sink.writeUtf8 区间写入）
     * Logcat 侧仍截断到 4000 字符。
     */
    fun logLong(level: Char, tag: String, msg: String, chunkSize: Int = 3000) {
        val ts: String
        val threadName: String
        val prefix: String

        synchronized(mainLock) {
            if (!safeRolloverIfNeeded()) return
            val sink = mainSink ?: return
            ts = Utils.now()
            threadName = Thread.currentThread().name
            prefix = "$ts | $level/$tag | $threadName | "

            var index = 0
            while (index < msg.length) {
                val end = (index + chunkSize).coerceAtMost(msg.length)

                // 文件写入：直接用 writeUtf8(string, start, end)，不创建子串
                try {
                    sink.writeUtf8(prefix)
                    sink.writeUtf8(msg, index, end)
                    sink.writeByte('\n'.code)
                    sink.flush()
                } catch (e: IOException) {
                    Log.e("ALogX", "logLong write error: ${e.message}", e)
                }

                index = end
            }
        }

        // Logcat 输出在锁外：必须截断，Android Log API 单条上限约 4000
        var index = 0
        while (index < msg.length) {
            val end = (index + chunkSize).coerceAtMost(msg.length)
            val chunkLen = end - index
            val forLogcat = if (chunkLen > 4000) {
                msg.substring(index, index + 4000) + "…"
            } else {
                msg.substring(index, end)
            }
            val lineForLogcat = "$ts | $level/$tag | $threadName | $forLogcat"
            when (level) {
                'V' -> Log.v(tag, lineForLogcat)
                'D' -> Log.d(tag, lineForLogcat)
                'I' -> Log.i(tag, lineForLogcat)
                'W' -> Log.w(tag, lineForLogcat)
                'E' -> Log.e(tag, lineForLogcat)
                'F' -> Log.wtf(tag, lineForLogcat)
                else -> Log.d(tag, lineForLogcat)
            }
            index = end
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
    /**
     * 将一段二进制数据保存到"当天目录/blobs/"下，并返回引用信息。
     *
     * 目录结构示例：
     *   /sdcard/ALogX/logs/2025-11-14/blobs/ab23cd9f.png
     *
     * 实现改为 Okio HashingSink：边写边算 md5，避免对大 byte[] 做全量内存扫描。
     */
    fun saveBlob(bytes: ByteArray, suffix: String = ".bin"): BlobInfo? {
        val warnThreshold = 20 * 1024 * 1024
        if (bytes.size > warnThreshold) {
            Log.w("ALogX", "saveBlob: extremely large blob (${bytes.size} bytes), consider using openTextBlobStream()")
        }

        synchronized(blobLock) {
            if (!safeRolloverIfNeeded()) return null

            if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

            if (!baseDir.exists() && !baseDir.mkdirs()) {
                Log.e("ALogX", "saveBlob: mkdirs baseDir failed: ${baseDir.absolutePath}")
                return null
            }

            val dayDir = File(baseDir, currentDay)
            if (!dayDir.exists() && !dayDir.mkdirs()) {
                Log.e("ALogX", "saveBlob: mkdirs dayDir failed: ${dayDir.absolutePath}")
                return null
            }

            val blobDir = File(dayDir, "blobs")
            if (!blobDir.exists() && !blobDir.mkdirs()) {
                Log.e("ALogX", "saveBlob: mkdirs blobDir failed: ${blobDir.absolutePath}")
                return null
            }

            val tmp = File(blobDir, "tmp_${System.currentTimeMillis()}$suffix")

            return try {
                val rawSink = tmp.sink(append = false)
                val hashingSink = HashingSink.md5(rawSink)
                val buffered = hashingSink.buffer()

                buffered.write(bytes)
                buffered.flush()
                buffered.close()

                val hash = hashingSink.hash.hex()
                val dst = File(blobDir, "$hash$suffix")
                if (dst.exists()) {
                    tmp.delete()
                } else {
                    tmp.renameTo(dst)
                }

                val relPath = dst.relativeTo(baseDir).invariantSeparatorsPath
                BlobInfo(
                    relativePath = relPath,
                    size = dst.length().toInt(),
                    hash = hash
                )
            } catch (e: IOException) {
                tmp.delete()
                Log.e("ALogX", "saveBlob error: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 将一段字符串作为 blob 保存到“当天目录/blobs/”下，并返回引用信息。
     *
     * 关键点：不再 content.toByteArray() 先整块转 bytes（那一下很吃内存）
     * 改为：边写 UTF-8 边做 MD5，写完后按 hash 重命名。
     */
    fun saveBlobString(content: String, suffix: String = ".txt"): BlobInfo? {
        synchronized(blobLock) {
            if (!safeRolloverIfNeeded()) return null

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

            val tmp = File(blobDir, "tmp_${System.currentTimeMillis()}$suffix")
            var buffered: BufferedSink? = null

            return try {
                val raw: Sink = tmp.sink(append = false)
                val hashing = HashingSink.md5(raw)
                val activeSink = hashing.buffer()
                buffered = activeSink
                activeSink.writeUtf8(content)
                activeSink.flush()
                activeSink.close()

                val hash = hashing.hash.hex()
                val dst = File(blobDir, "$hash$suffix")
                if (dst.exists()) {
                    tmp.delete()
                } else {
                    tmp.renameTo(dst)
                }

                val relPath = dst.relativeTo(baseDir).invariantSeparatorsPath

                BlobInfo(
                    relativePath = relPath,
                    size = dst.length().toInt(),
                    hash = hash
                )
            } catch (e: IOException) {
                try {
                    buffered?.close()
                } catch (_: Throwable) {
                }
                tmp.delete()
                Log.e("ALogX", "saveBlobString error: ${e.message}", e)
                null
            }
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
    fun openTextBlobStream(suffix: String = ".txt"): OpenBlobResult? {
        val dayDir: File
        val blobDir: File
        val tmp: File

        synchronized(blobLock) {
            if (!safeRolloverIfNeeded()) return null
            if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

            dayDir = File(baseDir, currentDay).apply { if (!exists()) mkdirs() }
            blobDir = File(dayDir, "blobs").apply { if (!exists()) mkdirs() }

            tmp = File(blobDir, "tmp_${System.currentTimeMillis()}$suffix")
        }

        return try {
            val rawSink = tmp.sink(append = false)
            val hashingSink = HashingSink.md5(rawSink)
            val buffered = hashingSink.buffer()
            val os: OutputStream = buffered.outputStream()
            val committed = AtomicBoolean(false)

            val commit = { writtenBytes: Long ->
                if (committed.compareAndSet(false, true)) {
                    try {
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
                    null
                }
            }

            OpenBlobResult(output = os, commit = commit)
        } catch (e: Throwable) {
            tmp.delete()
            Log.e("ALogX", "openTextBlobStream error: ${e.message}", e)
            null
        }
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
    private fun rolloverIfNeeded(force: Boolean = false) {
        synchronized(mainLock) {
            if (!fileLoggingEnabled || !::baseDir.isInitialized) return

            val today = Utils.today()
            val mainFile = File(baseDir, "main.log")

            // 1. 进程重启情况下，currentDay 可能是 ""，尝试从 main.log 推断日期
            if (currentDay.isEmpty() && mainFile.exists()) {
                currentDay = dayOf(mainFile.lastModified())
            }

            // NTP 回拨保护：如果当前系统时间比已记录的日期还早，说明时间被回拨了。
            // 此时不触发归档，继续写当前文件，避免日志被埋进错误的历史目录。
            if (!force && today < currentDay) {
                if (!baseDir.exists()) baseDir.mkdirs()
                if (mainSink == null) {
                    mainFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    mainSink = mainFile.sink(append = true).buffer()
                }
                if (config.enableLogcat && logcatSink == null) {
                    val dayDir = File(baseDir, currentDay)
                    if (!dayDir.exists()) dayDir.mkdirs()
                    val logcatFile = File(dayDir, "logcat.log")
                    logcatFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    synchronized(logcatLock) {
                        logcatSink = logcatFile.sink(append = true).buffer()
                    }
                }
                return
            }

            // 2. 正常同一天，不需要日切
            if (!force && today == currentDay) {
                if (!baseDir.exists()) baseDir.mkdirs()
                if (mainSink == null) {
                    mainFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    mainSink = mainFile.sink(append = true).buffer()
                }
                if (config.enableLogcat && logcatSink == null) {
                    val dayDir = File(baseDir, today)
                    if (!dayDir.exists()) dayDir.mkdirs()
                    val logcatFile = File(dayDir, "logcat.log")
                    logcatFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    synchronized(logcatLock) {
                        logcatSink = logcatFile.sink(append = true).buffer()
                    }
                }
                return
            }

            // 3. 真正日切：today > currentDay 或 force
            try { mainSink?.flush(); mainSink?.close() } catch (_: Throwable) {}
            mainSink = null

            synchronized(logcatLock) {
                try { logcatSink?.flush(); logcatSink?.close() } catch (_: Throwable) {}
                logcatSink = null
            }

            if (!baseDir.exists()) baseDir.mkdirs()

            if (currentDay.isNotEmpty() && mainFile.exists()) {
                val dayDir = File(baseDir, currentDay)
                if (!dayDir.exists()) dayDir.mkdirs()
                val appLogFile = File(dayDir, "app.log")
                appLogFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                mainFile.renameTo(appLogFile)
            }

            currentDay = today

            mainFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            mainSink = mainFile.sink(append = false).buffer()

            if (config.enableLogcat) {
                val dayDir = File(baseDir, currentDay)
                if (!dayDir.exists()) dayDir.mkdirs()
                val logcatFile = File(dayDir, "logcat.log")
                logcatFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                synchronized(logcatLock) {
                    logcatSink = logcatFile.sink(append = false).buffer()
                }
            }

            cleanupOldDays()
        }
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

                    if (!safeRolloverIfNeeded()) continue
                    synchronized(logcatLock) {
                        try {
                            logcatSink?.apply {
                                writeUtf8(t)
                                writeByte('\n'.code)
                                flush()
                            }
                        } catch (e: IOException) {
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
    fun zipDay(day: String): File? {
        synchronized(mainLock) {
            if (!::baseDir.isInitialized) return null

            val dayDir = File(baseDir, day)
            if (!dayDir.exists()) return null

            val out = File(baseDir, "logs_$day.zip")
            out.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
            if (out.exists()) out.delete()

            val mainLogForDay: File? = if (day == currentDay) {
                try { mainSink?.flush() } catch (_: Throwable) {}
                File(baseDir, "main.log").takeIf { it.exists() }
            } else null

            ZipOutputStream(out.outputStream()).use { zip ->
                val zipSink = zip.sink().buffer()

                dayDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        if (day == currentDay && file.name == "app.log") return@forEach

                        val rel = file.relativeTo(baseDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(rel))

                        file.source().use { src ->
                            zipSink.writeAll(src)
                            zipSink.flush()
                        }

                        zip.closeEntry()
                    }

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

                zipSink.flush()
            }

            return out
        }
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
        if (!safeRolloverIfNeeded()) return null

        if (!::baseDir.isInitialized || currentDay.isEmpty()) return null

        val dayDir = File(baseDir, currentDay)
        if (!dayDir.exists() && !dayDir.mkdirs()) {
            Log.e("ALogX", "dumpMeminfo: mkdirs dayDir failed: ${dayDir.absolutePath}")
            return null
        }

        val file = File(dayDir, "meminfo.txt")
        return try {
            file.sink(append = false).buffer().use { sink ->
                sink.writeUtf8("========== Memory Dump ==========\n")
                sink.writeUtf8("Time:    ${Utils.now()}\n")
                sink.writeUtf8("Package: $packageName\n")
                sink.writeUtf8("PID:     ${android.os.Process.myPid()}\n")
                sink.writeByte('\n'.code)

                // 1. JVM 堆内存
                val runtime = Runtime.getRuntime()
                val maxMem = runtime.maxMemory()
                val totalMem = runtime.totalMemory()
                val freeMem = runtime.freeMemory()
                val usedMem = totalMem - freeMem

                sink.writeUtf8("--- JVM Heap ---\n")
                sink.writeUtf8("Max:    ${formatBytes(maxMem)}\n")
                sink.writeUtf8("Total:  ${formatBytes(totalMem)}\n")
                sink.writeUtf8("Free:   ${formatBytes(freeMem)}\n")
                sink.writeUtf8("Used:   ${formatBytes(usedMem)}\n")
                sink.writeByte('\n'.code)

                // 2. 本进程 Native / Dalvik 内存
                try {
                    val mi = Debug.MemoryInfo()
                    Debug.getMemoryInfo(mi)
                    sink.writeUtf8("--- Process MemoryInfo ---\n")
                    sink.writeUtf8("Dalvik Pss:          ${formatBytes(mi.dalvikPss * 1024L)}\n")
                    sink.writeUtf8("Native Pss:          ${formatBytes(mi.nativePss * 1024L)}\n")
                    sink.writeUtf8("Total Pss:           ${formatBytes(mi.totalPss * 1024L)}\n")
                    sink.writeUtf8("Dalvik PrivateDirty: ${formatBytes(mi.dalvikPrivateDirty * 1024L)}\n")
                    sink.writeUtf8("Native PrivateDirty: ${formatBytes(mi.nativePrivateDirty * 1024L)}\n")
                    sink.writeUtf8("Total PrivateDirty:  ${formatBytes(mi.totalPrivateDirty * 1024L)}\n")
                    sink.writeByte('\n'.code)
                } catch (e: Exception) {
                    sink.writeUtf8("--- Process MemoryInfo ---\n")
                    sink.writeUtf8("Error: ${e.message}\n")
                    sink.writeByte('\n'.code)
                }

                // 3. 系统整体内存
                appContext?.let { ctx ->
                    try {
                        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val memInfo = ActivityManager.MemoryInfo()
                        am.getMemoryInfo(memInfo)
                        sink.writeUtf8("--- System Memory ---\n")
                        sink.writeUtf8("Total:      ${formatBytes(memInfo.totalMem)}\n")
                        sink.writeUtf8("Available:  ${formatBytes(memInfo.availMem)}\n")
                        sink.writeUtf8("Threshold:  ${formatBytes(memInfo.threshold)}\n")
                        sink.writeUtf8("Low Memory: ${memInfo.lowMemory}\n")
                        sink.writeByte('\n'.code)
                    } catch (e: Exception) {
                        sink.writeUtf8("--- System Memory ---\n")
                        sink.writeUtf8("Error: ${e.message}\n")
                        sink.writeByte('\n'.code)
                    }
                } ?: run {
                    sink.writeUtf8("--- System Memory ---\n")
                    sink.writeUtf8("Context not available (call LogCenter.init first)\n")
                    sink.writeByte('\n'.code)
                }

                // 4. dumpsys meminfo（需要 root，逐行流式写入）
                sink.writeUtf8("--- dumpsys meminfo (all processes) ---\n")
                try {
                    execWithSu("dumpsys meminfo")?.inputStream?.bufferedReader()?.use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            sink.writeUtf8(line)
                            sink.writeByte('\n'.code)
                            line = reader.readLine()
                        }
                    } ?: sink.writeUtf8("Unavailable (root not detected)\n")
                } catch (e: Exception) {
                    sink.writeUtf8("Unavailable (root required): ${e.javaClass.simpleName}\n")
                }
                sink.writeUtf8("=================================\n")
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
        fileLoggingEnabled = false
        currentDay = ""

        logcatThread?.interrupt()
        logcatThread = null
    }

    data class OpenBlobResult(
        val output: OutputStream,
        val commit: (writtenBytes: Long) -> BlobInfo?
    )
}
