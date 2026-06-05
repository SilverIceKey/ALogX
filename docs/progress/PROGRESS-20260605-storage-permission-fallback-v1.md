# PROGRESS-20260605-storage-permission-fallback-v1

## 当前状态

- 当前主任务：修复 ALogX 在调用方尚未授予外部存储权限时，首次写入 `/sdcard/{appName}/logs/main.log` 可能抛异常并导致调用方崩溃的问题。
- 当前阶段：框架源码修复已落盘，`1.0.11` 已发布到 Maven Local，并已推送 GitHub tag `1.0.11`，NewSelfOpenCard 联动编译验证通过。

## 根因判断

- ALogX 默认根目录固定为 `Environment.getExternalStorageDirectory()/{appName}/logs`。
- `LogCenter.init()` 在公共目录 `mkdirs()` 失败时会 return，但 `baseDir` 仍已初始化；后续任意 `ALog` 调用再次进入 `rolloverIfNeeded()` 时仍会尝试打开 `main.log`。
- `rolloverIfNeeded()` 内部对 `mainFile.sink()`、`logcatFile.sink()` 没有顶层异常兜底；一旦目录不可写，就会把 `FileNotFoundException` 同步抛回调用方。
- 调用方业务层路径判断只能降低概率，不能消除框架内部同类异常。

## 本轮修复范围

- `LogCenter.init()`：
  - 初始化前先 `shutdown()` 旧 sink，避免重复 init 后仍写旧目录。
  - 目录选择顺序调整为公共外部目录 -> app-specific 外部目录 -> internal files 目录。
  - 对候选目录做真实探测文件写入，确认可写后才启用文件日志。
- `LogCenter` 写入入口：
  - 新增 `safeRolloverIfNeeded()`，所有主日志、长日志、blob、logcat 采集和 meminfo 写入前统一检查。
  - sink 打开失败时关闭文件日志并记录 Android Log，不再向调用方抛异常。
  - `saveBlobString()`、`openTextBlobStream()` 补齐 sink 创建阶段异常保护。
- 版本：`VERSION` 调整为 `1.0.11`，用于调用方依赖修复版。

## 非本轮范围

- 不改变默认公共目录优先策略；有权限时仍优先写 `/sdcard/{appName}/logs`。
- 不修改 ALog/LogUtils 对外基础 API。
- 不搬迁降级目录里的早期日志。

## 验收方式

- ALogX：`./gradlew :alogx:assembleRelease publishToMavenLocal` 通过，`BUILD SUCCESSFUL in 33s`。
- NewSelfOpenCard：切换到 `com.github.SilverIceKey:ALogX:1.0.11` 后，`./gradlew :core:shared:dependencyInsight --configuration releaseRuntimeClasspath --dependency ALogX` 确认解析到 `1.0.11`。
- NewSelfOpenCard 局部编译：`./gradlew :core:shared:compileReleaseKotlin :core:app_base:compileReleaseKotlin :apps:d2:compileReleaseKotlin` 通过，`BUILD SUCCESSFUL in 40s`。
- NewSelfOpenCard 全机型编译：`./gradlew :apps:d2:compileReleaseKotlin :apps:f156:compileReleaseKotlin :apps:k115:compileReleaseKotlin :apps:k8:compileReleaseKotlin :apps:m9:compileReleaseKotlin :apps:mlahh1:compileReleaseKotlin :apps:p15:compileReleaseKotlin :apps:y9:compileReleaseKotlin :apps:tb980:compileReleaseKotlin` 通过，`BUILD SUCCESSFUL in 22s`。
- 真机：Android 9 新装未授权启动，应能进入权限申请页面；授权后公共目录日志仍能生成。

## 风险

- 未授权阶段若公共目录不可写，早期日志会落到 app-specific 或 internal files 目录；授权后再次 init 才会回到公共目录。
- GitHub tag `1.0.11` 已推送；调用方 CI 或其他开发机首次解析时仍需确认依赖源已完成构建缓存。
