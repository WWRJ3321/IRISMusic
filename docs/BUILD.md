# IRISMusic 编译指南（设备端出包）

> 本项目的 APK 在**手机本地的 Ubuntu（proot）终端**里用 Gradle 编译，不用 Android Studio。
> 「本机特有的坑」一节是必须照抄的配置，否则在 aarch64 设备上编译必挂。
>
> 最后验证：2026-10-04，v4.5.1 构建通过。

## 1. 执行环境

- 终端 = `super_admin:terminal`（Ubuntu 24 / proot，已挂载 `/sdcard`、`/storage/emulated/0`）。
- 工作区可直接读写，**无需复制到 Linux 家目录**。
- 项目根目录：`/sdcard/Download/工作区/IRISMusic`

## 2. 工具链（装在 `/opt`）

| 组件 | 位置 | 说明 |
|---|---|---|
| 构建入口 | `sh gradlew` | **用项目自带 wrapper**（`gradle/wrapper/` 已入库） |
| 备用 Gradle | `/opt/gradle-8.7/bin/gradle` | wrapper 失效时的回退 |
| 跑 Gradle 的 JDK | `/opt/jdk-17.0.20.1+1` | 由 `gradle.properties` 的 `org.gradle.java.home` 强制指定 |
| 语言级别 | JDK 17 | `build.gradle.kts` 里 `sourceCompatibility` / `jvmTarget = 17` |
| aapt2（ARM 原生） | `/opt/aapt2-fix/aapt2` | QEMU 兼容包装器，**必须**覆盖 |
| Android SDK | `/usr/lib/android-sdk` | 由 `local.properties` 的 `sdk.dir` 指定 |

> ⚠️ **工具链路径会因清理系统而失效**。编译前先 `ls` 验证上表路径是否存在；
> 若 JDK / aapt2 被删，照第 5 节重建。**不要清理 `/opt` 和 SDK 的 build-tools**。

## 3. 编译命令

```bash
cd /sdcard/Download/工作区/IRISMusic

# 快速语法验证（不出包）
sh gradlew compileReleaseKotlin 2>&1 | tail -20

# 出 Release APK
sh gradlew assembleRelease -x lint 2>&1 | tail -5
```

- 显式传长超时：`compileReleaseKotlin` ≈ 300000ms，`assembleRelease` ≈ 1200000ms。
- **不要用 `set -e`**：该终端会话下会改变 shell 退出行为导致卡死。
- 超时只取消命令、保留会话，可修正后重跑。

## 4. 产物与归档

```
app/build/outputs/apk/release/app-arm64-v8a-release.apk     ← 真机装这份
app/build/outputs/apk/release/app-armeabi-v7a-release.apk
```

按 ABI 拆分（`splits.abi`，`isUniversalApk=false`）。归档流程：

```bash
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk \
   hist/IRISMusic-v<版本号>-arm64-v8a.apk
sha256sum hist/IRISMusic-v<版本号>-arm64-v8a.apk
```

**归档文件名里的版本号必须与 `app/build.gradle.kts` 的 `versionName` 一致**（v4.5.1 曾因此错位）。

## 5. 本机特有的坑

### 5.1 `gradle.properties`（必须照抄）

```properties
# aarch64 设备：AGP 从 Maven 拉的 aapt2 是 x86_64，跑不起来 → 覆盖为 ARM 可用版
android.aapt2FromMavenOverride=/opt/aapt2-fix/aapt2
# 用完整 JDK（带 jlink）跑 Gradle，否则开启 buildConfig 后 Release 的 jdkImage 转换失败
org.gradle.java.home=/opt/jdk-17.0.20.1+1
```

### 5.2 `local.properties`（不入库，需手动创建）

```properties
sdk.dir=/usr/lib/android-sdk
```

被 `.gitignore` 排除，**从 git 克隆后必须手建**，否则报 SDK location not found。

### 5.3 签名配置（不入库，需手动准备）

```
keystore/iris-release.jks     ← 正式签名密钥
keystore.properties           ← 密钥别名与口令
```

两者都被 `.gitignore` 排除。**缺失时 Gradle 会回落到调试签名，导致装机报「签名不一致」**——
覆盖安装失败时，第一个要查的就是这里。验证签名是否为正式证书：

```bash
unzip -p <apk> META-INF/CERT.RSA | keytool -printcert | grep SHA256
# 正式证书指纹：1E:C1:BB:78:6C:FA:BB:89:CB:35:F3:9A:75:F6:4D:66:...
```

### 5.4 依赖镜像（`settings.gradle.kts`）

国内网络下前置阿里云镜像（google / public / gradle-plugin），再回落 `google()` / `mavenCentral()`，否则首次拉依赖极慢。

## 6. 常见故障速查

| 症状 | 原因 | 处理 |
|---|---|---|
| 装机报「签名不一致」 | `keystore/` 缺失，回落调试签名 | 补回 `keystore/iris-release.jks` + `keystore.properties` 重编 |
| `SDK location not found` | 没有 `local.properties` | 照 5.2 创建 |
| `Invalid or corrupt jarfile` / jdkImage 失败 | `org.gradle.java.home` 指向已删除的 JDK | `ls -d /opt/jdk*` 查实际版本并改 `gradle.properties` |
| aapt2 执行格式错误 | aapt2 覆盖路径失效 | `ls /opt/aapt2-fix/aapt2` 验证，缺失则重建包装器 |
| 终端卡死不返回 | 命令里用了 `set -e` | 去掉，重开会话 |
| `Unresolved reference: FontFamily` 一类 | 新增代码漏 import | 按报错补 import 后重编 |