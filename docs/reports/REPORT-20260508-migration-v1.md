# REPORT-20260508-migration-v1

## 背景

按 AGENTS.md 规范要求，对 ALogX 项目进行三项迁移，消除技术债并建立文档体系。

## 范围

1. 构建配置：`kotlinOptions.jvmTarget` 弃用 → `kotlin.compilerOptions` DSL
2. 文档结构：新建 `docs/` 目录体系
3. 代码：`LogConfig.logcatCmd` `Array<String>` → `List<String>`

## 操作步骤

### 1. 构建配置迁移

文件：`alogx/build.gradle.kts`、`app/build.gradle.kts`

修改前：
```kotlin
kotlinOptions {
    jvmTarget = "11"
}
```

修改后：
```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
```

### 2. LogConfig 类型迁移

文件：`alogx/src/main/java/com/sik/alogx/LogConfig.kt`

修改前：
```kotlin
val logcatCmd: Array<String> = arrayOf("logcat", "-v", "time"),
```

修改后：
```kotlin
val logcatCmd: List<String> = listOf("logcat", "-v", "time"),
```

适配调用方：
- `LogCenter.kt`：`exec(config.logcatCmd)` → `exec(config.logcatCmd.toTypedArray())`
- `README.md`：示例 `arrayOf(...)` → `listOf(...)`

### 3. 文档体系搭建

新建目录与文件：
```
docs/
├── agent-context.md
├── guides/
│   └── GUIDE-20260508-system-overview-v1.md
├── plans/
├── progress/
│   └── PROGRESS-20260508-migration-v1.md
├── reports/
│   └── REPORT-20260508-migration-v1.md
├── test-data/
├── templates/
└── archive/
```

## 结论

- 弃用警告已消除
- `LogConfig` 不再使用 `Array<String>`，避免 data class equals 行为异常
- 文档体系已建立，后续可按规范继续填充

## 验证

构建命令：
```bash
export ANDROID_HOME=/usr/local/project/Android/sdk
export ANDROID_NDK_HOME=/usr/local/project/Android/android-ndk-r27c
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
./gradlew build
```

预期结果：`BUILD SUCCESSFUL`

## 剩余风险

- `List<String>` 修改是破坏性变更（二进制不兼容）。如果外部用户直接以 `arrayOf(...)` 传入，需要改为 `listOf(...)`。已在 README 中更新示例。

## 后续动作

- 构建验证通过后可继续处理：Publication 配置警告、namespace 冲突、单元测试补充
