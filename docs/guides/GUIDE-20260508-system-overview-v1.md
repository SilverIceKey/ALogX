# ALogX 系统能力概览

## 1. 定位

ALogX = Android 超轻量级 + 高性能 + 可扩展日志框架。  
目标：用最少的代码做最多的事情，摆脱 Logcat 限制。

## 2. 模块划分

```
ALogX/
├── alogx/          # Library 模块（对外发布）
│   ├── ALog.kt         # 对外 API
│   ├── LogCenter.kt    # 核心引擎
│   ├── LogConfig.kt    # 全局配置
│   ├── Utils.kt        # 日期时间工具
│   ├── LogEncryptor.kt # 加密接口
│   └── ALogXExt.kt     # ByteArray 扩展
│
├── app/            # Demo App（Jetpack Compose）
│   ├── MainActivity.kt
│   └── App.kt
└── docs/           # 文档体系
```

## 3. 核心能力

| 能力 | 实现位置 | 说明 |
|------|---------|------|
| 文件日志写入 | `LogCenter.log()` | Okio BufferedSink，8KB buffer，低 GC |
| 每日滚动 | `LogCenter.rolloverIfNeeded()` | `main.log` → `yyyy-MM-dd/app.log` |
| 历史清理 | `LogCenter.cleanupOldDays()` | 按 `maxKeepDays` 删除过期目录 |
| Logcat 双写 | `LogCenter.log()` | 写文件同时打 Android Logcat |
| Logcat 捕获 | `LogCenter.startLogcatCollector()` | 后台线程捕获系统 logcat |
| 自动 TAG | `ALog.autoTag()` | 从堆栈取调用方文件名 |
| 长日志分段 | `ALog.long()` | 默认 3000 字符分段 |
| JSON 格式化 | `ALog.json()` | `JSONObject/JSONArray.toString(4)` |
| HEX 输出 | `ALog.hex()` | `String.format("%02X ", b)` |
| Blob 存储 | `LogCenter.saveBlob()` / `saveBlobString()` | 大文件单独存 blobs/，日志只存引用 |
| ZIP 打包 | `LogCenter.zipDay()` | 按天打包，当前天合并 main.log |
| 可选加密 | `LogEncryptor` 接口 | 用户自定义，框架只调用 |

## 4. 日志目录结构

```
/sdcard/{appName}/logs/
├── main.log
├── 2025-05-08/
│   ├── app.log
│   ├── logcat.log
│   └── blobs/
│       ├── ab23cd9f.bin
│       └── ...
└── logs_2025-05-08.zip
```

## 5. 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 2.2.20 |
| AGP | 8.13.1 |
| Gradle | 8.13 |
| compileSdk | 36 |
| minSdk | 24 |
| targetSdk | 36 |
| Java target | 11 |
| Demo UI | Jetpack Compose |
| 唯一第三方依赖 | Okio 3.16.2 |

## 6. 对外 API

```kotlin
// 基础等级
ALog.v(tag, msg)
ALog.d(tag, msg)
ALog.i(tag, msg)
ALog.w(tag, msg)
ALog.e(tag, msg)
ALog.wtf(tag, msg)

// 自动 TAG（最常用）
ALog.d("自动取文件名做 TAG")

// 长日志 / JSON / HEX / Blob
ALog.long(tag, msg)
ALog.json(tag, jsonString)
ALog.hex(tag, byteArray)
ALog.blob(tag, label, bytes, suffix)
ALog.blobString(tag, label, content, suffix)

// ZIP 打包
val zip = LogCenter.zipDay("2025-05-08")

// 初始化
LogCenter.init(context, LogConfig(appName = "MyApp", maxKeepDays = 7))
```

## 7. 配置项

```kotlin
data class LogConfig(
    val appName: String = "ALogX",
    val maxKeepDays: Int = 7,
    val enableLogcat: Boolean = true,
    val logcatCmd: List<String> = listOf("logcat", "-v", "time"),
    val onlyPackageLogcat: Boolean = true
)
```

## 8. 已知技术债

| 问题 | 位置 | 优先级 |
|------|------|--------|
| Publication 配置警告 | `alogx/build.gradle.kts` | 低 |
| app 与 alogx namespace 重复 | `app/build.gradle.kts` + `alogx/build.gradle.kts` | 低 |
| 单元测试仅含示例 | `alogx/src/test/`, `app/src/test/` | 中 |
