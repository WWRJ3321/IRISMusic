# IRISMusic 架构说明

> 写给未来的自己和 AI 协作者：改代码前先读这份，避免走弯路。
>
> 对应版本：v4.5.1 · 最后更新：2026-10-04 · 规模：51 个 Kotlin 文件 / ~24000 行

## 1. 技术栈与总体形态

| 项 | 选型 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose（纯 Compose，无 XML 布局） |
| 播放内核 | AndroidX Media3（ExoPlayer + MediaSession） |
| 架构 | 单 Activity + 单 ViewModel + 单一状态流（**非** MVI/Hilt/Room） |
| 持久化 | SharedPreferences（设置项）+ JSON 文件（歌单/历史/统计） |
| 网络 | 无后端。唯一出站是可关闭的版本检查 |
| ABI | 仅 `arm64-v8a` / `armeabi-v7a`，按 ABI 拆包 |

**刻意的简单**：没有 DI 框架、没有 Room、没有多模块。全局状态集中在一个 `PlayerUiState` data class，
UI 全部是它的纯函数投影。这让 24K 行代码仍能靠「单点读写」保持可控。

## 2. 分层与数据流

```
MainActivity                         ← 单 Activity，持有 ViewModel，提供 Theme
    ↓ collectAsState()
PlayerViewModel  ──  PlayerUiState   ← 唯一真相源（StateFlow）
    │                                  157 个持久化键，全部存 SharedPreferences
    ├─ data/        音乐库 / 歌单 / 歌词 / 统计 / 推荐
    ├─ audio/       均衡器 / 虚拟低音 / 空间声场 / 限幅 / 频谱 / 震动
    ├─ playback/    MusicService（MediaSession 前台服务）、桌面歌词
    └─ ui/          Compose 界面，**只读 state + 回调 ViewModel**
```

**铁律：UI 层不持有业务状态。** 所有设置项的改动都走
`ui → onXxxChange 回调 → viewModel.setXxx() → prefs 落盘 + state.copy() → UI 重组`。
这条链路任一环漏接，设置就会「看起来能点但没效果」或「重启后丢失」。

### 典型故障模式（都踩过）

| 症状 | 真实原因 |
|---|---|
| 设置项点了没反应 | 渲染层硬编码了值，没读 state（如歌词非高亮行曾硬编码 20sp） |
| 设置项重启后丢失 | ViewModel 初始化时漏读 prefs（`uiFont` 曾漏读，v4.5.1 修复） |
| 设置项部分生效 | 多个渲染分支，只改了其中一个（逐字流光 / 普通行两条分支） |

> **新增设置项检查清单**：
> 1. `PlayerUiState` 加字段 → 2. `companion object` 加 `KEY_` → 3. **初始化块 `prefs.getXxx` 读取**
> → 4. `setXxx()` 写盘 + copy state → 5. `SettingsScreen` 加控件 → 6. `MainScreen` 传回调
> → 7. **搜索所有渲染分支**确认都接了参数。漏第 3 步 = 重启丢失，漏第 7 步 = 部分不生效。

## 3. 核心模块

### 3.1 `player/PlayerViewModel.kt`（1598 行，全局中枢）

- 持有 `MediaController` 与 Media3 会话连接
- `PlayerUiState`：队列、播放态、全部外观设置、点赞、歌单、推荐、统计
- 157 个 `KEY_` 常量集中在底部 `companion object`
- **这是最大的单点风险文件**。改它前先 `grep` 清楚目标字段的完整链路。

### 3.2 `ui/theme/`（视觉系统核心）

| 文件 | 职责 |
|---|---|
| `Theme.kt` | 组合入口，接收 theme/mode/cornerBase/fontScale/surfaceStyle/glassBlur/**uiFont** |
| `Color.kt` | 8 套配色 × 明暗，含 WCAG 对比度自动校正 |
| `Type.kt` | `IrisTypography` / `IrisSerifTypography`，由 `irisTypographyOf(uiFont)` 工厂切换 |
| `LiquidGlass.kt` | AGSL SDF 位移取样折射，五级厚度 |
| `GlassBackdrop.kt` | 背景封面模糊与染色压制 |

**全局字体的实现要点**：App 内有 200+ 处硬编码 `fontSize`，逐个改不现实。
做法是在 `Theme.kt` 用 `LocalTextStyle` 下发 `fontFamily` —— 单点注入，全局生效，
且不干扰各处自定的字号。**以后任何"全局文字属性"都走这条通道，不要去改调用点。**

### 3.3 `ui/MainScreen.kt`（2073 行，最复杂的 UI）

- 四种排布的调度：纵向列表 / 横向卡片 / 堆叠卡片 / 海报墙
- `LyricsOverlay` 全屏歌词 + `KaraokeLine` 逐字流光
- `SettingsPanel` 的回调汇聚点
- **歌词有两条渲染分支**（逐字流光开 / 关），改歌词外观必须两边都改。

### 3.4 `audio/`（12 个文件，DSP 链）

`EqualizerController`（10 段 EQ）、`VirtualBass`、`SpatialWide`、`VirtualSurround`、
`SafeLimiter`（防爆音）、`TrackGain`（增益归一）、`FadeController`、`SilenceSkipper`、
`SpectrumAnalyzer`（频谱可视化）、`BassHaptics`（低频震动）。

各自独立、可单独开关，通过 ViewModel 统一挂到播放链上。

### 3.5 `data/`

`MusicRepository`（MediaStore 扫描）、`LyricParser`（LRC 含逐字时间轴）、
`Playlists` / `PlayHistory` / `ListenStats`（JSON 落盘）、
`Recommender`（本地加权推荐）、`DataTransfer`（导入导出）、`UpdateChecker`（唯一联网点）。

## 4. 仓库结构与边界

```
app/                 源码（入 git）
docs/                文档
  ├─ ARCHITECTURE.md  本文件
  ├─ BUILD.md         编译指南（含故障速查）
  ├─ IRISMusic.md     视觉风格规范
  ├─ DEVNOTES.md      版本日志（不入 git，仅本地）
  └─ rules_note.md    私密凭据（不入 git，仅本地）
design/              Logo 素材（入 git）
publicity/           宣传视频（13MB，不入 git）
sample/              示例音频
hist/                历史 APK 与归档 zip（626MB，不入 git）
keystore/            签名密钥（不入 git）
```

### 不入 git 的东西（`.gitignore` 已配置）

| 路径 | 原因 |
|---|---|
| `hist/` | 体积巨大的历史归档，进 git 会永久膨胀仓库 |
| `keystore/`、`keystore.properties` | 签名密钥，绝不入库 |
| `local.properties` | 机器相关的 SDK 路径 |
| `docs/rules_note.md`、`docs/DEVNOTES.md` | 本地私有笔记 |
| `publicity/*.mp4` | 宣传视频素材 |

> 提交前自查一次大文件与凭据：`git diff --cached --name-only | grep -iE 'apk|zip|mp4|mp3|jks'` 应为空。

## 5. 历史教训（真实事故）

### 5.1 源码丢失（2026-10-04）

v4.5.1 的功能代码**只改在磁盘上、从未 commit**。清理工作区时 47 个 Kotlin 文件被删，
git 里最新只有 3.9.1，GitHub 最新只有 4.5.0 —— 源码彻底丢失，只剩已编译的 APK。

恢复方式：从 GitHub 拉 v4.5.0 源码，在其上手工重写 4.5.1 的全部改动。

**定下的规矩：出包即提交。** 每次 `assembleRelease` 成功归档后立刻 `git commit`，
不要积累未提交的改动。开发中途也至少每天一次 WIP 提交。

### 5.2 版本号与文件名错位

曾出现文件名 `v4.5.2` 而 `versionName` 仍是 `4.5.1`。归档时文件名必须从
`app/build.gradle.kts` 抄，不要手打。

### 5.3 签名回落导致装不上

`keystore/` 不在 git 里，换目录编译时缺失 → Gradle 静默回落调试签名 → 覆盖安装报
「签名不一致」。编译新环境前先确认 `keystore/` 与 `keystore.properties` 到位。

### 5.4 工具链被清理

`/opt/jdk-21`、SDK 的 `build-tools/debian/aapt2` 曾被清空间时删除，构建直接挂。
现用 `/opt/jdk-17.0.20.1+1` 与 `/opt/aapt2-fix/aapt2`。**不要清理 `/opt`。**

### 5.5 内置字体的体积代价

为做出中文字体差异曾内置霞鹜文楷 GB2312 子集，APK 从 2.4MB 涨到 6.7MB（+175%）被否决。
最终方案：只提供「系统 / 衬线」，靠 Android 自带 Noto Serif CJK 实现差异，零体积成本。

> Android 上 `FontFamily.Monospace` 对中文会回退到系统黑体，与 `SansSerif` 渲染完全一致
> —— 做中文字体选项时，**等宽/无衬线这类选项是无效的摆设**，只有 Serif 真正有区别。

## 6. 已知债务（按优先级）

| 优先级 | 事项 | 说明 |
|---|---|---|
| P0 | **GitHub 同步** | 发布后确认 `main` 与 tag 已更新（本次 v4.5.1 已同步） |
| — | ~~`SettingsPanel` 44 个参数~~ | **已完成**：面板直接持有 `viewModel`，签名从 44 个回调压到 6 个参数。以后加设置项只改 `SettingsScreen.kt` 一处。 |
| P1 | 无自动化测试 | `app/src/test` 与 `androidTest` 均不存在，24K 行零测试。建议先给 `LyricParser`（776 行、逻辑最纯、历史 bug 最多）补单测 |
| P2 | `MainScreen.kt` 2073 行 | `LibraryPage` 单函数 597 行、`MainScreen` 453 行。建议按四种排布拆文件 |
| P2 | `PlayerViewModel` 1598 行 / 157 键 | 建议把外观设置拆成独立 `AppearanceSettings` 数据类 |
| P2 | 大库性能未实测 | 5000+ 曲目的扫描耗时与滚动帧率无数据 |
| P3 | 国际化不完整 | 有 `values/strings.xml` 但 UI 文案大量直接硬编码中文，`resourceConfigurations` 只留 en/zh |
| P3 | `INTERNET` 权限 | 为版本检查引入，削弱了「无网络」卖点，可考虑移除改走 F-Droid |

### 已具备（不是债务）

- **CI 已存在**：`.github/workflows/build.yml`，tag 推送触发，含三项有价值的设计——
  自动剥离设备专属的 `gradle.properties` 覆盖项、**manifest 权限白名单审计**（新增权限会让 CI 失败）、
  产出 SHA256SUMS 作为可复现构建证据。CI 用临时 debug 签名，不作为发布包。
- **代码卫生良好**：零 TODO/FIXME、零 `println` 调试残留、零空 `catch` 块、`@Suppress` 仅 10 处。
- **ProGuard 规则**：`app/proguard-rules.pro` 存在，R8 全模式已开。

## 7. 改代码前的自检

1. **先 grep 再改**：`grep_code` 搜字段名，确认完整链路（state → prefs → setter → UI → 所有渲染分支）。
2. **编译即验证**：`sh gradlew compileReleaseKotlin` 比直接出包快，先过语法。
3. **出包即提交**：参考 5.1，这是血的教训。
4. **改视觉先读 `IRISMusic.md`**：那里有不泛白、无水波、正交解耦等铁律。
5. **别动 `/opt`**：工具链在那里，删了就得重建。