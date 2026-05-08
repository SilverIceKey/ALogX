# ALogX

**ALogX = Android 超轻量级 + 高性能 + 可扩展日志框架。**  
目标是：**用最少的代码做最多的事情。**  
适合想摆脱 Logcat 限制、实现“真正可用日志系统”的 Android 项目。

---

## ✨ 特性

- 🚀 **高性能文件日志写入**（8KB buffer，低 GC）
- 📅 **每日滚动日志**（`main.log → yyyy-MM-dd/app.log`）
- 🧹 **按天清理历史日志**
- 📂 **目录结构清晰：**  
  `/sdcard/<appName>/logs/yyyy-MM-dd/*.log`
- 🐾 **同时写入 Android Logcat**（调试友好）
- 🔍 **捕获 logcat 输出到文件**（可过滤仅记录本应用）
- 🧵 **自动包含线程名**
- 📏 **long log 自动分段输出**
- 🌈 **JSON pretty output**
- 🔡 **HEX 输出（ByteArray → Hex）**
- 📦 **按天打包日志 ZIP**
- 🔐 **可选加密接口，你传我加密后的内容我直接存**
- 🧱 **无侵入，完全独立，不依赖第三方库**

---

## 📦 安装

放到你项目的任意 module 中即可（建议独立 module：`alogx`）。

包名：com.sik.alogx

jitpack依赖

---

## 🚀 初始化（必须）

在你的 `Application` 中：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        ALogX.init(
            context = this,
            cfg = LogConfig(
                appName = "ALogXDemo",
                maxKeepDays = 7,
                enableLogcat = true,
                onlyPackageLogcat = true
            )
        )
    }
}
```

## 📝 基础使用

```kotlin
ALog.v("TAG", "Verbose message")
ALog.d("TAG", "Debug message")
ALog.i("TAG", "Info message")
ALog.w("TAG", "Warning message")
ALog.e("TAG", "Error message")
ALog.wtf("TAG", "WTF message")
```

## 🎯 自动 TAG（最常用）

```kotlin
ALog.d("这条日志会自动用调用文件名做 TAG")
```
输出类似：
```less
2025-11-13 12:30:22.331 | D/MainActivity | main | 这条日志...
```

## 📏 长日志自动分段（避免 4K 限制）

```kotlin
ALog.long("TAG", longString)
```

## 🧬 JSON 漂亮输出

```kotlin
ALog.json("TAG", jsonString)
```
效果：
```css
{
    "code": 200,
    "msg": "ok",
    "data": { ... }
}
```

## 🔡 HEX 输出（ByteArray）

```kotlin
ALog.hex("TAG", bytes)
```
效果示例：
```mathematica
FA 01 0A FF 32 9C ...
```

## 🔐 自定义加密或 blob 大文件

ALogX 不替你加密，你自己处理完给我，我存：
```kotlin
ALogX.writeBlob("TAG", "fileName.bin", encryptedByteArray)
```
或你已经转成 Base64 / Hex：
```kotlin
ALogX.writeBlobString("TAG", "img_base64.txt", base64Text)
```
日志文件只会存引用：
```less
2025-11-13 ... | I/MainActivity | 写入 Blob: img_base64.txt (12 KB)
```
大文件不会塞进普通文本日志里！

## 📦 获取某一天日志 ZIP

```kotlin
val zip = LogCenter.zipDay("2025-11-13")
if (zip != null) {
    // 发送、上传、分享，随便你
}
```
压缩结构：
```lua
2025-11-13/
    app.log
    logcat.log
    blob_xxx.bin
logs_2025-11-13.zip
```

## 🐾 捕获 logcat（可选）

开启后：
- 自动执行 ```logcat -c```
- 写入：```yyyy-MM-dd/logcat.log```
- 可过滤仅记录包含包名的行

配置：
```kotlin
enableLogcat = true
onlyPackageLogcat = true
logcatCmd = listOf("logcat", "-v", "time")
```

## 📁 日志目录结构（示例）

```lua
/sdcard/ALogXDemo/logs/
├── main.log
├── 2025-11-13/
│   ├── app.log
│   ├── logcat.log
│   ├── img_base64.txt
│   └── blob_1.bin
└── 2025-11-14/
    └── app.log
```

## 🔧 权限（必须）

```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
```
动态申请：
```kotlin
ActivityCompat.requestPermissions(
    this,
    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
    123
)
```
Android 11+：

你必须使用：
```kotlin
val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
intent.data = Uri.parse("package:$packageName")
startActivity(intent)
```
否则 App 无法读写 SD 卡。

## 🧩 自定义配置

```kotlin
data class LogConfig(
    val appName: String = "ALogX",
    val maxKeepDays: Int = 7,
    val enableLogcat: Boolean = false,
    val onlyPackageLogcat: Boolean = true,
    val logcatCmd: Array<String> = arrayOf("logcat", "-v", "time")
)
```

## 🔥 ALogX 设计理念
- ### 不依赖任何第三方库
- ### 尽量少代码，但绝不牺牲功能
- ### 保证格式统一、目录清晰、文件可压缩可分享
- ### 解耦：加密、blob、上传全由你控制

## 🧪 Demo

```MainActivity```示例按钮会输出：

V / D / I / W / E / WTF

自动 TAG

long log

JSON log

HEX log

方便你直接测试效果。