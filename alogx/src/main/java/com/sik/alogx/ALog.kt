package com.sik.alogx

import org.json.JSONArray
import org.json.JSONObject

/**
 * ===============================================================
 *  ALogX 的对外日志入口
 *
 *  职责：
 *  1. 提供各种便捷 API（V/D/I/W/E/WTF、自动 TAG、long、json、hex）
 *  2. 所有日志最终都转发到 LogCenter.log(...)
 *
 *  真正写文件 / 打 logcat 的逻辑全部在 LogCenter 里处理。
 * ===============================================================
 */
object ALog {

    // ─────────────────────────── 基础日志等级 ───────────────────────────

    /** Verbose 日志 */
    fun v(tag: String, msg: String) =
        LogCenter.log('V', tag, msg)

    /** Debug 日志 */
    fun d(tag: String, msg: String) =
        LogCenter.log('D', tag, msg)

    /** Info 日志 */
    fun i(tag: String, msg: String) =
        LogCenter.log('I', tag, msg)

    /** Warn 日志 */
    fun w(tag: String, msg: String) =
        LogCenter.log('W', tag, msg)

    /** Error 日志 */
    fun e(tag: String, msg: String) =
        LogCenter.log('E', tag, msg)

    /** Fatal / WTF 日志 */
    fun wtf(tag: String, msg: String) =
        LogCenter.log('F', tag, msg)


    // ─────────────────────────── 自动 TAG（日常最常用）──────────────────────────

    /**
     * 自动从调用栈里获取调用方的 文件名 作为 TAG。
     *
     * 示例：
     *     ALog.d("你好")
     * 实际效果：
     *     D/MainActivity.kt: 你好
     */
    private fun autoTag(): String {
        val stack = Throwable().stackTrace
        return stack.getOrNull(2)?.fileName ?: "ALogX"
    }

    fun v(msg: String) = v(autoTag(), msg)
    fun d(msg: String) = d(autoTag(), msg)
    fun i(msg: String) = i(autoTag(), msg)
    fun w(msg: String) = w(autoTag(), msg)
    fun e(msg: String) = e(autoTag(), msg)
    fun wtf(msg: String) = wtf(autoTag(), msg)


    // ─────────────────── long log：自动分段输出长日志 ───────────────────

    /**
     * Android logcat 单条最大约 4000 字。
     * 所以这里自动做分段，避免被截断。
     */
    fun long(tag: String, msg: String, maxLen: Int = 3000) {

        if (msg.length <= maxLen) {
            d(tag, msg)
            return
        }

        var index = 0
        while (index < msg.length) {
            val end = (index + maxLen).coerceAtMost(msg.length)
            d(tag, msg.substring(index, end))
            index = end
        }
    }


    // ─────────────────── JSON 格式化输出 ───────────────────

    /**
     * 自动判断是 {} 还是 []，并格式化为美观的 JSON。
     */
    fun json(tag: String, json: String) {
        try {
            val trim = json.trim()
            val formatted = when {
                trim.startsWith("{") ->
                    JSONObject(trim).toString(4)

                trim.startsWith("[") ->
                    JSONArray(trim).toString(4)

                else -> json
            }

            long(tag, formatted)

        } catch (e: Exception) {
            e(tag, "JSON parse error: ${e.message}")
            long(tag, json)
        }
    }


    // ─────────────────── HEX 输出（调试 ByteArray）──────────────────

    /**
     * 把字节数组格式化为：
     *  01 FF 0A 9C ...
     */
    fun hex(tag: String, bytes: ByteArray) {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X ", b))
        }
        long(tag, sb.toString())
    }

    // ─────────────────── Blob：大块二进制数据输出 ───────────────────

    /**
     * 直接保存二进制数据为 blob 文件，并在主日志里打一个引用。
     *
     * @param tag    日志 TAG
     * @param label  这块数据的用途说明（例如 "upload_image"）
     * @param bytes  二进制数据（例如图片/压缩包等）
     * @param suffix 文件后缀，例如 ".png" ".jpg" ".bin"
     * @return BlobInfo 文件相对路径
     */
    fun blob(tag: String, label: String, bytes: ByteArray, suffix: String = ".bin"): String {
        val info = LogCenter.saveBlob(bytes, suffix)
        if (info == null) {
            e(tag, "blob save failed | label=$label | size=${bytes.size}")
        } else {
            i(
                tag,
                "blob saved | label=$label | size=${info.size} | hash=${info.hash} | blobPath=${info.relativePath}"
            )
        }
        return info?.relativePath.orEmpty()
    }

    /**
     * 自动 TAG 版本：直接传 label + bytes数组。
     */
    fun blob(label: String, bytes: ByteArray, suffix: String = ".bin"): String {
        return blob(autoTag(), label, bytes, suffix)
    }

    // ─────────────────── BLOB 字符串输出（大块 String 单独存文件）──────────────────

    /**
     * 将一段已经处理好的大字符串（base64 / HEX / 压缩 JSON 等）
     * 存成 blob 文件，并在主日志里打一个引用。
     *
     * @param tag      日志 TAG
     * @param label    这块数据的用途说明（比如 "user_avatar_base64"）
     * @param content  已经转换好的 blob 数据（String），不会再做 decode
     * @param suffix   文件后缀，例如 ".txt"、".b64"、".hex"
     */
    fun blobString(tag: String, label: String, content: String, suffix: String = ".txt") {
        val info = LogCenter.saveBlobString(content, suffix)
        if (info == null) {
            e(tag, "blobString save failed | label=$label | length=${content.length}")
        } else {
            i(
                tag,
                "blobString saved | label=$label | length=${content.length} | bytes=${info.size} | hash=${info.hash} | blobPath=${info.relativePath}"
            )
        }
    }

    /**
     * 自动 TAG 版本：直接传 label + content。
     */
    fun blobString(label: String, content: String, suffix: String = ".txt") {
        blobString(autoTag(), label, content, suffix)
    }

}
