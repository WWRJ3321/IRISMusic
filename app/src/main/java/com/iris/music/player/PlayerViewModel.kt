package com.iris.music.player

import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.iris.music.audio.BassHaptics
import com.iris.music.audio.RangeEnhancer
import com.iris.music.audio.SafeLimiter
import com.iris.music.audio.VirtualBass
import com.iris.music.audio.TrackGain
import com.iris.music.audio.VirtualSurround
import com.iris.music.audio.SpatialWide
import com.iris.music.audio.FadeController
import com.iris.music.audio.SilenceSkipper
import com.iris.music.audio.SpectrumAnalyzer
import com.iris.music.data.ListenEntry
import com.iris.music.data.ListenReport
import com.iris.music.data.ListenReportBuilder
import com.iris.music.data.ListenStats
import com.iris.music.data.DataTransfer
import com.iris.music.data.MusicFolder
import com.iris.music.data.MusicRepository
import com.iris.music.data.PlayHistory
import com.iris.music.data.Playlist
import com.iris.music.data.Playlists
import com.iris.music.data.Recommender
import com.iris.music.data.RepeatMode
import com.iris.music.data.ScanPath
import com.iris.music.data.Song
import com.iris.music.data.SortDirection
import com.iris.music.data.SortOrder
import com.iris.music.data.StatRange
import com.iris.music.playback.MusicService
import com.iris.music.playback.FloatingLyric
import com.iris.music.playback.SleepTimer
import com.iris.music.ui.ArtworkLoader
import com.iris.music.ui.Haptics
import com.iris.music.ui.theme.BG_BLUR_DEFAULT
import com.iris.music.ui.theme.BG_BLUR_MAX
import com.iris.music.ui.theme.BG_BLUR_MIN
import com.iris.music.ui.theme.GLASS_BLUR_DEFAULT
import com.iris.music.ui.theme.GLASS_BLUR_MAX
import com.iris.music.ui.theme.GLASS_BLUR_MIN
import com.iris.music.ui.theme.IrisMode
import com.iris.music.ui.theme.IrisLayout
import com.iris.music.ui.theme.IrisSurfaceStyle
import com.iris.music.ui.theme.IrisTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 播放器全局 UI 状态 */
data class PlayerUiState(
    val allSongs: List<Song> = emptyList(),
    val queue: List<Song> = emptyList(),
    val folders: List<MusicFolder> = emptyList(),
    val selectedFolders: Set<String> = emptySet(),
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val theme: IrisTheme = IrisTheme.MONO,
    val mode: IrisMode = IrisMode.LIGHT,
    val systemDark: Boolean = false,
    /** 表面材质：实色 / 毛玻璃 / 液态玻璃 */
    val surfaceStyle: IrisSurfaceStyle = IrisSurfaceStyle.SOLID,
    /** 界面排布：纵向列表 / 横向卡片 / 堆叠卡片 */
    val layout: IrisLayout = IrisLayout.LIST,
    /** 模糊浓度倍率，1.0 为基准（同时作用于毛玻璃与液态玻璃） */
    val glassBlur: Float = GLASS_BLUR_DEFAULT,
    /** 背景封面高斯模糊半径（dp），与材质无关，实色下同样生效 */
    val bgBlur: Float = BG_BLUR_DEFAULT,
    /** 歌单行大小：0=紧凑 1=标准 2=宽松 */
    val rowSize: Int = 1,
    /** 歌单行左侧显示封面 */
    val showRowCover: Boolean = false,
    /** 歌单行显示歌手副标题 */
    val showRowArtist: Boolean = true,
    /** 歌单行右下角显示所属歌单名 */
    val showRowPlaylist: Boolean = true,
    /** 搜索框常驻上栏；关闭后点标题才展开，闲置自动收起 */
    val searchPersistent: Boolean = true,
    /** 歌单行右下角显示点赞角标 */
    val showLikedBadge: Boolean = false,
    /** 已点赞歌曲 ID 集合 */
    val likedSongIds: Set<Long> = emptySet(),
    /** 队列仅显示点赞歌曲（我的收藏筛选） */
    val onlyLiked: Boolean = false,
    val showSettings: Boolean = false,
    val loading: Boolean = true,
    /** 正在刷新音乐库（下拉刷新/刷新按钮），驱动 UI 指示器 */
    val refreshing: Boolean = false,
    /** 海报墙果冻动效：磁贴位移/尺寸变化带弹性果冻拉伸 */
    val jellyAnim: Boolean = false,
    /** 睡眠定时器总时长，0 表示未启用 */
    val sleepTimerMs: Long = 0L,
    /** 睡眠定时器到点的绝对时间戳（System.currentTimeMillis 基准） */
    val sleepTimerEndMs: Long = 0L,
    /** 推荐歌曲列表 */
    val recommendations: List<Song> = emptyList(),
    /** 听歌报告弹层是否展开 */
    val showReport: Boolean = false,
    /** 听歌报告数据（按 [ListenReport.range] 汇总） */
    val report: ListenReport = ListenReport(),
    /** 推荐版本计数器：每次重新计算推荐时递增，用于触发 Crossfade 动画 */
    val recommendationsVersion: Int = 0,
    /** 推荐探索度 0-1（0=纯偏好，1=纯探索） */
    val exploration: Float = 0.3f,
    /** 所有自定义歌单（按创建顺序） */
    val playlists: List<Playlist> = emptyList(),
    /** 当前选中的歌单 ID，null = 显示全部（文件夹/收藏视图） */
    val activePlaylistId: Long? = null,
    /** 播放页可视化频谱开关（点击音频格式徽章切换） */
    val visualizerEnabled: Boolean = false,
    /** 实验性：可视化随手机倾斜变化（向哪侧倾斜哪侧升幅加大） */
    val tiltSpectrum: Boolean = false,
    /** 实验性：摇动手机封面跟着一晃（duangduang） */
    val coverShake: Boolean = false,
    /** 封面左下角单行歌词（锁在封面上，换词时模糊渐隐渐出） */
    val coverLyric: Boolean = false,
    /** 全屏歌词当前行的伪逐字扫光（无词级时间时按行时长匀速扫过） */
    val karaokeLyric: Boolean = true,
    /** 全屏歌词对齐：0=靠左 1=居中 2=靠右 */
    val lyricAlign: Int = 1,
    /** 歌词字体：0=系统 1=衬线 */
    val lyricFont: Int = 0,
    /** 歌词字号 (sp) */
    val lyricFontSize: Float = 20f,
    /** 界面字体：0=系统无衬线 1=衬线 */
    val uiFont: Int = 0,
    /** 上栏（卡片模式顶栏）完全静止时是否淡到完全透明（关=停在半透明 30%） */
    val topBarAutoHide: Boolean = false,
    /** 全局圆角基准值（dp），驱动所有卡片/列表/徽章的圆角 */
    val cornerBase: Float = 38f,
    /** 全局字号缩放倍率，1.0 为系统默认 */
    val fontScale: Float = 1f,
    /** 音乐渐入渐出开关 */
    val fadeEnabled: Boolean = false,
    /** 渐变时长（毫秒），默认 2 秒 */
    val fadeMs: Long = 2000L,
    /** 触感反馈开关 */
    val hapticsEnabled: Boolean = true,
    /** 歌单页显示"为你推荐"区域 */
    val showRecommendations: Boolean = true,
    /** 上栏排序行显示开关（关闭后上栏不再展开排序胶囊） */
    val showSortBar: Boolean = true,
    /** 无声略过（beta）：自动跳过曲首/曲尾静音段 */
    val silenceSkip: Boolean = false,
    /** 低音马达震动（beta · 多维听感） */
    val bassHaptics: Boolean = false,
    /** 震动强度档位：0=轻点 1=标准 2=重击 */
    val bassHapticsIntensity: Int = 1,
    /** 单次震动时长 ms，1-30 */
    val bassHapticsPulseMs: Int = 20,
    /** 触发灵敏度 1-10，越大越容易触发 */
    val bassHapticsSensitivity: Int = 5,
    /** 低音截止频率 Hz：多少 Hz 以下算作低音，120–320，默认 250 */
    val bassHapticsLowCutoff: Int = 250,
    /** 自适应：时长/灵敏度跟随音乐实时变化 */
    val bassHapticsAdaptive: Boolean = false,
    /** 虚拟低音（V2.2 · beta） */
    val virtualBass: Boolean = false,
    /** 虚拟低音混合强度 0-100 */
    val virtualBassStrength: Int = 50,
    /** 动态范围增强（V2.2 · beta） */
    val rangeEnhancer: Boolean = false,
    /** 动态范围增强强度 0-100 */
    val rangeEnhancerStrength: Int = 70,
    /** 虚拟环绕（V2.2 · beta）：立体声声场展宽 */
    val virtualSurround: Boolean = false,
    /** 虚拟环绕强度 0-100 */
    val virtualSurroundStrength: Int = 40,
    /** 宽场环绕（V4.4 · beta）：多频段展宽 + 早期反射，进一步提升空间感 */
    val spatialWide: Boolean = false,
    /** 宽场环绕强度 0-100 */
    val spatialWideStrength: Int = 50,
    /** 防失真限幅（V3.10）：防止推高/增强后削波撕裂 */
    val safeLimiter: Boolean = false,
    /** 防失真限幅强度 0-100（越大余量越足、越安全） */
    val safeLimiterStrength: Int = 30,
    /** 曲间响度均衡（V3.11）：在线测响度、按曲拉平 */
    val loudnorm: Boolean = false,
    /** 拉平力度 0-100（100=完全对齐 -14 LUFS） */
    val loudnormStrength: Int = 70,
    /** 自定义主题主色（ARGB_8888，仅 theme==CUSTOM 时生效） */
    val customPrimaryArgb: Long = 0xFF00F0FFL,
    /** 自定义主题次色（ARGB_8888，仅 theme==CUSTOM 时生效） */
    val customSecondaryArgb: Long = 0xFFFF2E97L,
    /** 自定义背景图 URI（SAF 持久化 URI）；null = 用专辑封面做背景 */
    val customBackgroundUri: String? = null,
    /**
     * 限定扫描目录的绝对路径；null = 不限定（扫描整个共享存储）。
     * 开启后曲库只包含该目录及子目录内的音频。
     */
    val scanRoot: String? = null,

    // ================= 新增：全屏歌词主题与悬浮歌词配置 =================
    /** 全屏歌词颜色总开关 */
    val lyricUseCustomColor: Boolean = false,
    /** 全屏歌词颜色主题 */
    val lyricTheme: IrisTheme = IrisTheme.MONO,
    /** 全屏歌词自定义主色 */
    val lyricCustomPrimary: Long = 0xFF00F0FFL,
    /** 全屏歌词自定义次色 */
    val lyricCustomSecondary: Long = 0xFFFF2E97L,
    
    /** 悬浮歌词颜色总开关 */
    val floatLyricUseCustomColor: Boolean = false,
    /** 悬浮歌词逐字流光开关 */
    val floatLyricKaraoke: Boolean = false,
    /** 悬浮歌词固定开关 */
    val floatLyricFixed: Boolean = false,
    /** 悬浮歌词固定模式：0=居中(仅上下滑) 1=完全固定 */
    val floatLyricFixMode: Int = 0,
    /** 悬浮歌词主题颜色 */
    val floatLyricTheme: IrisTheme = IrisTheme.MONO,
    /** 悬浮歌词自定义主色 */
    val floatLyricCustomPrimary: Long = 0xFF00F0FFL,
    /** 悬浮歌词自定义次色 */
    val floatLyricCustomSecondary: Long = 0xFFFF2E97L,

    /** 启动动画：每次冷启动都播放 Hello 书写动画（关则不播） */
    val splashEnabled: Boolean = true,
    /** 启动动画样式：0=简约(原版) 1=温柔(帽檐式) 2=细腻(尾巴式) */
    val splashStyle: Int = 0
) {
    val currentSong: Song? get() = queue.getOrNull(currentIndex)

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    init {
        // 必须在下面读 prefs 之前：新装首启把内置默认设置先灌进 iris_prefs，
        // 否则这一批 readXxx() 读到的还是代码硬编码值，首帧会闪一下旧样式。
        DataTransfer.seedDefaultSettingsIfFirstRun(app)
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        PlayerUiState(
            theme = readTheme(),
            mode = readMode(),
            surfaceStyle = readSurfaceStyle(),
            sortOrder = readSortOrder(),
            sortDirection = readSortDirection(),
            layout = readLayout(),
            glassBlur = prefs.getFloat(KEY_GLASS_BLUR, GLASS_BLUR_DEFAULT)
                .coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX),
            bgBlur = prefs.getFloat(KEY_BG_BLUR, BG_BLUR_DEFAULT)
                .coerceIn(BG_BLUR_MIN, BG_BLUR_MAX),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = readRepeatMode(),
            rowSize = prefs.getInt(KEY_ROW_SIZE, 1).coerceIn(0, 2),
            showRowCover = prefs.getBoolean(KEY_ROW_COVER, false),
            showRowArtist = prefs.getBoolean(KEY_ROW_ARTIST, true),
            showRowPlaylist = prefs.getBoolean(KEY_ROW_PLAYLIST, true),
            searchPersistent = prefs.getBoolean(KEY_SEARCH_PERSISTENT, true),
            showLikedBadge = prefs.getBoolean(KEY_LIKED_BADGE, false),
            exploration = prefs.getFloat(KEY_EXPLORATION, 0.3f),
            visualizerEnabled = prefs.getBoolean(KEY_VISUALIZER, false),
            tiltSpectrum = prefs.getBoolean(KEY_TILT_SPECTRUM, false),
            coverShake = prefs.getBoolean(KEY_COVER_SHAKE, false),
            coverLyric = prefs.getBoolean(KEY_COVER_LYRIC, false),
            karaokeLyric = prefs.getBoolean(KEY_KARAOKE_LYRIC, true),
            lyricAlign = prefs.getInt(KEY_LYRIC_ALIGN, 1).coerceIn(0, 2),
            lyricFont = if (prefs.getInt(KEY_LYRIC_FONT, 0) == 1) 1 else 0,
            lyricFontSize = prefs.getFloat(KEY_LYRIC_FONT_SIZE, 20f).coerceIn(14f, 32f),
            uiFont = if (prefs.getInt(KEY_UI_FONT, 0) == 1) 1 else 0,
            topBarAutoHide = prefs.getBoolean(KEY_TOPBAR_AUTOHIDE, false),
            cornerBase = prefs.getFloat(KEY_CORNER_BASE, 38f),
            fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.85f, 1.30f),
            fadeEnabled = prefs.getBoolean(FadeController.KEY_ENABLED, false),
            fadeMs = prefs.getLong(FadeController.KEY_FADE_MS, FadeController.DEFAULT_FADE_MS),
            hapticsEnabled = prefs.getBoolean(Haptics.KEY_ENABLED, true),
            showRecommendations = prefs.getBoolean(KEY_SHOW_RECS, true),
            showSortBar = prefs.getBoolean(KEY_SHOW_SORT_BAR, true),
            jellyAnim = prefs.getBoolean(KEY_JELLY_ANIM, false),
            silenceSkip = prefs.getBoolean(SilenceSkipper.KEY_ENABLED, false),
            bassHaptics = prefs.getBoolean(BassHaptics.KEY_ENABLED, false),
            bassHapticsIntensity = prefs.getInt(BassHaptics.KEY_INTENSITY, 1).coerceIn(0, 2),
            bassHapticsPulseMs = prefs.getInt(BassHaptics.KEY_PULSE_MS, 20).coerceIn(1, 30),
            bassHapticsSensitivity = prefs.getInt(BassHaptics.KEY_SENSITIVITY, 5).coerceIn(1, 10),
            bassHapticsLowCutoff = prefs.getInt(BassHaptics.KEY_LOW_CUTOFF, 250).coerceIn(120, 320),
            bassHapticsAdaptive = prefs.getBoolean(BassHaptics.KEY_ADAPTIVE, false),
            virtualBass = prefs.getBoolean(VirtualBass.KEY_ENABLED, false),
            virtualBassStrength = prefs.getInt(VirtualBass.KEY_STRENGTH, 50).coerceIn(0, 100),
            rangeEnhancer = prefs.getBoolean(RangeEnhancer.KEY_ENABLED, false),
            rangeEnhancerStrength = prefs.getInt(RangeEnhancer.KEY_STRENGTH, 70).coerceIn(0, 100),
            virtualSurround = prefs.getBoolean(VirtualSurround.KEY_ENABLED, false),
            virtualSurroundStrength = prefs.getInt(VirtualSurround.KEY_STRENGTH, 40).coerceIn(0, 100),
            spatialWide = prefs.getBoolean(SpatialWide.KEY_ENABLED, false),
            spatialWideStrength = prefs.getInt(SpatialWide.KEY_STRENGTH, 50).coerceIn(0, 100),
            safeLimiter = prefs.getBoolean(SafeLimiter.KEY_ENABLED, false),
            safeLimiterStrength = prefs.getInt(SafeLimiter.KEY_STRENGTH, 30).coerceIn(0, 100),
            loudnorm = prefs.getBoolean(TrackGain.KEY_ENABLED, false),
            loudnormStrength = prefs.getInt(TrackGain.KEY_STRENGTH, 70).coerceIn(0, 100),
            customPrimaryArgb = prefs.getLong(KEY_CUSTOM_PRIMARY, 0xFF00F0FFL),
            customSecondaryArgb = prefs.getLong(KEY_CUSTOM_SECONDARY, 0xFFFF2E97L),
            customBackgroundUri = prefs.getString(KEY_CUSTOM_BG_URI, null),
            selectedFolders = readSelectedFolders(),
            scanRoot = readScanRoot(),

            // === 新增：初始化全屏歌词与悬浮歌词配置 ===
            lyricUseCustomColor = prefs.getBoolean(KEY_LYRIC_USE_CUSTOM, false),
            lyricTheme = runCatching { IrisTheme.valueOf(prefs.getString(KEY_LYRIC_THEME, IrisTheme.MONO.name)!!) }.getOrDefault(IrisTheme.MONO),
            lyricCustomPrimary = prefs.getLong(KEY_LYRIC_CUSTOM_PRIMARY, 0xFF00F0FFL),
            lyricCustomSecondary = prefs.getLong(KEY_LYRIC_CUSTOM_SECONDARY, 0xFFFF2E97L),
            
            floatLyricUseCustomColor = prefs.getBoolean(FloatingLyric.KEY_USE_CUSTOM, false),
            floatLyricKaraoke = prefs.getBoolean(FloatingLyric.KEY_KARAOKE, false),
            floatLyricFixed = prefs.getBoolean(FloatingLyric.KEY_FIXED, false),
            floatLyricFixMode = prefs.getInt(FloatingLyric.KEY_FIX_MODE, 0),
            floatLyricTheme = runCatching { IrisTheme.valueOf(prefs.getString(FloatingLyric.KEY_THEME, IrisTheme.MONO.name)!!) }.getOrDefault(IrisTheme.MONO),
            floatLyricCustomPrimary = prefs.getLong(FloatingLyric.KEY_CUSTOM_PRIMARY, 0xFF00F0FFL),
            floatLyricCustomSecondary = prefs.getLong(FloatingLyric.KEY_CUSTOM_SECONDARY, 0xFFFF2E97L),

            splashEnabled = prefs.getBoolean(KEY_SPLASH_ENABLED, true),
            splashStyle = prefs.getInt(KEY_SPLASH_STYLE, 0).coerceIn(0, 2)
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var sleepUnsub: (() -> Unit)? = null
    private var searchJob: Job? = null
    private var reportJob: Job? = null

    private var statEntries: List<ListenEntry>? = null

    private var prevSongId: Long = -1L
    private var prevAccumMs: Long = 0L
    private var prevLegStartElapsed: Long = 0L
    private fun prevPlayedMs(): Long {
        val open = if (prevLegStartElapsed > 0L)
            android.os.SystemClock.elapsedRealtime() - prevLegStartElapsed else 0L
        return prevAccumMs + open
    }
    private fun updatePlayLeg(isPlaying: Boolean) {
        if (prevSongId < 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (isPlaying) {
            if (prevLegStartElapsed == 0L) prevLegStartElapsed = now
        } else if (prevLegStartElapsed > 0L) {
            prevAccumMs += now - prevLegStartElapsed
            prevLegStartElapsed = 0L
        }
    }

    private var pendingRestoreId: Long? = null
    private var pendingRestorePos: Long = 0L
    private var lastPersistMs = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayLeg(isPlaying)
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = syncFromController()
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = refreshSystemDark()
        override fun onLowMemory() {}
    }

    init {
        PlayHistory.init(app)
        ListenStats.init(app)
        ArtworkLoader.init(app)
        Playlists.init(app)

        val allPlaylists = Playlists.all()
        val restoredActive = prefs.getLong(KEY_ACTIVE_PLAYLIST, -1L)
            .takeIf { id -> id >= 0 && allPlaylists.any { it.id == id } }
        _state.value = _state.value.copy(
            likedSongIds = PlayHistory.getLikes(),
            playlists = allPlaylists,
            activePlaylistId = restoredActive
        )
        SpectrumAnalyzer.enabled = _state.value.visualizerEnabled
        Haptics.init(app)

        pendingRestoreId = prefs.getLong(KEY_LAST_SONG, -1L).takeIf { it >= 0 }
        pendingRestorePos = prefs.getLong(KEY_LAST_POS, 0L).coerceAtLeast(0L)

        connectController()

        viewModelScope.launch { loadLibrary() }
        viewModelScope.launch { tickPosition() }
        SleepTimer.attach(getApplication())
        if (SleepTimer.endMs > 0L) {
            _state.value = _state.value.copy(
                sleepTimerMs = SleepTimer.totalMs,
                sleepTimerEndMs = SleepTimer.endMs
            )
        }
        sleepUnsub = SleepTimer.addExpireObserver {
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
        }
        viewModelScope.launch(Dispatchers.IO) {
            ListenStats.seedFromLegacy(PlayHistory.getTotalPlayedMs(), PlayHistory.getLastPlayed())
        }

        refreshSystemDark()
        app.registerComponentCallbacks(configCallbacks)
    }

    // ==================== 播放控制 ====================

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            persistPlayback(throttle = false)
        } else {
            if (c.mediaItemCount == 0) pushQueue(startIndex = 0, startPositionMs = 0L)
            c.play()
        }
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3_000) c.seekTo(0L) else c.seekToPrevious()
    }

    fun next() {
        controller?.seekToNext()
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        val duration = c.duration
        if (duration <= 0) return
        val target = (duration * fraction.coerceIn(0f, 1f)).toLong()
        c.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        val queue = _state.value.queue
        if (index !in queue.indices) return
        val wantId = queue[index].id.toString()

        val playIdx = (0 until c.mediaItemCount).firstOrNull { i ->
            c.getMediaItemAt(i).mediaId == wantId
        }

        if (playIdx != null && c.mediaItemCount == queue.size) {
            c.seekTo(playIdx, 0L)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        } else {
            val pinId = if (_state.value.shuffle) queue[index].id else null
            val order = playOrder(queue, pinId)
            val pIdx = if (pinId != null) 0 else index
            c.setMediaItems(order.map { it.toMediaItem() }, pIdx, 0L)
            c.prepare()
        }
        c.play()
        _state.value = _state.value.copy(currentIndex = index)
    }

    fun playSong(song: Song) {
        if (controller == null) return
        val queue = _state.value.queue

        val idx = queue.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            playAt(idx)
            return
        }

        val insertAt = (_state.value.currentIndex + 1).coerceIn(0, queue.size)
        _state.value = _state.value.copy(
            queue = queue.toMutableList().apply { add(insertAt, song) }
        )
        playAt(insertAt)
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffle
        prefs.edit().putBoolean(KEY_SHUFFLE, next).apply()
        _state.value = _state.value.copy(shuffle = next)
        rebuildPlayOrder()
        applyPlaybackModes()
    }

    private fun rebuildPlayOrder() {
        val c = controller ?: return
        val base = _state.value.queue
        if (base.isEmpty()) return
        val cur = _state.value.currentSong
        val order = playOrder(base, if (_state.value.shuffle) cur?.id else null)
        val cIdx = order.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        val playingId = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (playingId != null && order.getOrNull(cIdx)?.id == playingId && c.isPlaying) {
            syncQueueAroundCurrent(c, order, cIdx)
        } else {
            c.setMediaItems(order.map { it.toMediaItem() }, cIdx, c.currentPosition.coerceAtLeast(0L))
            c.prepare()
        }
    }

    private fun playOrder(base: List<Song>, pinId: Long?): List<Song> {
        if (pinId == null) return base
        val curIdx = base.indexOfFirst { it.id == pinId }
        if (curIdx < 0) return base
        val current = base[curIdx]
        val rest = base.filterIndexed { i, _ -> i != curIdx }
        val perm = Recommender.weightedPermutation(rest, exploration = _state.value.exploration)
        return listOf(current) + disperseInitials(perm.map { rest[it] }.toMutableList())
    }

    private fun disperseInitials(order: MutableList<Song>): List<Song> {
        val ini = CharArray(order.size) { k ->
            order[k].title.trim().firstOrNull()?.uppercaseChar() ?: ' '
        }
        for (i in 1 until order.size) {
            if (ini[i] != ini[i - 1]) continue
            val j = (i + 1 until order.size).firstOrNull { k ->
                ini[k] != ini[i - 1] &&
                    (k == i + 1 || i + 1 >= order.size || ini[k] != ini[i + 1]) &&
                    ini[i] != ini[k] &&
                    (k - 1 == i || ini[i] != ini[k - 1]) &&
                    (k + 1 >= order.size || ini[i] != ini[k + 1])
            }
            if (j == null) break
            val t = order[i]; order[i] = order[j]; order[j] = t
            val c = ini[i]; ini[i] = ini[j]; ini[j] = c
        }
        return order
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        prefs.edit().putString(KEY_REPEAT, next.name).apply()
        _state.value = _state.value.copy(repeatMode = next)
        applyPlaybackModes()
    }

    fun cyclePlayMode() {
        val s = _state.value
        val (nextShuffle, nextRepeat) = when {
            s.shuffle -> false to RepeatMode.ALL
            s.repeatMode == RepeatMode.OFF -> true to RepeatMode.OFF
            s.repeatMode == RepeatMode.ALL -> false to RepeatMode.ONE
            else -> false to RepeatMode.OFF
        }
        prefs.edit()
            .putBoolean(KEY_SHUFFLE, nextShuffle)
            .putString(KEY_REPEAT, nextRepeat.name)
            .apply()
        _state.value = s.copy(shuffle = nextShuffle, repeatMode = nextRepeat)
        if (nextShuffle != s.shuffle) {
            rebuildPlayOrder()
        }
        applyPlaybackModes()
    }

    // ==================== 库 / 筛选 ====================

    fun reloadLibrary() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            val songs = MusicRepository.rescanAndReload(getApplication(), _state.value.scanRoot)
            val folders = withContext(Dispatchers.Default) { MusicRepository.buildFolders(songs) }
            _state.value = _state.value.copy(
                allSongs = songs,
                folders = folders,
                loading = false,
                refreshing = false
            )
            applyFilters(resetToFirst = true)
            updateRecommendations()
            refreshReportIfNeeded()
            withContext(Dispatchers.IO) { DataTransfer.attachOrphans(getApplication(), songs) }
        }
    }

    // ==================== 限定扫描目录 ====================

    fun setScanRoot(path: String?) {
        val normalized = path?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (normalized != null && !java.io.File(normalized).isDirectory) return
        prefs.edit().putString(KEY_SCAN_ROOT, normalized.orEmpty()).apply()
        _state.value = _state.value.copy(scanRoot = normalized)
        reloadLibrary()
    }

    fun resolveScanRoot(uri: android.net.Uri): String? =
        ScanPath.resolve(getApplication(), uri)

    fun refreshRecommendations() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true)
        val pool = recommendationPool()
        val exploration = _state.value.exploration
        val recs = Recommender.recommend(pool, exploration = exploration)
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            _state.value = _state.value.copy(
                recommendations = recs,
                recommendationsVersion = _state.value.recommendationsVersion + 1,
                refreshing = false
            )
        }
    }

    fun updateSearch(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            applyFilters(resetToFirst = false)
        }
    }

    fun toggleFolder(path: String) {
        val cur = _state.value.selectedFolders
        val next = if (path in cur) cur - path else cur + path
        _state.value = _state.value.copy(selectedFolders = next)
        saveSelectedFolders(next)
        applyFilters(resetToFirst = true)
        updateRecommendations()
    }

    fun clearFolders() {
        _state.value = _state.value.copy(selectedFolders = emptySet())
        saveSelectedFolders(emptySet())
        applyFilters(resetToFirst = true)
        updateRecommendations()
    }

    // ==================== 自定义歌单 ====================

    private fun refreshPlaylists() {
        _state.value = _state.value.copy(playlists = Playlists.all())
    }

    fun createPlaylist(name: String): Long {
        val id = Playlists.create(name)
        refreshPlaylists()
        selectPlaylist(id)
        return id
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        Playlists.rename(playlistId, newName)
        refreshPlaylists()
    }

    fun deletePlaylist(playlistId: Long) {
        Playlists.delete(playlistId)
        if (_state.value.activePlaylistId == playlistId) {
            _state.value = _state.value.copy(activePlaylistId = null)
            prefs.edit().putLong(KEY_ACTIVE_PLAYLIST, -1L).apply()
            applyFilters(resetToFirst = true)
        }
        refreshPlaylists()
    }

    fun selectPlaylist(playlistId: Long?) {
        _state.value = _state.value.copy(activePlaylistId = playlistId)
        prefs.edit().putLong(KEY_ACTIVE_PLAYLIST, playlistId ?: -1L).apply()
        applyFilters(resetToFirst = true)
    }

    fun addToPlaylist(playlistId: Long, songId: Long) {
        Playlists.addSong(playlistId, songId)
        refreshPlaylists()
        if (_state.value.activePlaylistId == playlistId) applyFilters(resetToFirst = false)
    }

    fun removeFromPlaylist(playlistId: Long, songId: Long) {
        Playlists.removeSong(playlistId, songId)
        refreshPlaylists()
        if (_state.value.activePlaylistId == playlistId) applyFilters(resetToFirst = false)
    }

    fun setSortOrder(order: SortOrder) {
        prefs.edit().putString(KEY_SORT_ORDER, order.name).apply()
        _state.value = _state.value.copy(sortOrder = order)
        applyFilters(resetToFirst = false)
    }
    fun setSortDirection(direction: SortDirection) {
        prefs.edit().putString(KEY_SORT_DIRECTION, direction.name).apply()
        _state.value = _state.value.copy(sortDirection = direction)
        applyFilters(resetToFirst = false)
    }

    // ==================== 设置 ====================

    fun toggleSettings(show: Boolean) {
        _state.value = _state.value.copy(showSettings = show)
    }

    fun setTheme(theme: IrisTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _state.value = _state.value.copy(theme = theme)
    }

    fun setMode(mode: IrisMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _state.value = _state.value.copy(mode = mode)
    }

    fun setSurfaceStyle(style: IrisSurfaceStyle) {
        prefs.edit().putString(KEY_SURFACE_STYLE, style.name).apply()
        _state.value = _state.value.copy(surfaceStyle = style)
    }

    fun setCustomColors(primaryArgb: Long, secondaryArgb: Long) {
        prefs.edit()
            .putLong(KEY_CUSTOM_PRIMARY, primaryArgb)
            .putLong(KEY_CUSTOM_SECONDARY, secondaryArgb)
            .apply()
        _state.value = _state.value.copy(
            customPrimaryArgb = primaryArgb,
            customSecondaryArgb = secondaryArgb
        )
    }

    fun setCustomBackground(uri: android.net.Uri?) {
        if (uri == null) {
            prefs.edit().remove(KEY_CUSTOM_BG_URI).apply()
            _state.value = _state.value.copy(customBackgroundUri = null)
            return
        }
        val ctx = getApplication<android.app.Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs.edit().putString(KEY_CUSTOM_BG_URI, uri.toString()).apply()
        _state.value = _state.value.copy(customBackgroundUri = uri.toString())
    }

    fun setLayout(layout: IrisLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
        _state.value = _state.value.copy(layout = layout)
        if (layout.isDeck && _state.value.currentIndex < 0 && _state.value.queue.isNotEmpty()) {
            _state.value = _state.value.copy(currentIndex = 0)
        }
    }

    fun setGlassBlur(scale: Float) {
        val v = scale.coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX)
        prefs.edit().putFloat(KEY_GLASS_BLUR, v).apply()
        _state.value = _state.value.copy(glassBlur = v)
    }

    fun setBgBlur(dp: Float) {
        val v = dp.coerceIn(BG_BLUR_MIN, BG_BLUR_MAX)
        prefs.edit().putFloat(KEY_BG_BLUR, v).apply()
        _state.value = _state.value.copy(bgBlur = v)
    }

    fun setRowSize(size: Int) {
        val s = size.coerceIn(0, 2)
        prefs.edit().putInt(KEY_ROW_SIZE, s).apply()
        _state.value = _state.value.copy(rowSize = s)
    }

    fun setShowRowCover(show: Boolean) {
        prefs.edit().putBoolean(KEY_ROW_COVER, show).apply()
        _state.value = _state.value.copy(showRowCover = show)
    }
    fun setShowRowArtist(show: Boolean) {
        prefs.edit().putBoolean(KEY_ROW_ARTIST, show).apply()
        _state.value = _state.value.copy(showRowArtist = show)
    }
    fun setShowRowPlaylist(show: Boolean) {
        prefs.edit().putBoolean(KEY_ROW_PLAYLIST, show).apply()
        _state.value = _state.value.copy(showRowPlaylist = show)
    }
    fun setSearchPersistent(on: Boolean) {
        prefs.edit().putBoolean(KEY_SEARCH_PERSISTENT, on).apply()
        _state.value = _state.value.copy(searchPersistent = on)
    }

    fun setShowLikedBadge(show: Boolean) {
        prefs.edit().putBoolean(KEY_LIKED_BADGE, show).apply()
        _state.value = _state.value.copy(showLikedBadge = show)
    }

    fun setVisualizerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VISUALIZER, enabled).apply()
        SpectrumAnalyzer.enabled = enabled
        _state.value = _state.value.copy(visualizerEnabled = enabled)
    }

    fun setPhysicsFx(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TILT_SPECTRUM, enabled).putBoolean(KEY_COVER_SHAKE, enabled).apply()
        _state.value = _state.value.copy(tiltSpectrum = enabled, coverShake = enabled)
    }

    fun setLyricAlign(align: Int) {
        val a = align.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LYRIC_ALIGN, a).apply()
        _state.value = _state.value.copy(lyricAlign = a)
    }
    fun setCoverLyric(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COVER_LYRIC, enabled).apply()
        _state.value = _state.value.copy(coverLyric = enabled)
    }
    fun setKaraokeLyric(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KARAOKE_LYRIC, enabled).apply()
        _state.value = _state.value.copy(karaokeLyric = enabled)
    }
    fun setTopBarAutoHide(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOPBAR_AUTOHIDE, enabled).apply()
        _state.value = _state.value.copy(topBarAutoHide = enabled)
    }

    fun setCornerBase(dp: Float) {
        val v = dp.coerceIn(0f, 44f)
        prefs.edit().putFloat(KEY_CORNER_BASE, v).apply()
        _state.value = _state.value.copy(cornerBase = v)
    }
    fun setFontScale(scale: Float) {
        val v = scale.coerceIn(0.85f, 1.30f)
        prefs.edit().putFloat(KEY_FONT_SCALE, v).apply()
        _state.value = _state.value.copy(fontScale = v)
    }

    fun setUiFont(font: Int) {
        val v = if (font == 1) 1 else 0
        prefs.edit().putInt(KEY_UI_FONT, v).apply()
        _state.value = _state.value.copy(uiFont = v)
    }

    fun setLyricFont(font: Int) {
        val v = font.coerceIn(0, 1)
        prefs.edit().putInt(KEY_LYRIC_FONT, v).apply()
        _state.value = _state.value.copy(lyricFont = v)
    }

    fun setLyricFontSize(size: Float) {
        val v = size.coerceIn(14f, 32f)
        prefs.edit().putFloat(KEY_LYRIC_FONT_SIZE, v).apply()
        _state.value = _state.value.copy(lyricFontSize = v)
    }

    fun setFadeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(FadeController.KEY_ENABLED, enabled).apply()
        FadeController.enabled = enabled
        if (!enabled) FadeController.resetVolume()
        _state.value = _state.value.copy(fadeEnabled = enabled)
    }

    fun setFadeMs(ms: Long) {
        val v = ms.coerceIn(FadeController.MIN_FADE_MS, FadeController.MAX_FADE_MS)
        prefs.edit().putLong(FadeController.KEY_FADE_MS, v).apply()
        FadeController.fadeMs = v
        _state.value = _state.value.copy(fadeMs = v)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Haptics.KEY_ENABLED, enabled).apply()
        Haptics.enabled = enabled
        _state.value = _state.value.copy(hapticsEnabled = enabled)
        if (enabled) Haptics.click()
    }

    fun setShowRecommendations(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_RECS, show).apply()
        _state.value = _state.value.copy(showRecommendations = show)
    }

    fun setShowSortBar(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SORT_BAR, show).apply()
        _state.value = _state.value.copy(showSortBar = show)
    }
    fun setJellyAnim(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_JELLY_ANIM, enabled).apply()
        _state.value = _state.value.copy(jellyAnim = enabled)
    }

    fun setSplashEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPLASH_ENABLED, enabled).apply()
        _state.value = _state.value.copy(splashEnabled = enabled)
    }

    fun setSplashStyle(style: Int) {
        val v = style.coerceIn(0, 2)
        prefs.edit().putInt(KEY_SPLASH_STYLE, v).apply()
        _state.value = _state.value.copy(splashStyle = v)
    }

    fun setSilenceSkip(enabled: Boolean) {
        prefs.edit().putBoolean(SilenceSkipper.KEY_ENABLED, enabled).apply()
        SilenceSkipper.setEnabled(enabled)
        _state.value = _state.value.copy(silenceSkip = enabled)
    }

    fun setBassHaptics(enabled: Boolean) {
        prefs.edit().putBoolean(BassHaptics.KEY_ENABLED, enabled).apply()
        BassHaptics.setEnabled(enabled)
        _state.value = _state.value.copy(bassHaptics = enabled)
    }

    fun setBassHapticsIntensity(level: Int) {
        val lv = level.coerceIn(0, 2)
        prefs.edit().putInt(BassHaptics.KEY_INTENSITY, lv).apply()
        BassHaptics.setIntensity(lv)
        BassHaptics.preview(lv)
        _state.value = _state.value.copy(bassHapticsIntensity = lv)
    }

    fun setBassHapticsPulseMs(ms: Int) {
        val v = ms.coerceIn(1, 30)
        prefs.edit().putInt(BassHaptics.KEY_PULSE_MS, v).apply()
        BassHaptics.setPulseMs(v)
        _state.value = _state.value.copy(bassHapticsPulseMs = v)
    }

    fun setBassHapticsSensitivity(level: Int) {
        val v = level.coerceIn(1, 10)
        prefs.edit().putInt(BassHaptics.KEY_SENSITIVITY, v).apply()
        BassHaptics.setSensitivity(v)
        _state.value = _state.value.copy(bassHapticsSensitivity = v)
    }

    fun setBassHapticsLowCutoff(hz: Int) {
        val v = hz.coerceIn(120, 320)
        prefs.edit().putInt(BassHaptics.KEY_LOW_CUTOFF, v).apply()
        BassHaptics.setLowCutoffHz(v)
        _state.value = _state.value.copy(bassHapticsLowCutoff = v)
    }

    fun setBassHapticsAdaptive(enabled: Boolean) {
        prefs.edit().putBoolean(BassHaptics.KEY_ADAPTIVE, enabled).apply()
        BassHaptics.setAdaptive(enabled)
        _state.value = _state.value.copy(bassHapticsAdaptive = enabled)
    }

    // ==================== V2.2 声音增强 ====================
    fun setVirtualBass(enabled: Boolean) {
        prefs.edit().putBoolean(VirtualBass.KEY_ENABLED, enabled).apply()
        VirtualBass.setEnabled(enabled)
        _state.value = _state.value.copy(virtualBass = enabled)
    }
    fun setVirtualBassStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(VirtualBass.KEY_STRENGTH, x).apply()
        VirtualBass.setStrength(x)
        _state.value = _state.value.copy(virtualBassStrength = x)
    }
    fun setRangeEnhancer(enabled: Boolean) {
        prefs.edit().putBoolean(RangeEnhancer.KEY_ENABLED, enabled).apply()
        RangeEnhancer.setEnabled(enabled)
        _state.value = _state.value.copy(rangeEnhancer = enabled)
    }
    fun setRangeEnhancerStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(RangeEnhancer.KEY_STRENGTH, x).apply()
        RangeEnhancer.setStrength(x)
        _state.value = _state.value.copy(rangeEnhancerStrength = x)
    }
    fun setVirtualSurround(enabled: Boolean) {
        prefs.edit().putBoolean(VirtualSurround.KEY_ENABLED, enabled).apply()
        VirtualSurround.setEnabled(enabled)
        _state.value = _state.value.copy(virtualSurround = enabled)
    }
    fun setVirtualSurroundStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(VirtualSurround.KEY_STRENGTH, x).apply()
        VirtualSurround.setStrength(x)
        _state.value = _state.value.copy(virtualSurroundStrength = x)
    }

    fun setSpatialWide(enabled: Boolean) {
        prefs.edit().putBoolean(SpatialWide.KEY_ENABLED, enabled).apply()
        SpatialWide.setEnabled(enabled)
        _state.value = _state.value.copy(spatialWide = enabled)
    }
    fun setSpatialWideStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(SpatialWide.KEY_STRENGTH, x).apply()
        SpatialWide.setStrength(x)
        _state.value = _state.value.copy(spatialWideStrength = x)
    }

    fun setSafeLimiter(enabled: Boolean) {
        prefs.edit().putBoolean(SafeLimiter.KEY_ENABLED, enabled).apply()
        SafeLimiter.setEnabled(enabled)
        _state.value = _state.value.copy(safeLimiter = enabled)
    }
    fun setSafeLimiterStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(SafeLimiter.KEY_STRENGTH, x).apply()
        SafeLimiter.setStrength(x)
        _state.value = _state.value.copy(safeLimiterStrength = x)
    }
    fun setLoudnorm(enabled: Boolean) {
        prefs.edit().putBoolean(TrackGain.KEY_ENABLED, enabled).apply()
        TrackGain.setEnabled(enabled)
        _state.value = _state.value.copy(loudnorm = enabled)
    }
    fun setLoudnormStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(TrackGain.KEY_STRENGTH, x).apply()
        TrackGain.setStrength(x)
        _state.value = _state.value.copy(loudnormStrength = x)
    }

    // ==================== 新增：颜色与悬浮歌词配置 ====================
    fun setLyricUseCustomColor(on: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_USE_CUSTOM, on).apply()
        _state.value = _state.value.copy(lyricUseCustomColor = on)
    }
    fun setLyricTheme(theme: IrisTheme) {
        prefs.edit().putString(KEY_LYRIC_THEME, theme.name).apply()
        _state.value = _state.value.copy(lyricTheme = theme)
    }
    fun setLyricCustomColors(primary: Long, secondary: Long) {
        prefs.edit().putLong(KEY_LYRIC_CUSTOM_PRIMARY, primary).putLong(KEY_LYRIC_CUSTOM_SECONDARY, secondary).apply()
        _state.value = _state.value.copy(lyricCustomPrimary = primary, lyricCustomSecondary = secondary)
    }

    fun setFloatLyricUseCustomColor(on: Boolean) {
        prefs.edit().putBoolean(FloatingLyric.KEY_USE_CUSTOM, on).apply()
        _state.value = _state.value.copy(floatLyricUseCustomColor = on)
    }
    fun setFloatLyricKaraoke(enabled: Boolean) {
        prefs.edit().putBoolean(FloatingLyric.KEY_KARAOKE, enabled).apply()
        _state.value = _state.value.copy(floatLyricKaraoke = enabled)
    }
    fun setFloatLyricFixed(enabled: Boolean) {
        prefs.edit().putBoolean(FloatingLyric.KEY_FIXED, enabled).apply()
        _state.value = _state.value.copy(floatLyricFixed = enabled)
    }
    fun setFloatLyricFixMode(mode: Int) {
        prefs.edit().putInt(FloatingLyric.KEY_FIX_MODE, mode).apply()
        _state.value = _state.value.copy(floatLyricFixMode = mode)
    }
    fun setFloatLyricTheme(theme: IrisTheme) {
        prefs.edit().putString(FloatingLyric.KEY_THEME, theme.name).apply()
        _state.value = _state.value.copy(floatLyricTheme = theme)
    }
    fun setFloatLyricCustomColors(primary: Long, secondary: Long) {
        prefs.edit().putLong(FloatingLyric.KEY_CUSTOM_PRIMARY, primary).putLong(FloatingLyric.KEY_CUSTOM_SECONDARY, secondary).apply()
        _state.value = _state.value.copy(floatLyricCustomPrimary = primary, floatLyricCustomSecondary = secondary)
    }

    // ==================== 点赞 ====================

    fun toggleLike(songId: Long) {
        PlayHistory.toggleLike(songId)
        _state.value = _state.value.copy(likedSongIds = PlayHistory.getLikes())
        updateRecommendations()
        if (_state.value.onlyLiked) applyFilters(resetToFirst = false)
    }

    fun setOnlyLiked(only: Boolean) {
        _state.value = _state.value.copy(onlyLiked = only)
        applyFilters(resetToFirst = true)
    }

// ==================== 睡眠定时器 ====================

    fun setSleepTimer(minutes: Int) {
        val app = getApplication<Application>()
        if (minutes <= 0) {
            SleepTimer.cancel()
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
            return
        }
        val totalMs = minutes * 60_000L
        val end = SleepTimer.set(app, minutes)
        _state.value = _state.value.copy(sleepTimerMs = totalMs, sleepTimerEndMs = end)
    }

    fun cancelSleepTimer() {
        SleepTimer.cancel()
        if (_state.value.sleepTimerMs > 0) {
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
        }
    }

    // ==================== 听歌报告 ====================

    fun toggleReport(show: Boolean) {
        _state.value = _state.value.copy(showReport = show)
        if (show) loadReport(_state.value.report.range)
    }

    fun setReportRange(range: StatRange) {
        val cached = statEntries
        if (cached != null) {
            _state.value = _state.value.copy(
                report = ListenReportBuilder.build(cached, _state.value.allSongs, range)
            )
        } else {
            loadReport(range)
        }
    }

    fun playSongById(songId: Long) {
        val song = _state.value.allSongs.firstOrNull { it.id == songId } ?: return
        playSong(song)
    }

    private fun loadReport(range: StatRange) {
        reportJob?.cancel()
        if (statEntries == null) {
            _state.value = _state.value.copy(report = _state.value.report.copy(loading = true))
        }
        reportJob = viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { ListenStats.load() }
            statEntries = entries
            val report = withContext(Dispatchers.Default) {
                ListenReportBuilder.build(entries, _state.value.allSongs, range)
            }
            _state.value = _state.value.copy(report = report)
        }
    }

    private fun refreshReportIfNeeded() {
        val entries = statEntries ?: return
        _state.value = _state.value.copy(
            report = ListenReportBuilder.build(entries, _state.value.allSongs, _state.value.report.range)
        )
    }

    // ==================== 内部实现 ====================

    private fun connectController() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, MusicService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()

        future.addListener({
            runCatching {
                val c = future.get()
                c.addListener(listener)
                controller = c
                applyPlaybackModes()

                c.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                    pendingRestoreId = id
                    pendingRestorePos = c.currentPosition.coerceAtLeast(0L)
                }

                if (_state.value.queue.isNotEmpty()) {
                    val restoreId = pendingRestoreId
                    val hit = if (restoreId != null) {
                        _state.value.queue.indexOfFirst { it.id == restoreId }
                    } else -1
                    pendingRestoreId = null
                    val startPos = if (hit >= 0) pendingRestorePos else c.currentPosition.coerceAtLeast(0L)
                    pendingRestorePos = 0L
                    pushQueue(
                        startIndex = if (hit >= 0) hit else _state.value.currentIndex.coerceAtLeast(0),
                        startPositionMs = startPos
                    )
                }
                viewModelScope.launch { warmUpSync() }
            }
        }, MoreExecutors.directExecutor())
    }

    private suspend fun warmUpSync() {
        repeat(10) {
            delay(200)
            syncFromController()
            if (_state.value.currentIndex >= 0) return
        }
    }

    private suspend fun tickPosition() {
        var lastPos = -1L
        while (true) {
            val c = controller
            if (c != null && c.isPlaying) {
                val song = _state.value.currentSong
                val cid = c.currentMediaItem?.mediaId?.toLongOrNull()
                if (song != null && cid != null && song.id != cid) {
                    delay(200)
                    continue
                }
                val pos = c.currentPosition
                if (pos != lastPos) {
                    lastPos = pos
                    _state.value = _state.value.copy(
                        positionMs = pos,
                        durationMs = c.duration.coerceAtLeast(0L)
                    )
                    persistPlayback(throttle = true)
                }
            } else {
                delay(2000)
                continue
            }
            delay(500)
        }
    }

    private fun persistPlayback(throttle: Boolean) {
        val song = _state.value.currentSong ?: return
        val now = System.currentTimeMillis()
        if (throttle && now - lastPersistMs < 5_000L) return
        lastPersistMs = now
        prefs.edit()
            .putLong(KEY_LAST_SONG, song.id)
            .putLong(KEY_LAST_POS, (controller?.currentPosition ?: _state.value.positionMs).coerceAtLeast(0L))
            .apply()
    }

    private fun syncFromController() {
        val c = controller ?: return

        val cid = c.currentMediaItem?.mediaId?.toLongOrNull()
        val newIndex = if (cid != null) _state.value.queue.indexOfFirst { it.id == cid }
            .takeIf { it >= 0 } ?: c.currentMediaItemIndex else c.currentMediaItemIndex
        val newSong = _state.value.queue.getOrNull(newIndex)

        if (newSong != null && cid != null && newSong.id != cid) return

        if (newSong != null && newSong.id != prevSongId && prevSongId >= 0) {
            val playedMs = prevPlayedMs()
            val prevDuration = _state.value.allSongs.firstOrNull { it.id == prevSongId }?.durationMs ?: 0L
            PlayHistory.recordPlay(prevSongId, playedMs, prevDuration)
        }
        if (newSong != null && newSong.id != prevSongId) {
            prevSongId = newSong.id
            prevAccumMs = 0L
            prevLegStartElapsed = if (c.isPlaying) android.os.SystemClock.elapsedRealtime() else 0L
            _state.value = _state.value.copy(currentIndex = newIndex)
            persistPlayback(throttle = false)
        }

        _state.value = _state.value.copy(
            currentIndex = newIndex,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0L)
        )
    }

    private fun updateRecommendations() {
        val pool = recommendationPool()
        if (pool.isEmpty()) return
        val exploration = _state.value.exploration
        viewModelScope.launch {
            val recs = withContext(Dispatchers.Default) {
                Recommender.recommend(pool, exploration = exploration)
            }
            _state.value = _state.value.copy(
                recommendations = recs,
                recommendationsVersion = _state.value.recommendationsVersion + 1
            )
        }
    }

    private fun recommendationPool(): List<Song> {
        val all = _state.value.allSongs
        val sel = _state.value.selectedFolders
        return if (sel.isEmpty()) all else all.filter { it.folderPath in sel }
    }

    private fun onLibraryChanged() = updateRecommendations()

    fun setExploration(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_EXPLORATION, v).apply()
        _state.value = _state.value.copy(exploration = v)
        updateRecommendations()
    }

    private fun applyPlaybackModes() {
        val c = controller ?: return
        c.shuffleModeEnabled = false
        c.repeatMode = when (_state.value.repeatMode) {
            RepeatMode.OFF -> if (_state.value.shuffle) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    private suspend fun loadLibrary() {
        val root = _state.value.scanRoot
        val cached = MusicRepository.loadFromCache(getApplication(), root)
        if (cached.isNotEmpty()) {
            _state.value = _state.value.copy(
                allSongs = cached,
                folders = MusicRepository.buildFolders(cached),
                loading = false
            )
            applyFilters(resetToFirst = true)
            updateRecommendations()
            refreshReportIfNeeded()
        }

        if (cached.isEmpty()) _state.value = _state.value.copy(loading = true)
        val songs = MusicRepository.loadSongs(getApplication(), root)
        if (cached.isNotEmpty() && songs.size == cached.size &&
            songs.map { it.id }.toSet() == cached.map { it.id }.toSet()
        ) {
            _state.value = _state.value.copy(loading = false)
            return
        }
        _state.value = _state.value.copy(
            allSongs = songs,
            folders = MusicRepository.buildFolders(songs),
            likedSongIds = PlayHistory.getLikes(),
            playlists = Playlists.all(),
            loading = false
        )
        applyFilters(resetToFirst = true)
        updateRecommendations()
        refreshReportIfNeeded()
    }

    private fun applyFilters(resetToFirst: Boolean) {
        val s = _state.value
        val playing = s.currentSong

        var list = s.allSongs

        val activePid = _state.value.activePlaylistId
        if (activePid != null) {
            val ids = Playlists.songs(activePid).toSet()
            list = list.filter { it.id in ids }
        } else {
            if (_state.value.onlyLiked) {
                list = list.filter { it.id in _state.value.likedSongIds }
            }

            if (_state.value.selectedFolders.isNotEmpty()) {
                list = list.filter { it.folderPath in _state.value.selectedFolders }
            }
        }

        val query = s.searchQuery.trim().lowercase()
        if (query.isNotEmpty()) {
            list = list.filter {
                it.title.lowercase().contains(query) ||
                    it.artist.lowercase().contains(query) ||
                    it.album.lowercase().contains(query)
            }
        }

        list = when (s.sortOrder) {
            SortOrder.TITLE -> list.sortedBy { it.title.lowercase() }
            SortOrder.ARTIST -> list.sortedBy { it.artist.lowercase() }
            SortOrder.DURATION -> list.sortedBy { it.durationMs }
            SortOrder.RECENT -> list.sortedBy { File(it.filePath).lastModified() }
        }
        if (s.sortDirection == SortDirection.DESCENDING) list = list.asReversed()

        val keptIndex = if (playing != null) list.indexOfFirst { it.id == playing.id } else -1

        val restoreId = pendingRestoreId
        val restoreIndex = if (keptIndex < 0 && restoreId != null) {
            list.indexOfFirst { it.id == restoreId }
        } else -1
        val restorePos = if (restoreIndex >= 0) pendingRestorePos else 0L
        if (controller != null && (restoreIndex >= 0 || list.isNotEmpty())) {
            pendingRestoreId = null
            pendingRestorePos = 0L
        }

        val newIndex = when {
            keptIndex >= 0 -> keptIndex
            restoreIndex >= 0 -> restoreIndex
            list.isEmpty() -> -1
            resetToFirst -> 0
            else -> -1
        }

        _state.value = _state.value.copy(queue = list, currentIndex = newIndex)
        if (playing != null && newIndex < 0 && !resetToFirst) return
        val keepPosition = when {
            keptIndex >= 0 -> controller?.currentPosition ?: 0L
            restoreIndex >= 0 -> restorePos
            else -> 0L
        }
        pushQueue(startIndex = newIndex.coerceAtLeast(0), startPositionMs = keepPosition)
    }

    private fun pushQueue(startIndex: Int, startPositionMs: Long) {
        val c = controller ?: return
        val queue = _state.value.queue

        if (queue.isEmpty()) {
            c.clearMediaItems()
            _state.value = _state.value.copy(currentIndex = -1)
            return
        }

        val index = startIndex.coerceIn(0, queue.lastIndex)

        val playingId = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (playingId != null && queue.getOrNull(index)?.id == playingId && c.isPlaying) {
            syncQueueAroundCurrent(c, queue, index)
            _state.value = _state.value.copy(currentIndex = index)
            return
        }

        val pinId = if (_state.value.shuffle) queue[index].id else null
        val order = playOrder(queue, pinId)
        val playIdx = if (pinId != null) 0 else index
        c.setMediaItems(order.map { it.toMediaItem() }, playIdx, startPositionMs)
        c.prepare()
        _state.value = _state.value.copy(currentIndex = index)
    }

    private fun syncQueueAroundCurrent(c: MediaController, queue: List<Song>, index: Int) {
        val pinId = if (_state.value.shuffle) queue[index].id else null
        val order = playOrder(queue, pinId)
        val playIdx = order.indexOfFirst { it.id == queue[index].id }.coerceAtLeast(0)
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur + 1 < count) c.removeMediaItems(cur + 1, count)
        if (cur > 0) c.removeMediaItems(0, cur)

        val after = order.drop(playIdx + 1)
        val before = order.take(playIdx)
        if (after.isNotEmpty()) c.addMediaItems(after.map { it.toMediaItem() })
        if (before.isNotEmpty()) c.addMediaItems(0, before.map { it.toMediaItem() })
    }

    private fun refreshSystemDark() {
        val dark = (getApplication<Application>().resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        _state.value = _state.value.copy(systemDark = dark)
    }

    private fun readTheme(): IrisTheme =
        runCatching { IrisTheme.valueOf(prefs.getString(KEY_THEME, IrisTheme.MONO.name)!!) }
            .getOrDefault(IrisTheme.MONO)

    private fun readMode(): IrisMode =
        runCatching { IrisMode.valueOf(prefs.getString(KEY_MODE, IrisMode.LIGHT.name)!!) }
            .getOrDefault(IrisMode.LIGHT)

    private fun readSurfaceStyle(): IrisSurfaceStyle {
        val saved = prefs.getString(KEY_SURFACE_STYLE, null)
        if (saved == null) {
            val legacyHaze = prefs.getBoolean(KEY_HAZE, true)
            val migrated = if (legacyHaze) IrisSurfaceStyle.FROSTED else IrisSurfaceStyle.SOLID
            prefs.edit().putString(KEY_SURFACE_STYLE, migrated.name).apply()
            return migrated
        }
        return runCatching { IrisSurfaceStyle.valueOf(saved) }.getOrDefault(IrisSurfaceStyle.SOLID)
    }

    private fun readSortOrder(): SortOrder =
        runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT_ORDER, SortOrder.TITLE.name)!!) }
            .getOrDefault(SortOrder.TITLE)
    private fun readSortDirection(): SortDirection =
        runCatching { SortDirection.valueOf(prefs.getString(KEY_SORT_DIRECTION, SortDirection.ASCENDING.name)!!) }
            .getOrDefault(SortDirection.ASCENDING)
    private fun readLayout(): IrisLayout =
        runCatching { IrisLayout.valueOf(prefs.getString(KEY_LAYOUT, IrisLayout.LIST.name)!!) }
            .getOrDefault(IrisLayout.LIST)

    private fun readSelectedFolders(): Set<String> {
        val raw = prefs.getString(KEY_SELECTED_FOLDERS, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.trim() }.toSet()
    }

    private fun readRepeatMode(): RepeatMode =
        runCatching { RepeatMode.valueOf(prefs.getString(KEY_REPEAT, RepeatMode.OFF.name)!!) }
            .getOrDefault(RepeatMode.OFF)

    private fun saveSelectedFolders(sets: Set<String>) {
        prefs.edit().putString(KEY_SELECTED_FOLDERS, sets.joinToString(",")).apply()
    }

    private fun readScanRoot(): String? =
        prefs.getString(KEY_SCAN_ROOT, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(albumArtUri)
                    .setExtras(
                        android.os.Bundle().apply { putString("file_path", filePath) }
                    )
                    .build()
            )
            .build()

    override fun onCleared() {
        sleepUnsub?.let { it() }; sleepUnsub = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        getApplication<Application>().unregisterComponentCallbacks(configCallbacks)
        super.onCleared()
    }

    private companion object {
        const val PREFS = "iris_prefs"
        private const val KEY_THEME = "theme"
        const val KEY_MODE = "mode"
        const val KEY_HAZE = "haze_enabled"
        const val KEY_SURFACE_STYLE = "surface_style"
        const val KEY_CUSTOM_PRIMARY = "custom_primary"
        const val KEY_CUSTOM_SECONDARY = "custom_secondary"
        const val KEY_CUSTOM_BG_URI = "custom_bg_uri"
        const val KEY_ACTIVE_PLAYLIST = "active_playlist_id"
        const val KEY_LAYOUT = "ui_layout"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_SORT_DIRECTION = "sort_direction"
        const val KEY_GLASS_BLUR = "glass_blur"
        const val KEY_BG_BLUR = "bg_blur"
        const val KEY_ROW_SIZE = "row_size"
        const val KEY_ROW_COVER = "row_cover"
        const val KEY_ROW_ARTIST = "row_artist"
        const val KEY_ROW_PLAYLIST = "row_playlist"
        const val KEY_SEARCH_PERSISTENT = "search_persistent"
        const val KEY_LIKED_BADGE = "liked_badge"
        const val KEY_VISUALIZER = "visualizer_enabled"
        const val KEY_TILT_SPECTRUM = "tilt_spectrum"
        const val KEY_COVER_SHAKE = "cover_shake"
        const val KEY_COVER_LYRIC = "cover_lyric"
        const val KEY_KARAOKE_LYRIC = "karaoke_lyric"
        const val KEY_LYRIC_ALIGN = "lyric_align"
        const val KEY_LYRIC_FONT = "lyric_font"
        const val KEY_LYRIC_FONT_SIZE = "lyric_font_size"
        const val KEY_UI_FONT = "ui_font"
        const val KEY_TOPBAR_AUTOHIDE = "topbar_autohide"
        const val KEY_CORNER_BASE = "corner_base"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_EXPLORATION = "exploration"
        const val KEY_SELECTED_FOLDERS = "selected_folders"
        const val KEY_SCAN_ROOT = "scan_root"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_REPEAT = "repeat_mode"
        const val KEY_LAST_SONG = "last_song_id"
        const val KEY_LAST_POS = "last_position_ms"
        const val KEY_SHOW_RECS = "show_recommendations"
        const val KEY_SHOW_SORT_BAR = "show_sort_bar"
        const val KEY_JELLY_ANIM = "jelly_anim"
        
        // === 新增的 Key ===
        const val KEY_LYRIC_USE_CUSTOM = "lyric_use_custom"
        const val KEY_LYRIC_THEME = "lyric_theme"
        const val KEY_LYRIC_CUSTOM_PRIMARY = "lyric_custom_primary"
        const val KEY_LYRIC_CUSTOM_SECONDARY = "lyric_custom_secondary"
        const val KEY_SPLASH_ENABLED = "splash_enabled"
        const val KEY_SPLASH_STYLE = "splash_style"
    }
}
