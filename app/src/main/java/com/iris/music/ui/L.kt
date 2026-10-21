package com.iris.music.ui

import android.content.Context

/**
 * 应用内字符串仓库（i18n 中枢）。
 *
 * 为什么不用 res/values + stringResource：
 * 1) 全部 UI 文案原本就是 Kotlin 硬编码，没有资源层；2) 应用内切语言若走
 * AppCompatDelegate 需要 appcompat 依赖 + Activity 重建，对一个纯 Compose 应用太重。
 *
 * 这里用「一个对象 + 一个可重组的纪元值」实现：
 * - [zh] 为 true 时返回中文，否则英文；
 * - [langEpoch] 是 mutableState：切语言时自增，所有读取 L 的 Composable 因读取了它而失效重组；
 * - 语言选择持久化在 iris_prefs（与播放器设置同一文件），"跟随系统"在每次冷启动时
 *   按系统 locale 重新判定（zh 开头 → 中文，否则英文）。
 *
 * 约定：模板函数参数一律是已算好的原始值（Int/Float/预格式化字符串），函数内部只做拼装。
 */
object L {

    /**
     * 当前是否中文。背后是 mutableState：任何 Composable 在组合期读它，
     * 切语言时自动失效重组——不需要手动 epoch、不需要重建 Activity。
     */
    private val _zh = androidx.compose.runtime.mutableStateOf(true)

    /** 当前是否中文（组合期读取即订阅，切语言自动刷新） */
    val zh: Boolean get() = _zh.value

    /** 用户的选择（"sys"/"zh"/"en"），设置页三选行用 */
    private val _selection = androidx.compose.runtime.mutableStateOf("sys")
    val langSelection: String get() = _selection.value

    private const val PREFS = "iris_prefs"
    private const val KEY_LANG = "app_lang"

    /** 冷启动调用（MainActivity.onCreate，早于任何 Compose 内容） */
    fun init(app: android.app.Application) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LANG, "sys") ?: "sys"
        _selection.value = raw
        _zh.value = when (raw) {
            "zh" -> true
            "en" -> false
            else -> java.util.Locale.getDefault().language.startsWith("zh")
        }
    }

    /** en: true=英文 / false=中文 / null=跟随系统（下次冷启动按系统 locale 生效，本次立即生效） */
    fun setLanguage(app: android.app.Application, en: Boolean?) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = when (en) { null -> "sys"; true -> "en"; false -> "zh" }
        prefs.edit().putString(KEY_LANG, raw).apply()
        _selection.value = raw
        _zh.value = en != true
    }

    // ==================== 自动命名歌单的显示时本地化 ====================
    // 无名歌单在数据层存的是「歌单 N」/「Playlist N」，属于数据不能依赖 UI；
    // 显示时按模式重映射，两种语言来回切都能还原。
    private val zhAutoName = Regex("^歌单 (\\d+)$")
    private val enAutoName = Regex("^Playlist (\\d+)$")
    fun displayName(raw: String): String = when {
        zh && enAutoName.matches(raw) -> "歌单 ${enAutoName.matchEntire(raw)!!.groupValues[1]}"
        !zh && zhAutoName.matches(raw) -> "Playlist ${zhAutoName.matchEntire(raw)!!.groupValues[1]}"
        else -> raw
    }

    // ==================== 设置页：语言 ====================
    val languageLabel get() = if (zh) "语言 / Language" else "Language / 语言"
    val langSystem get() = if (zh) "跟随系统" else "Follow system"
    val langChinese get() = if (zh) "中文" else "中文 (Chinese)"
    val langEnglish get() = if (zh) "English" else "英文 (English)"
    val langSummary get() = if (zh) "切换后立即生效，不影响系统语言" else "Applies immediately; does not change the system language"

    // ==================== 通用 ====================
    val cancel get() = if (zh) "取消" else "Cancel"
    val close get() = if (zh) "关闭" else "Close"
    val settings get() = if (zh) "设置" else "Settings"
    val delete get() = if (zh) "删除" else "Delete"
    val create get() = if (zh) "创建" else "Create"
    val reset get() = if (zh) "重置" else "Reset"
    val done get() = if (zh) "完成" else "Done"
    val unknown get() = if (zh) "未知" else "Unknown"
    val counting get() = if (zh) "统计中…" else "Crunching…"
    val none get() = if (zh) "未选择" else "Nothing playing"

    // ==================== 数量 ====================
    fun songs(n: Int) = if (zh) "$n 首" else if (n == 1) "1 song" else "$n songs"
    fun playsSuffix(n: Int) = if (zh) " · 约 $n 次" else " · ~$n plays"
    fun days(n: Int) = if (zh) "$n 天" else if (n == 1) "1 day" else "$n days"

    // ==================== Song.kt 排序 ====================
    val sortTitle get() = if (zh) "标题" else "Title"
    val sortArtist get() = if (zh) "艺术家" else "Artist"
    val sortDuration get() = if (zh) "时长" else "Duration"
    val sortDateAdded get() = if (zh) "修改/添加时间" else "Date modified"
    val sortAsc get() = if (zh) "正序" else "Ascending"
    val sortDesc get() = if (zh) "倒序" else "Descending"
    val sortAscArrow get() = if (zh) "正序 ↑" else "Asc ↑"
    val sortDescArrow get() = if (zh) "倒序 ↓" else "Desc ↓"

    // ==================== Color.kt 枚举 ====================
    val modeDark get() = if (zh) "深色" else "Dark"
    val modeLight get() = if (zh) "浅色" else "Light"
    val modeAuto get() = if (zh) "自动" else "Auto"
    val surfaceSolid get() = if (zh) "实色" else "Solid"
    val surfaceFrosted get() = if (zh) "毛玻璃" else "Frosted"
    val surfaceLiquid get() = if (zh) "液态玻璃" else "Liquid glass"
    val layoutList get() = if (zh) "纵向排布" else "Vertical"
    val layoutCarousel get() = if (zh) "横向排布" else "Carousel"
    val layoutStack get() = if (zh) "堆叠排布" else "Stack"
    val layoutCompact get() = if (zh) "唱片墙" else "Poster wall"
    val themeMono get() = if (zh) "黑白" else "Mono"
    val themeSunset get() = if (zh) "落日橙" else "Sunset"
    val themeViolet get() = if (zh) "极光紫" else "Aurora"
    val themeOcean get() = if (zh) "深海蓝" else "Ocean"
    val themeBlood get() = if (zh) "血红" else "Blood"
    val themeGrass get() = if (zh) "青草绿" else "Grass"
    val themeCustom get() = if (zh) "自定义" else "Custom"

    // ==================== 主界面 ====================
    val searchHint get() = if (zh) "搜索歌曲、艺术家…" else "Search songs, artists…"
    val noMatchInQueue get() = if (zh) "当前列表中没有匹配的歌曲" else "No match in the current queue"
    val recommending get() = if (zh) "推荐中…" else "Finding picks…"
    val forYou get() = if (zh) "为你推荐" else "For you"
    fun likedLabel(n: Int) = if (zh) "♥ 我喜欢 · ${songs(n)}" else "♥ Favourites · ${songs(n)}"
    fun playlistLabel(name: String, n: Int) = if (zh) "歌单「$name」· ${songs(n)}" else "Playlist \"$name\" · ${songs(n)}"
    fun folderLabel(n: Int, folders: Int) =
        if (zh) "${songs(n)} · 已选 $folders 个文件夹" else "${songs(n)} · $folders folder${if (folders == 1) "" else "s"} selected"
    fun legacyLikedLabel(n: Int) = if (zh) "我的收藏 · ${songs(n)}" else "Favourites · ${songs(n)}"
    val myLikes get() = if (zh) "我的收藏" else "My favourites"

    // ==================== 歌单 ====================
    val playlists get() = if (zh) "歌单" else "Playlists"
    val folderView get() = if (zh) "文件夹视图" else "Folders"
    val playlistView get() = if (zh) "歌单视图" else "Playlists"
    val allMusic get() = if (zh) "全部音乐" else "All music"
    val heartRow get() = if (zh) "♥ 我喜欢" else "♥ Favourites"
    val emptyPlaylists get() =
        if (zh) "还没有歌单，点下方「新建歌单」创建一个吧"
        else "No playlists yet — tap \"New playlist\" below to create one"
    val playlistName get() = if (zh) "歌单名称" else "Playlist name"
    val newPlaylist get() = if (zh) "＋ 新建歌单" else "＋ New playlist"
    fun deletePlaylistAsk(name: String) = if (zh) "删除歌单「$name」？" else "Delete playlist \"$name\"?"
    fun deletePlaylistNote(n: Int) =
        if (zh) "歌单内的 $n 首歌不会被删除" else "The $n song${if (n == 1) "" else "s"} inside won't be deleted"
    val addToPlaylist get() = if (zh) "加入歌单" else "Add to playlist"
    val likedIn get() = if (zh) "已喜欢 · 点按移出" else "Liked · tap to remove"
    val tapToLike get() = if (zh) "点按喜欢" else "Tap to like"
    val addedIn get() = if (zh) "已加入 · 点按移出" else "Added · tap to remove"
    val createAndAdd get() = if (zh) "创建并加入" else "Create & add"
    val newPlaylistAndAdd get() = if (zh) "＋ 新建歌单并加入" else "＋ New playlist and add"
    fun posterHint(n: Int) = if (zh) "拖动探索 · ${songs(n)}" else "Drag to explore · ${songs(n)}"

    fun autoPlaylistName(id: Long) = if (zh) "歌单 $id" else "Playlist $id"

    // ==================== 播放卡片 / 歌单墙 ====================
    val sleepTimer get() = if (zh) "睡眠定时器" else "Sleep timer"
    val notPlaying get() = if (zh) "未播放" else "Not playing"
    val pickToPlay get() = if (zh) "选一首开始播放" else "Pick a song to start"
    val loadingLibrary get() = if (zh) "读取音乐库…" else "Loading library…"
    val emptyLibrary get() =
        if (zh) "还没有歌曲\n去设置里换个文件夹，或取消当前筛选"
        else "No songs yet\nPick another folder in Settings, or clear the current filter"

    // ==================== 均衡器 ====================
    val eqTitle get() = if (zh) "音效" else "Audio effects"
    val eqUnsupported get() =
        if (zh) "当前设备不支持系统均衡器（虚拟低音等增强仍可用）"
        else "System equalizer not supported on this device (virtual bass and other enhancements still work)"
    val eqCurve get() = if (zh) "曲线" else "Curve"
    val eqSliders get() = if (zh) "滑条" else "Sliders"
    val eqVirtualBass get() = if (zh) "虚拟低音" else "Virtual bass"
    val eqBlend get() = if (zh) "混合强度" else "Blend"
    val eqDre get() = if (zh) "动态范围增强" else "Dynamic range boost"
    val eqDreAmount get() = if (zh) "增强强度" else "Intensity"
    val eqSurround get() = if (zh) "虚拟环绕" else "Virtual surround"
    val eqSurroundNote get() = if (zh) "单声道歌曲也会自动展宽" else "Mono tracks are widened automatically"
    val eqWidth get() = if (zh) "扩展强度" else "Width"
    val eqWide get() = if (zh) "宽场环绕" else "Wide-field surround"
    val eqWideNote get() =
        if (zh) "分频展宽 + 早期反射，空间感更强"
        else "Band-split widening + early reflections for a stronger sense of space"
    val eqSpace get() = if (zh) "空间强度" else "Space"
    val eqLimiter get() = if (zh) "防失真保护" else "Distortion guard"
    val eqLimiterNote get() =
        if (zh) "只防破音撕裂，不影响正常音量与鼓点"
        else "Only guards against clipping; normal volume and drums unaffected"
    val eqHeadroom get() = if (zh) "保护余量" else "Headroom"
    val eqLoudness get() = if (zh) "响度均衡" else "Loudness match"
    val eqLoudnessNote get() =
        if (zh) "拉平各首歌的音量差异，避免忽大忽小（新歌听完一遍自动测量）"
        else "Evens out loudness between songs (each song is measured automatically after one full play)"
    val eqLoudnessAmount get() = if (zh) "拉平力度" else "Strength"
    fun eqNodes(n: Int) = if (zh) "$n 个节点" else "$n node${if (n == 1) "" else "s"}"
    fun eqCurveHint(min: Int, max: Int) =
        if (zh) "拖动节点调节 · 点击空白新增 · 双击节点删除（$min-$max 个）"
        else "Drag nodes to adjust · tap empty space to add · double-tap to delete ($min–$max nodes)"
    val eqLoudnessCurve get() = if (zh) "等响曲线" else "Equal-loudness curve"
    fun eqCompensation(v: String) = if (zh) "补偿强度 · $v%" else "Compensation · $v%"

    // ==================== 听歌报告 ====================
    val reportTitle get() = if (zh) "听歌报告" else "Listening report"
    val heatmap get() = if (zh) "听歌热力图" else "Listening heatmap"
    val heatmapGrid get() = if (zh) "方格" else "Grid"
    val noListen get() = if (zh) "没有听歌" else "No listening"
    val heatmapCurveNote get() =
        if (zh) "近一年每天的听歌时长，曲线越高听得越久"
        else "Daily listening time over the past year — higher means more"
    val heatmapGridNote get() =
        if (zh) "近一年每天的听歌时长，颜色越深听得越久"
        else "Daily listening time over the past year — darker means more"
    val topSongsTitle get() = if (zh) "单曲时长排行" else "Top songs by time"
    fun topSongsNote(range: String) =
        if (zh) "${range}听得最久的歌，点一下直接播放"
        else "Longest-played songs $range — tap to play"
    fun emptyReport(range: String) =
        if (zh) "${range}还没有听歌记录" else "No listening records $range yet"
    val collapse get() = if (zh) "收起" else "Collapse"
    fun expandAll(n: Int) = if (zh) "展开全部 $n 首" else "Show all $n"
    fun rangeListening(range: String) = if (zh) "${range}听歌" else "$range · Listening"
    val labelSongs get() = if (zh) "歌曲" else "Songs"
    val labelRecords get() = if (zh) "记录" else "Plays"
    val labelActive get() = if (zh) "活跃" else "Active"
    val dailyAvg get() = if (zh) "日均" else "Daily avg"
    val longestDay get() = if (zh) "最长一天" else "Longest day"
    fun longestValue(v: String) = if (zh) "最长 $v" else "Longest $v"
    val wdMon get() = if (zh) "一" else "M"
    val wdWed get() = if (zh) "三" else "W"
    val wdFri get() = if (zh) "五" else "F"
    val less get() = if (zh) "少" else "Less"
    val more get() = if (zh) "多" else "More"
    fun allTime(v: String) = if (zh) "累计 $v" else "$v total"

    // ==================== 时间格式（听歌报告） ====================
    private val enMonths = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    fun monthShort(month0: Int) = if (zh) "${month0 + 1}月" else enMonths[month0.coerceIn(0, 11)]
    fun dateFull(month0: Int, day: Int) = if (zh) "${month0 + 1}月${day}日" else "${enMonths[month0.coerceIn(0, 11)]} $day"
    val zeroMin get() = if (zh) "0分" else "0m"
    fun secs(s: Long) = if (zh) "${s}秒" else "${s}s"
    fun mins(m: Long) = if (zh) "${m}分" else "${m}m"
    fun hours(h: Long) = if (zh) "${h}小时" else "${h}h"
    fun hoursMins(h: Long, m: Long) = if (zh) "${h}小时${m}分" else "${h}h ${m}m"
    val ltOneMin get() = if (zh) "<1分" else "<1m"
    fun totalMins(m: Long) = if (zh) "${m}分" else if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    // ==================== 统计区间 ====================
    val rangeDay get() = if (zh) "日" else "Day"
    val rangeToday get() = if (zh) "今天" else "Today"
    val rangeWeek get() = if (zh) "周" else "Week"
    val rangeThisWeek get() = if (zh) "本周" else "This week"
    val rangeMonth get() = if (zh) "月" else "Month"
    val rangeThisMonth get() = if (zh) "本月" else "This month"
    val rangeYear get() = if (zh) "年" else "Year"
    val rangeThisYear get() = if (zh) "今年" else "This year"
    val rangeAll get() = if (zh) "全部" else "All"
    val rangeAllTime get() = if (zh) "累计" else "All time"

    // ==================== 曲库 / 数据层 ====================
    val rootFolder get() = if (zh) "根目录" else "Root folder"
    val unknownArtist get() = if (zh) "未知艺术家" else "Unknown artist"
    val removedSongs get() = if (zh) "已移除的歌曲" else "Removed songs"

    // ==================== 推荐理由 ====================
    val recLiked get() = if (zh) "你点赞过这首" else "You liked this song"
    fun recPlays(n: Int) =
        if (zh) "播放次数排名靠前（$n 次）" else "Among your most played ($n plays)"
    fun recLongListen(minutes: Int) =
        if (zh) "累计听了较久（约 $minutes 分钟）" else "Long total listening (about $minutes min)"
    fun recArtistLiked(artist: String) =
        if (zh) "你常点赞 $artist 的歌" else "You often like songs by $artist"
    fun recSkipped(n: Int) =
        if (zh) "经常没听完就切走（$n 次）" else "Often skipped before finishing ($n times)"
    val recRecent get() =
        if (zh) "最近一小时刚听过，暂时降权" else "Played in the last hour — temporarily ranked down"

    // ==================== EQ 曲线预设 ====================
    val curveBass get() = if (zh) "低音增强" else "Bass boost"
    val curveMid get() = if (zh) "中音增强" else "Vocal boost"
    val curveTreble get() = if (zh) "高音增强" else "Treble boost"

    // ==================== 夜间场景 ====================
    val nfCoverStand get() = if (zh) "封面立牌" else "Cover stand"
    val nfSlogan get() = if (zh) "今夜，让城市陪你听歌" else "Tonight, let the city listen with you"
    val nfHint get() = if (zh) "天台夜航 · 转回竖屏退出" else "Rooftop night flight · rotate to portrait to exit"

    // ==================== 设置页 ====================
    // —— 提示 ——
    val errNotRealPath get() =
        if (zh) "无法获取该文件夹的真实路径，请从本地存储中选择"
        else "Couldn't resolve the real path of that folder — please pick it from local storage"
    val errAndroidData get() =
        if (zh) "其他应用的 Android/data 目录系统禁止读取（Android 11+ 限制，与权限无关）。请把音乐移到 Music、Download 等普通文件夹后再选择。"
        else "Other apps' Android/data folders are blocked by the system (Android 11+ restriction, unrelated to permissions). Move your music to a normal folder such as Music or Download and try again."
    val errNoAccess get() =
        if (zh) "无法读取该文件夹，请先在下方「存储权限」开启「所有文件访问」后重新选择。"
        else "Couldn't read that folder. Enable \"All files access\" under Storage permission below, then pick it again."
    val folderNoAudio get() =
        if (zh) "该文件夹（含一层子目录）未发现音频文件；更深层次的子目录会在扫描后收录。"
        else "No audio files found in that folder (one level of subfolders included); deeper levels are picked up after a scan."
    // —— 外观 ——
    val secAppearance get() = if (zh) "外观" else "Appearance"
    val themeSection get() = if (zh) "主题" else "Theme"
    val darkMode get() = if (zh) "明暗模式" else "Theme mode"
    val customColors get() = if (zh) "自定义配色" else "Custom colors"
    val customColorsNote get() =
        if (zh) "拖动滑块调配主色与次色，立即生效" else "Drag the sliders to mix the primary & secondary colors — applies instantly"
    val colorPrimary get() = if (zh) "主色" else "Primary"
    val colorSecondary get() = if (zh) "次色" else "Secondary"
    fun hue(v: Int) = if (zh) "色相 · $v" else "Hue · $v"
    fun saturation(v: Int) = if (zh) "饱和度 · $v%" else "Saturation · $v%"
    fun brightness(v: Int) = if (zh) "明度 · $v%" else "Brightness · $v%"
    val surfaceStyle get() = if (zh) "界面材质" else "Surface material"
    val surfaceSolidNote get() = if (zh) "实色：不透明卡片，对比强、最省电" else "Solid: opaque cards, strongest contrast, most efficient"
    val surfaceFrostedNote get() = if (zh) "毛玻璃：上栏与歌词层背景模糊，卡片仍是实色" else "Frosted: top bar & lyrics blur the background, cards stay solid"
    val surfaceLiquidNote get() = if (zh) "液态玻璃：真折射取样 + 色散 + 镜面内缘，最费性能" else "Liquid glass: real refraction + dispersion + specular rim, most demanding"
    fun frostBlur(v: String) = if (zh) "模糊浓度 · $v" else "Blur strength · $v"
    val frostClear get() = if (zh) "清透：几乎能看清背后内容，最省电" else "Clear: background almost readable, most efficient"
    val frostLight get() = if (zh) "轻薄：淡淡一层雾" else "Light: a thin veil of frost"
    val frostStandard get() = if (zh) "标准（推荐）" else "Standard (recommended)"
    val frostHeavy get() = if (zh) "厚重：背景只剩色块" else "Heavy: background reduced to color fields"
    val frostMax get() = if (zh) "极厚：糊成纯色，最费性能" else "Max: background fully fused, most demanding"
    fun bgBlur(v: Int) = if (zh) "背景模糊 · ${v}dp" else "Background blur · ${v}dp"
    val blurOff get() = if (zh) "关闭：封面原图直接当背景，最清晰也最抢眼" else "Off: cover art used as-is — sharpest, most eye-catching"
    val blurSlight get() = if (zh) "轻微：还看得出封面构图" else "Slight: cover composition still readable"
    val blurMedium get() = if (zh) "适中：只剩色块与轮廓" else "Medium: color fields and shapes remain"
    val blurStandard get() = if (zh) "标准（推荐）：纯色氛围底" else "Standard (recommended): a plain ambient wash"
    val blurExtreme get() = if (zh) "极致：几乎是一片纯色" else "Extreme: nearly a flat color"
    val customBackground get() = if (zh) "自定义背景" else "Custom background"
    val customBgOn get() =
        if (zh) "已使用自选图片做背景（仍受上方「背景模糊」影响）"
        else "Using your own image as background (still affected by Background blur above)"
    val customBgOff get() = if (zh) "不设则背景跟随当前专辑封面" else "When unset, the background follows the current album cover"
    val pickImage get() = if (zh) "选择图片" else "Choose image"
    val resetCover get() = if (zh) "恢复封面" else "Reset to cover"
    val cornerRadius get() = if (zh) "界面圆角" else "Corner radius"
    val cornerSharp get() = if (zh) "直角：锐利硬朗" else "Sharp: crisp and angular"
    val cornerSlight get() = if (zh) "微圆：克制内敛" else "Slight: restrained"
    val cornerStandard get() = if (zh) "标准圆角" else "Standard"
    val cornerLarge get() = if (zh) "大圆角：柔和饱满（推荐）" else "Large: soft and full (recommended)"
    val cornerPill get() = if (zh) "极圆：接近胶囊" else "Extra large: near-capsule"
    val fontSize get() = if (zh) "字号大小" else "Text size"
    val fontStandard get() = if (zh) "标准" else "Standard"
    val fontSmall get() = if (zh) "偏小：紧凑省地" else "Small: compact"
    val fontLarge get() = if (zh) "偏大：清晰易读" else "Large: easier to read"
    val fontXL get() = if (zh) "特大：醒目护眼" else "Extra large: maximum clarity"
    val appFont get() = if (zh) "界面字体" else "App font"
    val fontSystem get() = if (zh) "系统" else "System"
    val fontSerif get() = if (zh) "衬线" else "Serif"
    val layout get() = if (zh) "界面排布" else "Layout"
    val layoutListNote get() = if (zh) "纵向排布：歌单列表 + 右滑播放页，原有结构" else "Vertical: playlist list + swipe right to the player — the classic structure"
    val layoutCarouselNote get() = if (zh) "横向排布：只剩播放卡片，左右滑动翻歌" else "Carousel: just player cards, swipe left/right between songs"
    val layoutStackNote get() = if (zh) "堆叠排布：卡片叠成一沓，把最上面那张拖走切歌" else "Stack: cards piled up — drag the top one away to change songs"
    val layoutCompactNote get() = if (zh) "唱片墙：正方形/长方形磁贴组成画布，可自由拖动探索" else "Poster wall: a canvas of square/rectangular tiles you can drag around freely"
    val rowStyle get() = if (zh) "列表行样式" else "List row style"
    val rowCompact get() = if (zh) "紧凑" else "Compact"
    val rowStandard get() = if (zh) "标准" else "Standard"
    val rowLoose get() = if (zh) "宽松" else "Loose"
    val rowShowCover get() = if (zh) "歌单行显示封面" else "Show artwork on song rows"
    val rowShowArtist get() = if (zh) "歌单行显示歌手" else "Show artist on song rows"
    val rowShowArtistOff get() = if (zh) "关闭后歌单行只显示歌名" else "When off, rows show the song title only"
    val rowShowPlaylist get() = if (zh) "歌单行显示所属歌单" else "Show source playlist on song rows"
    val rowShowPlaylistOff get() = if (zh) "关闭后不再显示歌曲所在的收藏歌单" else "When off, hides which playlist each song belongs to"
    val searchPinned get() = if (zh) "搜索框常驻" else "Persistent search bar"
    val searchPinnedOff get() = if (zh) "关闭后上栏收起搜索框，点标题展开，闲置几秒自动收起" else "When off, the top bar collapses the search field — tap the title to open it; it folds away after a few idle seconds"
    val rowShowLikeBadge get() = if (zh) "歌单行显示点赞角标" else "Show like badge on song rows"
    val rowShowLikeBadgeNote get() = if (zh) "已点赞的歌曲在右下角显示爱心" else "Liked songs show a small heart in the corner"
    // —— 播放 ——
    val secPlayback get() = if (zh) "播放" else "Playback"
    val fadeInOut get() = if (zh) "淡入淡出" else "Fade in / out"
    val fadeInOutNote get() = if (zh) "开头音量渐起、结尾渐落，暂停也不再突兀" else "Volume ramps up at the start and down at the end; pauses are never abrupt"
    fun fadeSeconds(v: String) = if (zh) "渐变时长 · $v 秒" else "Fade duration · $v s"
    val silenceSkip get() = if (zh) "无声略过 · beta" else "Silence skip · beta"
    val silenceSkipNote get() = if (zh) "自动跳过歌曲开头和结尾没有声音的部分" else "Automatically skips silent parts at the beginning and end of songs"
    val topBarIdleHide get() = if (zh) "上栏静止时隐藏" else "Hide top bar when idle"
    val topBarIdleHideNote get() = if (zh) "完全静止时上栏淡到全透明，碰一下即回" else "When nothing moves, the top bar fades out completely — touch to bring it back"
    // —— 歌词 ——
    val secLyrics get() = if (zh) "歌词" else "Lyrics"
    val coverLyric get() = if (zh) "封面歌词" else "Cover lyric"
    val coverLyricNote get() = if (zh) "封面左下角单行歌词，换句时模糊渐隐渐出" else "A single lyric line in the cover's corner, blurring in and out between lines"
    val karaoke get() = if (zh) "逐字流光" else "Word glow"
    val karaokeNote get() = if (zh) "全屏歌词当前行按进度扫光点亮（无词级时间时按句长估算）" else "The current line lights up word by word with progress (estimated when word timings are absent)"
    val lyricAlign get() = if (zh) "歌词对齐" else "Lyric alignment"
    val alignLeft get() = if (zh) "靠左" else "Left"
    val alignCenter get() = if (zh) "居中" else "Center"
    val alignRight get() = if (zh) "靠右" else "Right"
    val lyricFont get() = if (zh) "歌词字体" else "Lyric font"
    fun lyricFontSize(v: Int) = if (zh) "歌词字号 · ${v}sp" else "Lyric size · ${v}sp"
    // —— 动效与反馈 ——
    val secMotion get() = if (zh) "动效与反馈" else "Motion & feedback"
    val haptics get() = if (zh) "触感反馈" else "Haptic feedback"
    val hapticsNote get() = if (zh) "按键、切歌、拖动滚动条时轻微震动" else "A light buzz on taps, track changes and scrubbing"
    val hapticsDrag get() = if (zh) "拖动刻度震动" else "Scrub haptics"
    val hapticsDragNote get() = if (zh) "拖动进度条每跨 1 秒轻震一下，唱片墙每滑 1/4 格（含惯性）轻震一下" else "One tick per second on the seek bar; one tick per quarter-tile on the poster wall, fling included"
    val physicsMotion get() = if (zh) "物理动效 · beta" else "Physical motion · beta"
    val physicsMotionNote get() = if (zh) "倾斜频谱 + 摇动封面，传感器驱动" else "Tilting spectrum + shakeable cover, sensor-driven"
    val springyMotion get() = if (zh) "灵动动效" else "Springy motion"
    val springyMotionNote get() = if (zh) "按钮、列表、卡片与面板采用更有弹性的过渡" else "Buttons, lists, cards and sheets use more elastic transitions"
    val bassMotor get() = if (zh) "低音马达震动 · beta" else "Bass motor vibration · beta"
    val bassMotorNote get() = if (zh) "跟随低频节奏轻微震动，幅度随鼓点强弱变化" else "Light vibration following the bass line; amplitude tracks the drums"
    val vibStrength get() = if (zh) "震动强度" else "Vibration strength"
    val vibLightTap get() = if (zh) "轻点" else "Light tap"
    val vibHeavyHit get() = if (zh) "重击" else "Heavy hit"
    val vibAdaptive get() = if (zh) "自适应旋律" else "Adaptive melody"
    val vibAdaptiveNote get() = if (zh) "强度随鼓点力度、时长随鼓点长短自动变化" else "Strength follows hit force and duration follows hit length automatically"
    val vibDuration get() = if (zh) "单次震动时长" else "Vibration duration"
    val vibShort get() = if (zh) "短" else "Short"
    val vibMedium get() = if (zh) "中" else "Medium"
    val vibLong get() = if (zh) "长" else "Long"
    val triggerSensitivity get() = if (zh) "触发灵敏度" else "Trigger sensitivity"
    val sensLow get() = if (zh) "低" else "Low"
    val sensHigh get() = if (zh) "高" else "High"
    val bassCutoff get() = if (zh) "低音范围（截止频率）" else "Bass range (cutoff frequency)"
    fun bassCutoffNote(hz: Int) =
        if (zh) "${hz}Hz 以下算作低音（走沉震），以上走脆震"
        else "Below ${hz}Hz counts as bass (deep buzz); above it uses the crisp tick"
    // —— 推荐 ——
    val secRecs get() = if (zh) "推荐" else "Recommendations"
    val recExplore get() = if (zh) "推荐探索度" else "Exploration level"
    val recSafe get() = if (zh) "贴合口味：按相似度和历史喜好推荐" else "Safe: recommendations by similarity and history"
    val recBalanced get() = if (zh) "均衡：偏好为主，偶尔新鲜" else "Balanced: your taste first, occasional surprises"
    val recExploreMost get() = if (zh) "探索为主：多推没听过的风格" else "Exploratory: leans into unfamiliar styles"
    val recRandom get() = if (zh) "全随机：完全探索未知" else "Fully random: pure discovery"
    val showRecsOnPlaylists get() = if (zh) "歌单页显示推荐区" else "Show recommendations on the playlist page"
    val showRecsOff get() = if (zh) "关闭后歌单页只显示歌曲列表" else "When off, the playlist page shows only the song list"
    // —— 数据 ——
    val secData get() = if (zh) "数据" else "Data"
    val scanFolder get() = if (zh) "指定文件夹" else "Music folder"
    fun scanCurrent(root: String) =
        if (zh) "当前限定：$root\n曲库只包含该文件夹之内的歌曲，推荐与搜索同步收窄"
        else "Limited to: $root\nThe library contains only songs inside this folder; recommendations and search follow suit"
    val scanUnsetNote get() =
        if (zh) "不限定则扫描整个共享存储；选择后只显示该文件夹之内（含子文件夹）的歌曲"
        else "Unset scans the whole shared storage; once chosen, only songs inside the folder (subfolders included) are shown"
    val pickFolder get() = if (zh) "选择文件夹" else "Choose folder"
    val restoreAll get() = if (zh) "恢复全部" else "Restore all"
    val topBarSort get() = if (zh) "上栏显示排序" else "Sort options in top bar"
    val topBarSortOff get() = if (zh) "关闭后上栏不再展开排序选项，界面更简洁" else "When off, the top bar hides the sort options for a cleaner look"
    val storagePerm get() = if (zh) "存储权限" else "Storage permission"
    val storagePermWarn get() =
        if (zh) "未授予「所有文件访问」，曲库可能漏掉部分歌曲。"
        else "Without \"All files access\", some songs may be missing from the library."
    val goEnable get() = if (zh) "去开启" else "Open settings"
    val musicFolders get() = if (zh) "音乐文件夹" else "Music folders"
    val about get() = if (zh) "关于" else "About"
    fun aboutBody(v: String) =
        if (zh) "IRIS Music v$v\n纯本地音乐播放器 · 不联网不上传\nKotlin + Jetpack Compose 构建"
        else "IRIS Music v$v\nA purely local music player · no network, no uploads\nBuilt with Kotlin + Jetpack Compose"
    val myData get() = if (zh) "我的数据" else "My data"
    val exporting get() = if (zh) "正在导出…" else "Exporting…"
    val exported get() = if (zh) "已导出到所选位置" else "Exported to the chosen location"
    val exportFailed get() = if (zh) "导出失败" else "Export failed"
    val importing get() = if (zh) "正在导入…" else "Importing…"
    fun imported(songs: Int, likes: Int, playlists: Int, days: Int) =
        if (zh) "导入完成：匹配 $songs 首、点赞 $likes、歌单 $playlists、听歌记录 $days 天"
        else "Import complete: $songs songs matched, $likes likes, $playlists playlists, $days listening days"
    val importBadFormat get() =
        if (zh) "导入失败：文件格式不是 IRIS Music 导出的数据" else "Import failed: not an IRIS Music export file"
    val importUnreadable get() = if (zh) "导入失败：文件无法读取" else "Import failed: file couldn't be read"
    val dataExportNote get() =
        if (zh) "把点赞、播放次数、听歌时长和歌单导出成 JSON 文件，换机或重装后导入即可恢复。"
        else "Exports likes, play counts, listening time and playlists as a JSON file — import it after switching phones or reinstalling."
    val dataMergeNote get() =
        if (zh) "导入按曲目匹配并取较大值合并，重复导入不会翻倍。全程本地读写，不联网。"
        else "Import matches by track and keeps the larger value, so importing twice never doubles anything. Fully local; never online."
    val exportData get() = if (zh) "导出数据" else "Export data"
    val checkUpdate get() = if (zh) "检查更新" else "Check for updates"
    val checkUpdateNote get() =
        if (zh) "开启后可手动检查 GitHub 上的新版本，仅此访问网络"
        else "When on, you can manually check GitHub for new versions — the only network use"
    val checking get() = if (zh) "正在检查…" else "Checking…"
    fun newVersionFound(tag: String) = if (zh) "发现新版本 $tag" else "New version found: $tag"
    val upToDate get() = if (zh) "已是最新版本" else "You're up to date"
    val checkFailed get() = if (zh) "检查失败：网络不可用或稍后再试" else "Check failed: network unavailable, try again later"
    val checkingShort get() = if (zh) "检查中…" else "Checking…"
    val goDownload get() = if (zh) "前往下载" else "Get it"
    // —— 悬浮歌词 ——
    val floatLyric get() = if (zh) "悬浮歌词" else "Floating lyrics"
    val floatLyricNote get() =
        if (zh) "在桌面/其它应用上方常驻显示当前歌词，单行可拖动移动位置"
        else "Shows the current lyric line over the home screen and other apps; drag the line to reposition it"
    val floatLyricSetting get() = if (zh) "桌面悬浮歌词" else "Floating lyrics on desktop"
    val floatPermMissing get() = if (zh) "尚未授予悬浮窗权限" else "Overlay permission not granted yet"
    val floatServiceNote get() = if (zh) "常驻前台服务，退到后台也跟随" else "Runs as a foreground service, keeps following even in the background"
    val goFloatPerm get() = if (zh) "去授予悬浮窗权限" else "Grant overlay permission"
    fun floatFontSize(v: Int) = if (zh) "字号 · $v sp" else "Size · $v sp"
    fun floatWidth(v: Int) = if (zh) "长度 · $v% 屏宽" else "Width · $v% of screen"
    fun floatBgAlpha(v: Int) = if (zh) "黑底透明度 · $v%" else "Background opacity · $v%"
}

// ==================== 数据层枚举的本地化显示名 ====================
/** 曲库排序字段 */
val com.iris.music.data.SortOrder.label: String
    get() = when (this) {
        com.iris.music.data.SortOrder.TITLE -> L.sortTitle
        com.iris.music.data.SortOrder.ARTIST -> L.sortArtist
        com.iris.music.data.SortOrder.DURATION -> L.sortDuration
        com.iris.music.data.SortOrder.RECENT -> L.sortDateAdded
    }

/** 曲库排序方向 */
val com.iris.music.data.SortDirection.label: String
    get() = when (this) {
        com.iris.music.data.SortDirection.ASCENDING -> L.sortAsc
        com.iris.music.data.SortDirection.DESCENDING -> L.sortDesc
    }

/** 统计区间（报告页签） */
val com.iris.music.data.StatRange.label: String
    get() = when (this) {
        com.iris.music.data.StatRange.DAY -> L.rangeDay
        com.iris.music.data.StatRange.WEEK -> L.rangeWeek
        com.iris.music.data.StatRange.MONTH -> L.rangeMonth
        com.iris.music.data.StatRange.YEAR -> L.rangeYear
        com.iris.music.data.StatRange.ALL -> L.rangeAll
    }

/** 均衡器预设曲线 */
val com.iris.music.audio.EqBoost.label: String
    get() = when (this) {
        com.iris.music.audio.EqBoost.BASS -> L.curveBass
        com.iris.music.audio.EqBoost.MID -> L.curveMid
        com.iris.music.audio.EqBoost.TREBLE -> L.curveTreble
    }

/** 统计区间在报告文案里的名称（今天/本周/…），英文侧同样可用 */
val com.iris.music.data.StatRange.title: String
    get() = when (this) {
        com.iris.music.data.StatRange.DAY -> L.rangeToday
        com.iris.music.data.StatRange.WEEK -> L.rangeThisWeek
        com.iris.music.data.StatRange.MONTH -> L.rangeThisMonth
        com.iris.music.data.StatRange.YEAR -> L.rangeThisYear
        com.iris.music.data.StatRange.ALL -> L.rangeAllTime
    }
