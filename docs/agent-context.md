# Agent Context — ALogX

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
