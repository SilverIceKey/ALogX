package com.sik.alogx

/**
 * 日志加密接口（可选）。
 *
 * - 默认情况下，LogCenter 不会加密，直接写明文日志。
 * - 如果你调用 LogCenter.setEncryptor(...) 传入实现，
 *   那么写入文件前会调用 encrypt(line) 对单行日志做处理。
 *
 * 注意：
 * - 这里只管“单行字符串 → 加工后的单行字符串”
 * - 至于你是 AES/DES/自定义猫哭加密，我不管，你来实现。
 */
fun interface LogEncryptor {

    /**
     * 对一行日志做加密/混淆/脱敏。
     *
     * @param line  原始日志字符串（含时间、等级、线程等）
     * @return      处理后的字符串（写入文件用）
     */
    fun encrypt(line: String): String
}
