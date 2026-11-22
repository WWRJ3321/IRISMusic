# IRIS Music 代码评审

> 评审对象：`WWRJ3321/IRISMusic` @ `main`（v4.9.5，commit `5f56caa`）
> 规模：56 个 Kotlin 文件 / 27,278 行；单 Activity + 单 ViewModel + 单一 StateFlow
> 方式：通读构建配置、Manifest、CI、`data/`、`audio/`、`playback/`、核心 UI，核对 GitHub Actions 运行记录与 Releases

---

## 总评

这是我近期读过的**个人 Android 项目里注释质量最高的一份**。`MusicService` 里那段 DSP 链顺序的理由（为什么 TrackGain 在最前、SafeLimiter 在 EQ 之后）、`ListenStats` 为什么用追加式日志而不是 prefs、`MusicRepository` 为什么绕开 MediaStore——这些"记录动机"的注释，比 90% 的商业项目都值钱。

但它有一个统一的模式性问题：**声明跑在实现前面，而且没人校验这个差**。

- README 说"默认不联网、数据只在本机"——Manifest 申请了 `MANAGE_EXTERNAL_STORAGE`（全盘文件访问）。
- `ARCHITECTURE.md` 把"CI 已存在"列为资产——CI 最近 5 次全部失败。
- `REMIND.md` 要求分发时保留"源文件顶部的 GPL 头部注释"——56 个文件里 3 个有。
- README 说推荐算法基于"风格权重"——`Song` 里没有 genre 字段，算法也没有风格维度。

代码本身不差，差的是**闭环**：写了审计脚本却审计错了对象，写了架构文档却没有机制让它跟着代码走，写了 CI 却从没绿过。

### 分项评分

| 维度 | 分数 | 一句话 |
|---|:--:|---|
| 架构清晰度 | 8 | 分层干净、单向数据流贯彻到底，代价是 god state |
| 音频 / DSP | 8 | 自研 EQ、限幅、LUFS、频谱旁路，链序有据可查 |
| 数据层健壮性 | 5 | 歌单名会破坏存储、导入无校验、扫描一处失败全盘失败 |
| 性能（大库 / 列表） | 5 | 87 字段状态 2Hz 全量 copy、排序每首一次 stat、item 里查数据库 |
| 工程化（CI/测试） | 3 | 27K 行零测试；CI 连红 5 次无人发现 |
| 文档 | 9 | 内容极好，但已落后代码 4 个版本、且三处与事实不符 |
| 无障碍 | 2 | 78 处 `clickable`，0 处 `semantics` |
| 安全 / 隐私模型 | 5 | 代码干净，权限模型却在拆自己的台 |

---

## 一、P0：先修这五个

### 1. CI 从来没绿过，而且失败得很安静

查 GitHub Actions：仓库共 48 次运行记录，**最近 5 次（v4.8.1 → v4.9.5）全部 failure**，且都卡在 `Build release APK` 这一步——整份 job 只跑了 **13 秒**，说明根本没进到编译，是 Gradle 启动阶段就挂了。

对照 `gradle.properties`，原因几乎是明摆着的：

```properties
android.aapt2FromMavenOverride=/opt/aapt2-fix/aapt2
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-arm64
```

这两行是**作者本机（aarch64 + proot）的私货**。`ubuntu-latest` 是 x86_64，既没有 `/opt/aapt2-fix/aapt2`，也没有 `openjdk-arm64` 这个目录，Gradle 拿到无效 `java.home` 会立刻退出——13 秒完全对得上。（另一种可能是 Gradle 发行版下载超时，`networkTimeout=10000` 也刚好是 10 秒级；无论哪种，下面第 1 步的修法都适用。）

`ARCHITECTURE.md` 第 174 行写着"CI … 自动剥离设备专属的 `gradle.properties` 覆盖项"，但 `build.yml` 里只有一句 `cat gradle.properties`，**剥离逻辑根本不存在**。文档描述的是一个想象中的 CI。

顺带一提，v4.9.5 的 release notes 自己写了"构建环境：JDK 统一指向系统 OpenJDK 17（/usr/lib/jvm/java-17-openjdk-arm64）"——这就是把本机路径写进版本库的直接证据。

**修法**

1. 把机器相关配置从版本库里的 `gradle.properties` 删掉，改放 `~/.gradle/gradle.properties`（作者本机）或用命令行 `-P` 传；`local.properties` 已在 `.gitignore` 里，也可以放那儿。
2. CI 加一步断言，让"绿"有意义：
   ```yaml
   - name: Verify APK
     run: |
       ls -l app/build/outputs/apk/release/*.apk
       unzip -l app/build/outputs/apk/release/*.apk | grep -q classes.dex
   ```
3. 顺手在 CI 里跑 `./gradlew :app:lintDebug testDebugUnitTest`——现在这两样一个都没有。
4. Actions 版本也旧了：日志里已有 `actions/checkout@v4`、`actions/setup-java@v4` 的 Node 20 弃用警告，且 `ubuntu-latest` 将于 2026-10-19 迁到 Ubuntu 26。建议升到 `@v5`，并给第三方 action 锁 commit SHA（`softprops/action-gh-release` 是写权限，值得锁）。

> 一个更根本的判断：这份 CI **不产出 APK**（只上传 `SHA256SUMS.txt`），发布靠作者本机打包。也就是说 CI 现在唯一的作用是权限审计——而权限审计的"允许清单"里恰好包含 `MANAGE_EXTERNAL_STORAGE`。**唯一在跑的把关环节，把最该拦的东西放行了。**

---

### 2. 歌单名会破坏存储，甚至能改写别的歌单

`data/Playlists.kt` 用字符串拼装持久化：

```kotlin
// 写
private fun writeNames(map: Map<Long, String>) {
    val raw = map.entries.joinToString(";") { "${it.key}:${it.value}" }
    prefs.edit().putString(KEY_NAMES, raw).apply()
}
// 读（164 行）
val kv = part.split(':')
val name = kv.getOrNull(1) ?: continue
```

输入框（`PlaylistSheets.kt:218`）对名字**没有任何过滤**。于是：

| 用户输入 | 写入 | 读回 |
|---|---|---|
| `Live: 2024` | `1:Live: 2024` | `Live` ← 冒号后被截断 |
| `A;B` | `1:A;B` | `A` ← 后半段变成孤儿被丢弃 |
| `x;3:Pwned` | `1:x;3:Pwned` | `{1:"x", 3:"Pwned"}` ← **歌单 3 被改名** |

第三行不是理论：`DataTransfer.import()` 会把外部 JSON 里的歌单名原样喂给 `Playlists.create()`，一个手工改过的导出文件就能改写用户自己的歌单集合。

**修法**（10 分钟）：整份 names 用 `JSONArray` 存，或至少 `URLEncoder.encode(name, "UTF-8")`。歌单量本来就小，JSON 的开销可以忽略。同时给 `TextField` 加长度上限（现在没有）。

---

### 3. 响度均衡在**音频线程**写 SharedPreferences

`audio/TrackGain.kt`：

```
queueInput()            // ← 音频渲染线程
  └─ commitMeasurement()      // 277 行
       └─ putLufs()           // 322 行
            ├─ store.toMutableMap()   // 复制 400 条
            ├─ encodeStore(m)         // 拼字符串
            └─ prefs.edit().putString(...).apply()   // 373 行
```

`apply()` 虽然异步落盘，但**序列化、Editor 创建、拿到 prefs 锁**全在这条调用栈上。音频线程每首歌定稿时（约第 10~30 秒）会做一次几百 KB 的分配 + 一次字符串编码。这正是爆音/卡顿最喜欢出现的位置——而"爆音"是这个项目花了 `FadeController`、`SafeLimiter` 两个文件去消灭的东西。

更隐蔽的是 `@Synchronized private fun putLufs` 与主线程的 `setCurrentSong()` 共享同一把锁：**音频线程等主线程的锁 = 优先级反转**，主线程当时要是在做 Compose 排版，音频线程就得陪它排队。

**修法**：`commitMeasurement()` 只算出 `(path, lufs, peak)`，`handler.post { putLufs(...) }`，用一个专用单线程 executor 落盘。

---

### 4. `MANAGE_EXTERNAL_STORAGE` 与"隐私优先"的卖点互斥

Manifest 里：

```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

`MainActivity.requestNeededPermissions()` 在**首次冷启动**就把用户踢到系统设置页去开这个权限（只在首次，这点比之前好）。

三个问题叠在一起：

1. **商店政策**：Google Play 对 Android 11+ 的"所有文件访问"采用白名单制，只给文件管理器、备份、杀毒、文档管理这类核心用途。音乐播放器**不在名单里**，上架会被拒。`targetSdk = 34` 也早已落后（Play 现在要求 35+）。
2. **信任模型崩塌**：一个主打"不联网、数据不出本机"的应用，同时要求读你**全部**文件（含照片、聊天记录导出、下载的 PDF）。用户无法审计代码，只能看权限——看到的就是这一条。
3. **没有降级路径**：拿不到权限时 `collectAudioFiles` 返回空表，界面是空库，没有"为什么是空的 / 怎么修"的引导。`ScanPath.resolve` 也依赖真实路径，SAF 选完目录后一旦权限变化就静默失效。

**修法（分三步，不必一次做完）**

- **短期**：把权限请求从"首次启动拦路"改成"用户点『刷新曲库』且曲库为空时，弹一张说明卡"。文案讲清楚代价，给"仅用系统媒体库（部分封面可能缺失）"的选项。
- **中期**：加一条 MediaStore 通道（`MediaStore.Audio.Media` + `READ_MEDIA_AUDIO`），作为默认实现；直扫文件系统降级为"高级模式"。你注释里说的 mjpeg 封面误判是真实痛点，但那是**边角 case**，不该让 100% 用户为它交出全盘权限。
- **长期**：拆两个 flavor——`foss`（MediaStore only，**删掉 INTERNET**，检查更新改为 `ACTION_VIEW` 打开 releases 页）和 `full`（直扫 + 应用内检查更新）。这样"默认不联网"从一句承诺变成系统设置里可验证的事实。

> 顺带：`INTERNET` 权限其实可以彻底不要。版本检查本来只用来提示"有新版本"，用 `Intent.ACTION_VIEW` 打开 releases 页面不需要任何权限，用户体验反而更好（能看更新说明）。

---

### 5. 大曲库下 `setMediaItems` 有 Binder 爆栈风险

`pushQueue()` 一次性把整个队列转成 `MediaItem` 推给 `MediaController`，每个 item 带 title/artist/album/URI/`extras{"file_path"}`。Binder 单次事务上限 1MB；按每个 item 200~400 字节算，**约 3000~5000 首就会踩到 `TransactionTooLargeException`**。Media3 的 issue tracker 里有同类报告，且这个异常发生在切歌/筛选这种高频路径上。

**修法**：超过 N 首（比如 500）时改用"窗口队列"——播放器里只放当前曲目 ±50 首，`onMediaItemTransition` 靠近边界时再补。或者把 `file_path` 这类 extras 从 MediaItem 里拿掉，改由服务端按 `mediaId` 反查（`MusicService` 已经有 `Song` 的 id 体系）。

---

## 二、P1：性能（都在热路径上）

### 6. 每首歌一次 `stat()`，而且跑在主线程

`PlayerViewModel.applyFilters()`（1738 行）：

```kotlin
SortOrder.RECENT -> list.sortedBy { File(it.filePath).lastModified() }
```

`applyFilters` 由 `updateSearch` 在 `viewModelScope`（= **Main**）里调用。5000 首的库，用户每敲一个字（250ms 防抖后）就要打 5000 次 `stat` 系统调用。冷启动更糟：`MusicRepository.loadSongs` 最后也有一句 `File(it.filePath).lastModified()` 做排序（344 行），刚扫完 5000 个文件又来 5000 次 stat。

**修法**：`MetaEntry` 里本来就有 `mtime`，把它带进 `Song`（加一个 `modifiedAt: Long` 字段），排序直接读内存字段。这一改同时省掉两处 IO。

### 7. 87 字段的 `PlayerUiState`，每 500ms 整体 copy 一次

```kotlin
// tickPosition()
_state.value = _state.value.copy(positionMs = pos, durationMs = c.duration)
```

`PlayerUiState` 有 87 个字段，其中 `allSongs` / `queue` / `recommendations` 是几千元素的 List。每 500ms 新建一个把这两个大 List 一起拎着的新对象，然后 `MainActivity` 在根上 `collectAsStateWithLifecycle()` 并把它整包传给 `MainScreen`——**整棵树的可跳过性全废了**，因为 `PlayerUiState` 里有 `List` 字段且没标 `@Immutable`，Compose 判定它 unstable，每个子 Composable 收到新参数就重组。

同时 `Song` 带 `Uri`（unstable），`SongRow` 的 `active = index == state.currentIndex` 又在每次进度更新时被重新求值。

**修法（按性价比排序）**

1. 把 `positionMs` / `durationMs` 拆成独立的 `StateFlow<PlaybackProgress>`，只让进度条和歌词订阅。**这一条能砍掉 90% 的无效重组。**
2. `@Immutable data class Song`（字段都不可变，`Uri` 换成 String 更好），给 `PlayerUiState` 也加注解。
3. 升级到 Kotlin 2.x + Compose Compiler 插件，自动开启 strong skipping——比拼手改 87 个字段现实得多。
4. `tickPosition` 用 `while (true) delay(500)` 常驻，Activity 进后台也不停。挂个 `repeatOnLifecycle(STARTED)` 或至少判断 `ProcessLifecycleOwner`。

### 8. `LazyColumn` 的 item 里直接查数据层

`MainScreen.kt:1068`：

```kotlin
playlistNames = Playlists.playlistsOfSong(song.id),
```

`playlistsOfSong` 的实现是：遍历所有歌单 → 每个歌单 `prefs.getString()` → `split(',')` → `mapNotNull { toLongOrNull() }`。可见 10 行 × 10 个歌单 × 每个歌单 500 首 = **每帧 5 万次字符串解析**，而且因为第 7 条的 2Hz 全量重组，它还会被动重跑。

**修法**：在 `PlayerUiState` 里维护一个 `songId → List<String>` 的映射，歌单变更时算一次；item 里只做一次 Map 查找。

### 9. 音频线程上的小分配

`SpectrumAnalyzer`：

- `handleBuffer()` 每次 `buf.asShortBuffer()` 新建一个视图对象（约 86 次/秒）。
- `analyze()` 里 `val out = FloatArray(BANDS)`（376 行），每分析一窗就 new 一个（约 43 次/秒）。

单次都不大，但这是 DMA 回调栈，GC 一旦在这里发生就是可听见的咔哒声。注释里已经写了"复用 `published` 会破坏快照原子性"——那就用**双缓冲**（两个预分配数组 + volatile 引用交换），语义一样，分配为零。

### 10. 每次冷启动都全盘 BFS

`loadLibrary()` 先读缓存秒开（好设计），但紧接着无条件 `loadSongs()` 做一次完整 BFS + 逐文件 `exists()`/`length()`/`lastModified()`。5000 首要遍历上万目录、打 15000 次 stat，每次打开 App 都来一遍。

**修法**：注册 `ContentObserver` 监听 `MediaStore.Audio` 变更，或用 `MediaStore.getGeneration()` 判断"库没变就跳过重扫"；把全量重扫留给手动刷新。

### 11. 16 路 `MediaMetadataRetriever` 并发

`SCAN_CONCURRENCY = 16` + `paths.map { async { ... } }`。16 个并发 retriever 在低端机上容易 `OOM` / `setDataSource` 失败；而且 5000 首就是一次性创建 5000 个 coroutine。

**修法**：降到 4~6 路（MMR 是重对象），用 `channelFlow` + 固定 worker 消费，顺便能做出真实进度条。

---

## 三、P1：正确性与健壮性

### 12. 零测试，27K 行

`app/src/test`、`app/src/androidTest` 都不存在，`@Preview` 也是 0。`ARCHITECTURE.md` 第 165 行自己承认了（P1），但优先级排低了——因为下面这些 bug **一条单测就能拦住**：

| 模块 | 该测什么 | 拦得住什么 |
|---|---|---|
| `Playlists` | 名字含 `:` / `;` / 空 / 超长 | 上面的 P0-2 |
| `LyricParser` | 各编码、错乱 tag size、无 tag 文件 | 手写二进制解析崩库 |
| `ListenStats` | 跨零点、跨时区、`epochDayOf` 边界 | 报告串天 |
| `Recommender` | 排序稳定性、explore=0/1 的单调性 | 推荐"点了没反应" |
| `UpdateChecker.isNewer` | `4.10 > 4.9`、`8Beta`、超长段 | 误报更新 |
| `DataTransfer` | 导入两次幂等、孤儿收编 | 数据翻倍/丢失 |

`LyricParser` 1240 行手写 ID3v2/APE/Vorbis/MP4 atom 解析，是最该有测试的模块——它的输入完全来自外部文件，纯函数，零 Android 依赖。**先从这个开始，两天能补到 60% 覆盖率。**

### 13. 导入没有版本闸门，任意键直写 prefs

`DataTransfer.import()` 写了 `"format": 1` 却**从不校验**。`applySettings()` 把 JSON 里出现的**任意 key** 写进 `iris_prefs`，类型由文件里的 `t` 字段决定。于是 `{"t":"string","v":"x"}` 写到 `last_song_id`，下次启动 `prefs.getLong(KEY_LAST_SONG, 0L)` 直接 `ClassCastException`——**一个手改的导入文件能让 App 永久起不来**（用户只能清数据）。

另外 32MB 护栏是这样的：

```kotlin
if (input.available() > 32 * 1024 * 1024) return@use null
val buf = input.readBytes()   // ← 先整个读进内存
if (buf.size > 32 * 1024 * 1024) null
```

`available()` 对 content URI 基本返回 0，所以第一道形同虚设；真正的护栏在**读完之后**，救不了 OOM。改成 `readNBytes` 式限长读取。

**修法**：校验 `format`；settings 走**白名单 + 类型表**（`Map<String, Type>`），未知键丢弃并提示"有 N 项设置已跳过"。

### 14. 一个文件读失败，整次扫描作废

```kotlin
paths.map { path -> async { sem.withPermit { ... } } }.awaitAll()
```

`readMeta` catch 了 `Throwable`，但 `File(path).exists()` / `length()` 在 `MANAGE_EXTERNAL_STORAGE` 被部分回收、或路径在 FUSE 上异常时会抛 `SecurityException`。`awaitAll` 是结构化并发——**一个子任务抛出，整次 `loadSongs` 取消**，用户看到的是"刷新失败，曲库为空"。

**修法**：`supervisorScope { ... }`，或每个 `async` 内部包 `runCatching`，把失败计数收集起来展示。

### 15. 响度库只有 400 条

`TrackGain.STORE_MAX = 400`，满了就按插入序踢最旧的。5000 首的库 → 用户听第 401 首时，第 1 首的测量值已被淘汰，下次再听又要重测 10 秒才生效。**这个功能在大曲库上等于随机生效**，而它恰恰是大曲库最需要。

**修法**：存 SQLite（或按目录分片存 JSON），不要用 prefs 字符串；容量按"曲库大小 × 1.2"动态算。

### 16. 其他小问题

- `ListenStats.KEEP_DAYS = 800` 超期记录在 `load()` 里静默丢弃，用户无从知晓"我的年度报告为什么只有两年"。
- `L.displayName()` 先 `matches()` 再 `matchEntire()!!`，正则跑两遍 + `!!`。
- `MusicService.onGetSession()` 对**任何** controller 都返回 session（服务 `exported="true"`）。对隐私向应用，建议 `onConnect` 里只放行系统 UI 与自己包名。
- `FloatingLyric` 单例里的 `CoroutineScope(SupervisorJob())` 从不 `cancel()`，每次服务重建都留一个活着的 Job。
- `MainActivity.onResume()` 是空实现；`DataTransfer.attachOrphans` 里的 `matched` 变量算完不用。

---

## 四、P2：工程与表达

### 17. 文档已开始腐烂（这是最可惜的一项）

`ARCHITECTURE.md` 写"最后更新 2026-10-04 · v4.5.1 · 51 文件 / 24000 行"，实际是 v4.9.5 / 56 文件 / 27278 行。具体失准的地方：

| 文档说 | 代码是 |
|---|---|
| "`MusicRepository`（**MediaStore 扫描**）" | 类注释第一句就是"**不依赖 MediaStore**" |
| "CI … 自动剥离设备专属的 gradle.properties 覆盖项" | workflow 里只有 `cat`，没有剥离 |
| "CI 已存在"（列为**已具备的资产**） | 连红 5 次 |
| "157 个持久化键" | `PlayerViewModel` 里 47 个 `KEY_`，全项目更多 |

这份文档的价值很高（5.1「源码丢失」那段是真正的技术债教训），但它现在会**误导未来的自己和 AI 协作者**——这恰恰是它开头那一句想避免的事。

**修法**：能不写数字就不写（"约 50 个文件"），或者加一个 10 行的 CI 脚本核对文件数/版本号/是否含 `MediaStore`，不符就红。文档里凡是"CI 会做 X"的句子，都应该有对应的一行 workflow。

### 18. GPL 头 3/56，与 `REMIND.md` 自相矛盾

`REMIND.md`：分发时必须保留"**源文件顶部的 GPL 头部注释**"。实际只有 `DataTransfer.kt`、`UpdateChecker.kt`、`IrisIcons.kt` 三个文件有。对一个把许可写了两份文档、还专门解释"传染性条款"的项目，这种不一致比没有声明更糟。

**修法**：`spotless` 或一个 20 行脚本，CI 校验每个 `.kt` 前 3 行含 `GNU General Public License`。

### 19. `L.kt`：345 处 `if (zh)` 不是国际化，是双语硬编码

```kotlin
val recExploreMost get() = if (zh) "探索为主：多推没听过的风格" else "Exploratory: leans into unfamiliar styles"
```

`strings.xml` 里只有 1 条 `app_name`。后果：

- 加第三种语言 = 改 345 处代码；翻译者无法参与（没有 `.po`/`strings.xml` 工作流）。
- 没有复数、没有占位符校验（`L.recPlays(n)` 靠手工拼串）、没有 `%1$s` 这类 lint 保护。
- `_zh` 是 Compose `mutableStateOf` 全局单例：非组合上下文（`MusicRepository` 里读 `L.unknownArtist`）读到的是当前值但**不会订阅**；单元测试无法注入语言；`@Preview` 无法换语言。

**修法**：`strings.xml` + `stringResource`，应用内切语言用 `LocalConfiguration` 覆盖（不需要 AppCompat 重建 Activity，Compose 完全支持）。如果坚持不用资源系统，至少做成 `Map<Key, Pair<Zh, En>>` 表 + 缺 EN 时回落到 ZH 并打 log——**现在是缺了就直接中文漏到英文界面里，没有任何告警**。

### 20. 无障碍基本为零

- `.clickable` 78 处，`contentDescription` 11 处（其中 11 处是 `= null`），`semantics { }` 0 处。
- 自绘 `IrisIcons`（68 行）里的图标全部没有语义标签，TalkBack 读出来是"按钮"。
- `MainScale` 的字体缩放在 `LocalDensity.fontScale * state.fontScale` 上再乘系统值，上限没钳制——系统设 200% + App 设 1.3 就是 260%，大面积文字被裁切。

这是"做给自己用"的项目最容易被跳过的一环，但它同时也是**成本最低、最容易被 PR 感谢**的一项：给 10 个主按钮加 `contentDescription`，半天。

### 21. 版本与工具链

- `targetSdk = 34` / `compileSdk = 34`：Play 现在要求 35+；Android 15 的强制 edge-to-edge 也没接（`enableEdgeToEdge`）。
- Kotlin 1.9.24 + AGP 8.5.2：升到 Kotlin 2.x 能白拿 strong skipping（对第 7 条是直接收益）。
- `minSdk = 24` 但没用 `java.time`（`ListenStats` 自己实现了 `floorDiv` 和 epochDay）——开了 core library desugaring 就可以删掉这堆手工日期算术，少一处 bug 源。

---

## 五、值得保留的（别改）

1. **注释写"为什么"，并且写失败史**。`MusicService` 里"v4.6.13 之前的随机模式后台播完一首就暂停"这种注释，是全项目最有价值的部分。
2. **权限白名单审计的思路**。方向对（防止权限悄悄扩张），只是对象选错了——它保护"已声明的集合"，而不是"集合里有没有危险项"。
3. **`ListenStats` 的追加式日志 + 原子 rename**。为"进程被杀"设计的，比很多商业 App 认真。
4. **移动侦测 + ID 迁移**（`remapMovedSongs`）。用"标题+作者+时长"三指纹且要求双侧唯一，比"按文件名猜"稳得多。
5. **导入的孤儿区**（`attachOrphans`）。空库导入不丢数据，这是踩过坑才写得出的设计。
6. **体积克制**。2.3MB 的 APK，砍掉 Coil + OkHttp 换自研 `ArtworkLoader`，娱乐类 App 里少见。

---

## 六、如果只做三件事

| # | 动作 | 成本 | 收益 |
|---|---|---|---|
| 1 | 把机器私货从 `gradle.properties` 挪走，让 CI 变绿，并加 `lint + test` 步骤 | 半天 | 拿回唯一的自动化防线；以后每次 push 都有人替你看一眼 |
| 2 | 修 `Playlists` 序列化 + 补 6 个纯函数单测 | 1 天 | 消掉一个会毁数据的 bug，同时建立测试的"第一个例子" |
| 3 | 把 `MANAGE_EXTERNAL_STORAGE` 从"首次启动拦路"改成"可选高级模式"，并给 MediaStore 一条默认通道 | 2~3 天 | 让"隐私优先"从文案变成架构；顺手解锁 F-Droid / Play 的可能性 |

做完这三件，这个项目的短板就从"工程化"变成"无障碍 + i18n"——那都是可以邀请社区一起做的部分。

---

*评审基于 `main` 分支快照，未运行构建（沙箱无 Android SDK）。行号对应 commit `5f56caa`。*
