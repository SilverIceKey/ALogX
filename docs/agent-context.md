# Agent Context — ALogX

## 最新交接（2026-06-05）

- 当前主任务：修复 Android 应用在未授予外部存储权限时，ALogX 初始化或首次写 `main.log` 抛 `FileNotFoundException` 并拖崩调用方的问题。
- 最新结论：根因是 ALogX 默认只使用 `/sdcard/{appName}/logs`，且 `rolloverIfNeeded()` 打开 `main.log`、`logcat.log` 的异常未被顶层兜住；调用方即使在业务层做路径判断，一旦公共目录不可写或判断误差，框架仍可能同步抛异常。
- 本轮修复：`LogCenter.init()` 会先尝试公共目录，再降级到 app-specific 外部目录，最后降级到 internal files 目录；所有日志写入入口统一走 `safeRolloverIfNeeded()`，目录或 sink 打开失败只关闭文件日志并写 Android Log，不再抛给业务方；`openTextBlobStream()` 和 `saveBlobString()` 也补齐 sink 创建异常保护。
- 当前版本：`gradle.properties` 的 `VERSION` 调整为 `1.0.11`，用于 NewSelfOpenCard 通过 `mavenLocal()` 验证修复版。
- 当前验证：`./gradlew :alogx:assembleRelease publishToMavenLocal` 通过并发布 Maven Local；NewSelfOpenCard 已切到 `com.github.SilverIceKey:ALogX:1.0.11`，`dependencyInsight` 确认解析到 `1.0.11`，局部编译和全机型 `compileReleaseKotlin` 均通过。
- 未完成验证：未做 Android 9 真机新装未授权回放；需要确认未授权启动不崩、授权后公共目录日志可生成。GitHub tag `1.0.11` 已推送，CI 或其他开发机首次解析时仍需确认依赖源已完成构建缓存。

## 当前主任务

完成三项迁移：
1. 构建配置：`kotlinOptions.jvmTarget` → `kotlin.compilerOptions` DSL
2. 文档结构：按 AGENTS.md 规范搭建 `docs/` 目录体系
3. 代码层面：`LogConfig.logcatCmd` 从 `Array<String>` 迁移为 `List<String>`

## 当前阶段

迁移已完成，待构建验证通过。

## 固定入口

| 入口 | 路径 | 说明 |
|------|------|------|
| 项目根 | `/usr/local/project/github/ALogX` | Android 日志库 |
| 核心库 | `alogx/src/main/java/com/sik/alogx/` | ALog、LogCenter、LogConfig 等 |
| Demo App | `app/src/main/java/com/sik/alogx/` | MainActivity、App |
| 构建脚本 | `alogx/build.gradle.kts` / `app/build.gradle.kts` | AGP 8.13.1, Kotlin 2.2.20 |
| 系统概览 | `docs/guides/GUIDE-20260508-system-overview-v1.md` | 能力总览 |
| 最新进度 | `docs/progress/PROGRESS-20260508-migration-v1.md` | 本轮进度 |
| 迁移报告 | `docs/reports/REPORT-20260508-migration-v1.md` | 本轮报告 |

## 最近关键结论

- Android SDK 36 / build-tools 36.0.0 已补装到 `/usr/local/project/Android/sdk`
- 项目 `./gradlew build` 已验证通过（198 tasks）
- `LogConfig` 不再使用 `Array<String>`，避免 data class equals 陷阱
- 构建弃用警告已清理（`jvmTarget` → `compilerOptions`）
- `LogConfig.logcatCmd` 默认值从 `threadtime` 改为 `time`

## 环境配置

```bash
export ANDROID_HOME=/usr/local/project/Android/sdk
export ANDROID_NDK_HOME=/usr/local/project/Android/android-ndk-r27c
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

## 下一步

1. 构建验证通过 → 本轮结束
2. 用户确认后续需求（如 Publication 配置、namespace 冲突、单元测试补充等）
