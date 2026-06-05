# REPORT-20260605-storage-permission-fallback-v1

## 背景

NewSelfOpenCard 现场设备在新装后尚未完成存储授权，应用启动早期调用 ALogX 写 `/storage/emulated/0/selfOpenCard/logs/main.log`，出现 `FileNotFoundException` 并导致 `Application.onCreate()` 崩溃。

## 范围

- 框架模块：`alogx/src/main/java/com/sik/alogx/LogCenter.kt`。
- 版本配置：`gradle.properties`。
- 文档：`docs/agent-context.md`、本进度与报告文档。

## 操作

- 将 ALogX 初始化目录选择改为公共外部目录优先，失败时自动降级到 app-specific 外部目录，再失败时降级到 internal files 目录。
- 初始化前关闭旧 sink，避免重复 init 后仍保留旧不可写 sink。
- 所有文件日志写入入口改为先执行安全 rollover，打开文件失败时只关闭文件日志，不再抛给业务方。
- 对 `saveBlobString()`、`openTextBlobStream()` 的 sink 创建阶段补齐异常保护。

## 结论

根因在框架层：文件日志目录不可写时，ALogX 不应让日志 IO 异常穿透到业务应用。修复后，权限未授予、公共目录缺失或不可写时，框架会降级日志目录或关闭文件日志，业务调用只会损失文件日志，不会因日志系统崩溃。

## 证据

- ALogX 构建发布：`./gradlew :alogx:assembleRelease publishToMavenLocal` 通过，`BUILD SUCCESSFUL in 33s`。
- ALogX 远端发布：GitHub tag `1.0.11` 已推送，指向修复提交 `8e5ae7e`。
- NewSelfOpenCard 依赖解析：`./gradlew :core:shared:dependencyInsight --configuration releaseRuntimeClasspath --dependency ALogX` 显示 `com.github.SilverIceKey:ALogX:1.0.11`。
- NewSelfOpenCard 局部编译：`./gradlew :core:shared:compileReleaseKotlin :core:app_base:compileReleaseKotlin :apps:d2:compileReleaseKotlin` 通过，`BUILD SUCCESSFUL in 40s`。
- NewSelfOpenCard 全机型编译：`./gradlew :apps:d2:compileReleaseKotlin :apps:f156:compileReleaseKotlin :apps:k115:compileReleaseKotlin :apps:k8:compileReleaseKotlin :apps:m9:compileReleaseKotlin :apps:mlahh1:compileReleaseKotlin :apps:p15:compileReleaseKotlin :apps:y9:compileReleaseKotlin :apps:tb980:compileReleaseKotlin` 通过，`BUILD SUCCESSFUL in 22s`。

## 剩余风险

- 未做 Android 9 真机未授权回放。
- 降级目录中的早期日志不会自动合并到公共目录。
- GitHub tag `1.0.11` 已推送；其他机器或 CI 首次解析时仍需确认依赖源已完成构建缓存。

## 后续动作

- 真机验证新装未授权启动、授权后公共日志生成、日志打包上传。
- 关注调用方依赖源首轮构建结果，确认 `com.github.SilverIceKey:ALogX:1.0.11` 可在非本机环境解析。
