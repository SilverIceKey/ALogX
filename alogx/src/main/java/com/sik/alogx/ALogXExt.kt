package com.sik.alogx

/**
 * Hex数组
 */
private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()

/**
 * ByteArray 转 Hex 字符串。
 */
fun ByteArray.toHex(): String {
    val result = CharArray(this.size * 2)
    var index = 0
    for (b in this) {
        val v = b.toInt() and 0xFF
        result[index++] = HEX_ARRAY[v ushr 4]
        result[index++] = HEX_ARRAY[v and 0x0F]
    }
    return String(result)
}