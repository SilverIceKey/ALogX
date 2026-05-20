# PROGRESS-20260520-memory-optimization-v2

## 当前状态

kiosk 7×24h 场景下的方案 B 优化已落盘，编译通过（0 warning），对外 API 零变更。

## 本轮范围

基于 kiosk 7×24h + 大 blob（图片 BASE64）场景，执行最小改动的锁分离与 GC 优化：

1. `autoTag()` 缓存：ThreadLocal 快路径 + ConcurrentHashMap，减少重复解析
2. `SimpleDateFormat` ThreadLocal 化：消除线程安全隐患，去掉每次无意义的 timeZone 刷新
3. 锁分离：`mainLock` / `blobLock` / `logcatLock` 三锁独立，避免 blob 大对象阻塞普通日志
4. `saveBlob()` md5 外移：大对象在 `blobLock` 外算完 md5 再进锁，缩短锁持有时间
5. logcat 采集去耦合：不再与 `log()`/`saveBlob()` 抢同一把锁
6. NTP 回拨保护：时间回拨时不触发归档，防止日志被埋进错误历史目录

## 已完成的改动

### 1. Utils.kt

- `dayFmt` / `timeFmt` 改为 `ThreadLocal<SimpleDateFormat>`
- 删除每次 `format` 前的 `timeZone = zone` 刷新
- `today()` / `now()` 直接返回 `ThreadLocal.get()!!.format(Date())`

### 2. ALog.kt

- 新增 `tagCache: ConcurrentHashMap<String, String>` 和 `lastCaller: ThreadLocal<Pair<String, String>?>`
- `autoTag()` 先走 ThreadLocal 快路径（同一线程连续同一个调用类命中），未命中再走 `ConcurrentHashMap.getOrPut`
- 保留 `Throwable().stackTrace`（无法避免），但大幅降低了后续重复解析开销

### 3. LogCenter.kt

- 新增 `mainLock`、`blobLock`、`logcatLock` 三把独立锁
- `log()` / `logLong()`：文件写入在 `mainLock` 内，logcat 输出移到锁外
- `saveBlob()`：`md5(bytes)` 移到 `blobLock` 外计算，锁内只做目录创建和文件写入
- `saveBlobString()` / `openTextBlobStream()`：改用 `blobLock`
- `dumpMeminfo()` / `zipDay()`：改用 `synchronized(mainLock)`
- `rolloverIfNeeded()`：内部用 `synchronized(mainLock)` 包裹全部逻辑；增加 `today < currentDay` 的 NTP 回拨保护分支；`logcatSink` 的关闭/重建由 `synchronized(logcatLock)` 保护
- logcat 采集线程：`synchronized(this)` 拆分为 `synchronized(mainLock) { rolloverIfNeeded() }` + `synchronized(logcatLock) { 写 sink }`

## 验证状态

| 检查项 | 状态 | 证据 |
|--------|------|------|
| 代码编译 | ✅ 通过 | `./gradlew :alogx:compileDebugKotlin` BUILD SUCCESSFUL，0 warning |
| 对外 API | ✅ 零变更 | 所有 public 方法签名保持原样 |
| 锁顺序安全性 | ✅ 无死锁 | blobLock → mainLock → logcatLock，无反向路径 |

## 阻塞项

无。

## 下一步

1. 如需运行单元测试或集成验证，继续执行
2. 如需调整阈值或进一步优化 `autoTag()` 的堆栈获取开销，可再评估
