# IRISMusic 编译指南（设备端 Gradle 出包）

> 本项目的 APK 是在**手机本地的 Ubuntu 终端**里用 Gradle 命令行直接编译的，不用 Android Studio、不用 Gradle Wrapper。
> 本文件写给其它项目的 AI 复用：照「环境与命令」执行即可；「本机特有的坑」是必须复制的配置，否则 aarch64 设备上编译必挂。

## 环境与命令

### 1. 执行环境
- 终端 = `super_admin:terminal`（手机内的 Ubuntu 24 / proot 会话，已挂载 `/sdcard`、`/storage/emulated/0`，可直接读写工作区源码，无需复制）。
- 直接 `cd` 到工作区根目录操作。

### 2. 工具链（均装在 `/opt`，非 wrapper）

| 组件 | 位置 / 版本 | 说明 |
|---|---|---|
| Gradle | `/opt/gradle-8.7/bin/gradle` | 用系统安装的 Gradle，**不用** `./gradlew` |
| 跑 Gradle 的 JDK | `/opt/jdk-21.0.12.1+1` | 由 `gradle.properties` 的 `org.gradle.java.home` 强制指定，与系统默认 java 无关 |
| 语言级别 JDK | 17 | `build.gradle.kts` 里 `sourceCompatibility/jvmTarget = 17` |
| Android SDK | `/usr/lib/android-sdk` | 由 `local.properties` 的 `sdk.dir` 指定（环境变量 `ANDROID_HOME` 为空） |

### 3. 编译命令

```bash
cd <项目根目录>

# 仅编译 Kotlin（快速验证能否通过，不出包）
/opt/gradle-8.7/bin/gradle :app:compileReleaseKotlin --console=plain

# 出 Release APK
/opt/gradle-8.7/bin/gradle :app:assembleRelease --console=plain
```

- 务必显式传长超时：`compileReleaseKotlin` 给 ~300000ms，`assembleRelease` 给 ~420000ms（首次含依赖下载会更久）。
- **不要用 `set -e`**：该终端会话下会改变 shell 退出行为导致卡死。
- 命令超时会取消当前命令但保留会话，可修正后重跑。

### 4. 产物

```
app/build/outputs/apk/release/app-arm64-v8a-release.apk   ← 真机装这份
app/build/outputs/apk/release/app-armeabi-v7a-release.apk
```

按 ABI 拆分（`splits.abi`，`isUniversalApk=false`），所以出多份；arm64 设备装 `arm64-v8a` 那份。装机后可 `md5sum` 与发布产物比对确保一致。

## 本机特有的坑（`gradle.properties` 必须照抄）

```properties
# aarch64 设备：AGP 默认从 Maven 拉的 aapt2 是 x86_64，跑不起来 → 覆盖为系统 ARM 原生版
android.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
# 用完整 JDK（带 jlink）跑 Gradle，否则开启 buildConfig 后 Release 的 jdkImage 转换会失败
org.gradle.java.home=/opt/jdk-21.0.12.1+1
```

另有 `local.properties`：

```properties
sdk.dir=/usr/lib/android-sdk
```

要点：
- **aapt2 覆盖**是 aarch64 手机上编译 Android 的核心障碍——不覆盖，AGP 调用的 aapt2 是 x86 架构，链接期直接报错。
- **JDK 路径**写死完整 JDK 而非 `/usr/lib/jvm/...` 精简版，否则 `buildFeatures.buildConfig=true` 时 jlink 相关任务失败。
- **SDK 路径**走 `local.properties`，不依赖环境变量。

## 依赖镜像（`settings.gradle.kts`）

国内网络下仓库前置阿里云镜像（google / public / gradle-plugin 三个），再回落 `google()` / `mavenCentral()`，否则首次拉依赖极慢或超时。