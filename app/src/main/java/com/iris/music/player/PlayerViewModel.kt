/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
import com.iris.music.audio.VirtualBass
import com.iris.music.audio.VirtualSurround
import com.iris.music.audio.FadeController
import com.iris.music.audio.SilenceSkipper
import com.iris.music.audio.SpectrumAnalyzer
import com.iris.music.data.ListenEntry
import com.iris.music.data.ListenReport
import com.iris.music.data.ListenReportBuilder
import com.iris.music.data.ListenStats
import com.iris.music.data.MusicFolder
import com.iris.music.data.MusicRepository
import com.iris.music.data.PlayHistory
import com.iris.music.data.Playlist
import com.iris.music.data.Playlists
import com.iris.music.data.Recommender
import com.iris.music.data.RepeatMode
import com.iris.music.data.Song
import com.iris.music.data.SortOrder
import com.iris.music.data.StatRange
import com.iris.music.playback.MusicService
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
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.ALL,
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
    /** 果冻动效：卡片位移/尺寸变化带弹性果冻拉伸 */
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
    /** 全局圆角基准值（dp），驱动所有卡片/列表/徽章的圆角 */
    val cornerBase: Float = 38f,
    /** 音乐渐入渐出开关 */
    val fadeEnabled: Boolean = false,
    /** 渐变时长（毫秒），默认 2 秒 */
    val fadeMs: Long = 2000L,
    /** 触感反馈开关 */
    val hapticsEnabled: Boolean = true,
    /** 歌单页显示"为你推荐"区域 */
    val showRecommendations: Boolean = true,
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
    /** 自定义主题主色（ARGB_8888，仅 theme==CUSTOM 时生效） */
    val customPrimaryArgb: Long = 0xFF00F0FFL,
    /** 自定义主题次色（ARGB_8888，仅 theme==CUSTOM 时生效） */
    val customSecondaryArgb: Long = 0xFFFF2E97L,
    /** 自定义背景图 URI（SAF 持久化 URI）；null = 用专辑封面做背景 */
    val customBackgroundUri: String? = null
) {
    val currentSong: Song? get() = queue.getOrNull(currentIndex)

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        PlayerUiState(
            theme = readTheme(),
            mode = readMode(),
            surfaceStyle = readSurfaceStyle(),
            layout = readLayout(),
            glassBlur = prefs.getFloat(KEY_GLASS_BLUR, GLASS_BLUR_DEFAULT)
                .coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX),
            bgBlur = prefs.getFloat(KEY_BG_BLUR, BG_BLUR_DEFAULT)
                .coerceIn(BG_BLUR_MIN, BG_BLUR_MAX),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = readRepeatMode(),
            rowSize = prefs.getInt(KEY_ROW_SIZE, 1).coerceIn(0, 2),
            showRowCover = prefs.getBoolean(KEY_ROW_COVER, false),
            showLikedBadge = prefs.getBoolean(KEY_LIKED_BADGE, false),
            exploration = prefs.getFloat(KEY_EXPLORATION, 0.3f),
            visualizerEnabled = prefs.getBoolean(KEY_VISUALIZER, false),
            cornerBase = prefs.getFloat(KEY_CORNER_BASE, 38f),
            fadeEnabled = prefs.getBoolean(FadeController.KEY_ENABLED, false),
            fadeMs = prefs.getLong(FadeController.KEY_FADE_MS, FadeController.DEFAULT_FADE_MS),
            hapticsEnabled = prefs.getBoolean(Haptics.KEY_ENABLED, true),
            showRecommendations = prefs.getBoolean(KEY_SHOW_RECS, true),
            jellyAnim = prefs.getBoolean(KEY_JELLY_ANIM, false),
            silenceSkip = prefs.getBoolean(SilenceSkipper.KEY_ENABLED, false),
            bassHaptics = prefs.getBoolean(BassHaptics.KEY_ENABLED, false),
            bassHapticsIntensity = prefs.getInt(BassHaptics.KEY_INTENSITY, 1).coerceIn(0, 2),
            bassHapticsPulseMs = prefs.getInt(BassHaptics.KEY_PULSE_MS, 20).coerceIn(1, 30),
            bassHapticsSensitivity = prefs.getInt(BassHaptics.KEY_SENSITIVITY, 5).coerceIn(1, 10),
            virtualBass = prefs.getBoolean(VirtualBass.KEY_ENABLED, false),
            virtualBassStrength = prefs.getInt(VirtualBass.KEY_STRENGTH, 50).coerceIn(0, 100),
            rangeEnhancer = prefs.getBoolean(RangeEnhancer.KEY_ENABLED, false),
            rangeEnhancerStrength = prefs.getInt(RangeEnhancer.KEY_STRENGTH, 70).coerceIn(0, 100),
            virtualSurround = prefs.getBoolean(VirtualSurround.KEY_ENABLED, false),
            virtualSurroundStrength = prefs.getInt(VirtualSurround.KEY_STRENGTH, 40).coerceIn(0, 100),
            customPrimaryArgb = prefs.getLong(KEY_CUSTOM_PRIMARY, 0xFF00F0FFL),
            customSecondaryArgb = prefs.getLong(KEY_CUSTOM_SECONDARY, 0xFFFF2E97L),
            customBackgroundUri = prefs.getString(KEY_CUSTOM_BG_URI, null),
            selectedFolders = readSelectedFolders()
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var sleepTimerJob: Job? = null
    private var searchJob: Job? = null
    private var reportJob: Job? = null

    /**
     * 听歌明细缓存。切换日/周/月/年只是同一份数据换个聚合口径，
     * 每次都重读文件既慢又没必要；打开弹层时才刷新（见 [toggleReport]）。
     */
    private var statEntries: List<ListenEntry>? = null

    /** 切歌追踪：记录上一首歌的 ID 和开始播放时的时间戳 */
    private var prevSongId: Long = -1L
    private var prevSongStartMs: Long = 0L

    /**
     * 待恢复的曲目与位置。
     *
     * 两种来源：进程复用时播放器里已有的当前曲目，或上次退出时落盘的记录。
     * 库是异步加载的，连上 controller 的那一刻队列还是空的，没法直接定位下标，
     * 所以先把 id 记在这里，等 [applyFilters] 建好队列再对齐。
     */
    private var pendingRestoreId: Long? = null
    private var pendingRestorePos: Long = 0L

    /** 上次把播放进度落盘的时间，用于限流（每 5 秒一次足够） */
    private var lastPersistMs = 0L


    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncFromController()

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

        // 点赞集合在 PlayHistory 初始化后读取（prefs lateinit）
        val allPlaylists = Playlists.all()
        // 恢复上次选中的歌单：存盘里的 ID 必须仍存在于歌单列表才采纳，
        // 否则（歌单已被删）回落到全部视图，避免指向不存在的歌单。
        val restoredActive = prefs.getLong(KEY_ACTIVE_PLAYLIST, -1L)
            .takeIf { id -> id >= 0 && allPlaylists.any { it.id == id } }
        _state.value = _state.value.copy(
            likedSongIds = PlayHistory.getLikes(),
            playlists = allPlaylists,
            activePlaylistId = restoredActive
        )
        // 频谱分析器状态跟随持久化开关（服务可能先于本次读取创建）
        SpectrumAnalyzer.enabled = _state.value.visualizerEnabled
        // 触感与渐变：服务进程内的 FadeController 由 MusicService 自行 init，
        // 这里只需保证 UI 进程的 Haptics 拿到配置
        Haptics.init(app)

        // 上次退出时播的那首歌。冷启动时播放器是空的，只能靠这份记录恢复；
        // 若服务还活着（划后台再回来），connectController 会用播放器里的真实曲目覆盖它。
        pendingRestoreId = prefs.getLong(KEY_LAST_SONG, -1L).takeIf { it >= 0 }
        pendingRestorePos = prefs.getLong(KEY_LAST_POS, 0L).coerceAtLeast(0L)

        connectController()

        viewModelScope.launch { loadLibrary() }
        viewModelScope.launch { tickPosition() }
        // 老版本只有"每首歌累计多久"，没有按天明细。把它折算成一条条记录，
        // 升级用户第一次打开报告才不会是空的（只执行一次，见 seedFromLegacy）。
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
            // 暂停后位置就不动了，轮询不会再写，这里补一次
            persistPlayback(throttle = false)
        } else {
            if (c.mediaItemCount == 0) pushQueue(startIndex = 0, startPositionMs = 0L)
            c.play()
        }
    }

    fun previous() {
        val c = controller ?: return
        // 播放超过 3 秒时“上一首”先回到开头，符合常见播放器手感
        if (c.currentPosition > 3_000) c.seekTo(0L) else c.seekToPrevious()
    }

    fun next() {
        controller?.seekToNext()
    }

    /** 按进度比例跳转，fraction 取值 0f..1f */
    fun seekTo(fraction: Float) {
        val c = controller ?: return
        val duration = c.duration
        if (duration <= 0) return
        val target = (duration * fraction.coerceIn(0f, 1f)).toLong()
        c.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    /** 播放队列中指定位置的歌曲 */
    fun playAt(index: Int) {
        val c = controller ?: return
        val queue = _state.value.queue
        if (index !in queue.indices) return

        // 播放器里的队列是否就是 UI 现在这份：长度相同还不够，
        // 筛选后长度可能碰巧一致而内容不同，那样 seekTo 会播成另一首歌，
        // 所以再核对目标位置上的 mediaId。
        val wantId = queue[index].id.toString()
        val inSync = c.mediaItemCount == queue.size && c.getMediaItemAt(index).mediaId == wantId

        if (inSync) {
            c.seekTo(index, 0L)
            // 上一首解码失败等原因会让播放器停在 IDLE，此时 play() 不生效
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        } else {
            // 不能走 pushQueue——它在"当前曲目仍在播放"时会跳过重写直接返回，
            // 于是播放器一直留着旧队列，用户点哪首都只是高亮变了、声音没换，
            // 看起来就是点了没反应。用户明确点歌时必须以 UI 队列为准重写。
            c.setMediaItems(queue.map { it.toMediaItem() }, index, 0L)
            c.prepare()
        }
        c.play()
        _state.value = _state.value.copy(currentIndex = index)
    }

    /**
     * 按歌曲对象播放（推荐卡片入口）。
     *
     * 不接受"UI 先算好下标再传进来"：推荐基于全库打分，而队列可能被文件夹或
     * 收藏筛选过，推荐的歌不在队列里时 `indexOfFirst` 返回 -1，调用方一句
     * `if (idx >= 0)` 就把点击静默吞掉了。这里改为找不到就把歌插进队列再播。
     */
    fun playSong(song: Song) {
        if (controller == null) return
        val queue = _state.value.queue

        val idx = queue.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            playAt(idx)
            return
        }

        // 插到当前曲目后面，保留用户的筛选条件而不是把筛选清掉
        val insertAt = (_state.value.currentIndex + 1).coerceIn(0, queue.size)
        _state.value = _state.value.copy(
            queue = queue.toMutableList().apply { add(insertAt, song) }
        )
        // 队列变长，playAt 会检测到不同步并重写播放器队列
        playAt(insertAt)
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffle
        prefs.edit().putBoolean(KEY_SHUFFLE, next).apply()
        _state.value = _state.value.copy(shuffle = next)
        if (next) {
            // 开随机：按偏好分加权洗牌队列（喜欢的歌更靠前出现），当前播放的歌保持在首位续播
            shuffleQueueByPreference()
        } else {
            // 关随机：恢复原排序并保留当前播放位置
            applyFilters(resetToFirst = false)
        }
        applyPlaybackModes()
    }

    /** 偏好加权洗牌：当前播放的歌固定排第 0 位（续播不中断），其余按权重随机排列 */
    private fun shuffleQueueByPreference() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val curIdx = _state.value.currentIndex
        val current = queue.getOrNull(curIdx)

        val rest = if (current != null) queue.filterIndexed { i, _ -> i != curIdx } else queue
        val perm = Recommender.weightedPermutation(rest, exploration = _state.value.exploration)
        val shuffled = perm.map { rest[it] }

        val newQueue = if (current != null) listOf(current) + shuffled else shuffled
        val newIndex = if (current != null) 0 else -1
        _state.value = _state.value.copy(queue = newQueue, currentIndex = newIndex)

        // 交给 pushQueue：正在放的那首在新队列里仍是当前曲目，于是它走
        // syncQueueAroundCurrent，只重写前后条目，声音不中断。
        //
        // 原先这里直接 setMediaItems 重写整条时间线。即使当前曲目被放回同一个下标，
        // ExoPlayer 也把它当成一个新条目：丢掉已解码的缓冲、重新 seek 到 keepPos，
        // 于是听到"咔一下停住、再接着播"。切随机的那声停顿就是这么来的。
        pushQueue(
            startIndex = newIndex.coerceAtLeast(0),
            startPositionMs = controller?.currentPosition ?: 0L
        )
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

    // ==================== 库 / 筛选 ====================

    fun reloadLibrary() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            // 刷新 = 触发媒体扫描（新文件可被索引）+ 重新读库
            val songs = MusicRepository.rescanAndReload(getApplication())
            _state.value = _state.value.copy(
                allSongs = songs,
                folders = MusicRepository.buildFolders(songs),
                loading = false,
                refreshing = false
            )
            applyFilters(resetToFirst = true)
            // 队列重建后刷新推荐
            updateRecommendations()
            // 报告里的歌名来自 allSongs，库换了要跟着重算
            refreshReportIfNeeded()
        }
    }

    /** 仅重新计算推荐（不重扫媒体、不改队列） */
    fun refreshRecommendations() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true)
        val allSongs = _state.value.allSongs
        val exploration = _state.value.exploration
        // 纯元数据排序，毫秒级
        val recs = Recommender.recommend(allSongs, exploration = exploration)
        // 延迟 500ms 后重置（保证旋转动画可见）
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
        // 防抖：停止输入 250ms 后才执行过滤，避免每个字符都重写队列
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
    }

    /** 清空文件夹选择（回到全部音乐） */
    fun clearFolders() {
        _state.value = _state.value.copy(selectedFolders = emptySet())
        saveSelectedFolders(emptySet())
        applyFilters(resetToFirst = true)
    }

    // ==================== 自定义歌单 ====================

    /** 刷新歌单列表（增删后同步到 state） */
    private fun refreshPlaylists() {
        _state.value = _state.value.copy(playlists = Playlists.all())
    }

    /** 创建歌单并选中它 */
    fun createPlaylist(name: String): Long {
        val id = Playlists.create(name)
        refreshPlaylists()
        selectPlaylist(id)
        return id
    }

    /** 重命名歌单 */
    fun renamePlaylist(playlistId: Long, newName: String) {
        Playlists.rename(playlistId, newName)
        refreshPlaylists()
    }

    /** 删除歌单；若正选中则回到全部 */
    fun deletePlaylist(playlistId: Long) {
        Playlists.delete(playlistId)
        if (_state.value.activePlaylistId == playlistId) {
            _state.value = _state.value.copy(activePlaylistId = null)
            prefs.edit().putLong(KEY_ACTIVE_PLAYLIST, -1L).apply()
            applyFilters(resetToFirst = true)
        }
        refreshPlaylists()
    }

    /** 选中歌单（null = 回到文件夹/收藏视图） */
    fun selectPlaylist(playlistId: Long?) {
        _state.value = _state.value.copy(activePlaylistId = playlistId)
        prefs.edit().putLong(KEY_ACTIVE_PLAYLIST, playlistId ?: -1L).apply()
        applyFilters(resetToFirst = true)
    }

    /** 把一首歌加入歌单 */
    fun addToPlaylist(playlistId: Long, songId: Long) {
        Playlists.addSong(playlistId, songId)
        refreshPlaylists()
        // 若当前正显示该歌单，需要把新歌加入队列
        if (_state.value.activePlaylistId == playlistId) applyFilters(resetToFirst = false)
    }

    /** 把一首歌从歌单移除 */
    fun removeFromPlaylist(playlistId: Long, songId: Long) {
        Playlists.removeSong(playlistId, songId)
        refreshPlaylists()
        if (_state.value.activePlaylistId == playlistId) applyFilters(resetToFirst = false)
    }

    fun setSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(sortOrder = order)
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

    /** 表面材质：实色 / 毛玻璃 / 液态玻璃 */
    fun setSurfaceStyle(style: IrisSurfaceStyle) {
        prefs.edit().putString(KEY_SURFACE_STYLE, style.name).apply()
        _state.value = _state.value.copy(surfaceStyle = style)
    }

    /** 自定义主题取色：主/次色（ARGB_8888）。调用方通常随后 setTheme(CUSTOM)。 */
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

    /**
     * 自定义背景图。传入 SAF 返回的 content URI；传 null 清除自定义背景、回退专辑封面。
     * 这里尝试持久化读权限，使重启后仍能读取该图；若来源不支持持久授权，
     * 本次会话仍可用，重启后会自动回退到封面（不崩）。
     */
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

    /**
     * 界面排布：纵向列表 / 横向卡片 / 堆叠卡片。
     *
     * 切到卡片模式时把队列位置对齐到一个有效下标：卡片模式没有"未选中"这个状态，
     * currentIndex 还是 -1 的话第一张卡会是空的（冷启动没点过歌就是这个情况）。
     */
    fun setLayout(layout: IrisLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
        _state.value = _state.value.copy(layout = layout)
        if (layout.isDeck && _state.value.currentIndex < 0 && _state.value.queue.isNotEmpty()) {
            _state.value = _state.value.copy(currentIndex = 0)
        }
    }

    /** 模糊浓度倍率，同时作用于毛玻璃半径与液态玻璃各层级半径 */
    fun setGlassBlur(scale: Float) {
        val v = scale.coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX)
        prefs.edit().putFloat(KEY_GLASS_BLUR, v).apply()
        _state.value = _state.value.copy(glassBlur = v)
    }

    /** 背景封面模糊半径（dp）。0 = 封面清晰可见，越大越糊 */
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

    fun setShowLikedBadge(show: Boolean) {
        prefs.edit().putBoolean(KEY_LIKED_BADGE, show).apply()
        _state.value = _state.value.copy(showLikedBadge = show)
    }

    /**
     * 切换可视化频谱。关闭时 SpectrumAnalyzer 会跳过全部 FFT 计算，
     * 因此这里同步开关分析器而不是只改 UI 标志。
     */
    fun setVisualizerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VISUALIZER, enabled).apply()
        SpectrumAnalyzer.enabled = enabled
        _state.value = _state.value.copy(visualizerEnabled = enabled)
    }

    /** 设置全局圆角基准（dp），0=直角，44=接近胶囊 */
    fun setCornerBase(dp: Float) {
        val v = dp.coerceIn(0f, 44f)
        prefs.edit().putFloat(KEY_CORNER_BASE, v).apply()
        _state.value = _state.value.copy(cornerBase = v)
    }

    /**
     * 音乐渐入渐出开关。服务与 UI 同进程，FadeController 的静态字段可直接写，
     * 无需经由 MediaController 传命令。
     */
    fun setFadeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(FadeController.KEY_ENABLED, enabled).apply()
        FadeController.enabled = enabled
        if (!enabled) FadeController.resetVolume()
        _state.value = _state.value.copy(fadeEnabled = enabled)
    }

    /** 渐变时长（毫秒） */
    fun setFadeMs(ms: Long) {
        val v = ms.coerceIn(FadeController.MIN_FADE_MS, FadeController.MAX_FADE_MS)
        prefs.edit().putLong(FadeController.KEY_FADE_MS, v).apply()
        FadeController.fadeMs = v
        _state.value = _state.value.copy(fadeMs = v)
    }

    /** 触感反馈开关 */
    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Haptics.KEY_ENABLED, enabled).apply()
        Haptics.enabled = enabled
        _state.value = _state.value.copy(hapticsEnabled = enabled)
        // 打开时立刻给一次反馈，让用户当场感知强度
        if (enabled) Haptics.click()
    }

    /** 歌单页"为你推荐"区域显示开关 */
    fun setShowRecommendations(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_RECS, show).apply()
        _state.value = _state.value.copy(showRecommendations = show)
    }

    /** 果冻动效开关 */
    fun setJellyAnim(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_JELLY_ANIM, enabled).apply()
        _state.value = _state.value.copy(jellyAnim = enabled)
    }

    /** 无声略过（beta） */
    fun setSilenceSkip(enabled: Boolean) {
        prefs.edit().putBoolean(SilenceSkipper.KEY_ENABLED, enabled).apply()
        SilenceSkipper.setEnabled(enabled)
        _state.value = _state.value.copy(silenceSkip = enabled)
    }

    /** 低音马达震动（beta · 多维听感） */
    fun setBassHaptics(enabled: Boolean) {
        prefs.edit().putBoolean(BassHaptics.KEY_ENABLED, enabled).apply()
        BassHaptics.setEnabled(enabled)
        _state.value = _state.value.copy(bassHaptics = enabled)
    }

    /** 震动强度：0=轻点 1=标准 2=重击 */
    fun setBassHapticsIntensity(level: Int) {
        val lv = level.coerceIn(0, 2)
        prefs.edit().putInt(BassHaptics.KEY_INTENSITY, lv).apply()
        BassHaptics.setIntensity(lv)
        BassHaptics.preview(lv) // 选档即时预览：用该档波形震一下
        _state.value = _state.value.copy(bassHapticsIntensity = lv)
    }

    /** 单次震动时长 ms，1-30 */
    fun setBassHapticsPulseMs(ms: Int) {
        val v = ms.coerceIn(1, 30)
        prefs.edit().putInt(BassHaptics.KEY_PULSE_MS, v).apply()
        BassHaptics.setPulseMs(v)
        _state.value = _state.value.copy(bassHapticsPulseMs = v)
    }

    /** 触发灵敏度 1-10，越大越容易触发 */
    fun setBassHapticsSensitivity(level: Int) {
        val v = level.coerceIn(1, 10)
        prefs.edit().putInt(BassHaptics.KEY_SENSITIVITY, v).apply()
        BassHaptics.setSensitivity(v)
        _state.value = _state.value.copy(bassHapticsSensitivity = v)
    }
    // ==================== V2.2 声音增强 ====================
    /** 虚拟低音（beta · 多维听感）：心理声学谐波合成 */
    fun setVirtualBass(enabled: Boolean) {
        prefs.edit().putBoolean(VirtualBass.KEY_ENABLED, enabled).apply()
        VirtualBass.setEnabled(enabled)
        _state.value = _state.value.copy(virtualBass = enabled)
    }
    /** 虚拟低音混合强度 0-100 */
    fun setVirtualBassStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(VirtualBass.KEY_STRENGTH, x).apply()
        VirtualBass.setStrength(x)
        _state.value = _state.value.copy(virtualBassStrength = x)
    }
    /** 动态范围增强（beta · 多维听感）：压缩 + 化妆增益 */
    fun setRangeEnhancer(enabled: Boolean) {
        prefs.edit().putBoolean(RangeEnhancer.KEY_ENABLED, enabled).apply()
        RangeEnhancer.setEnabled(enabled)
        _state.value = _state.value.copy(rangeEnhancer = enabled)
    }
    /** 动态范围增强强度 0-100 */
    fun setRangeEnhancerStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(RangeEnhancer.KEY_STRENGTH, x).apply()
        RangeEnhancer.setStrength(x)
        _state.value = _state.value.copy(rangeEnhancerStrength = x)
    }
    /** 虚拟环绕（beta · 多维听感）：立体声声场展宽 */
    fun setVirtualSurround(enabled: Boolean) {
        prefs.edit().putBoolean(VirtualSurround.KEY_ENABLED, enabled).apply()
        VirtualSurround.setEnabled(enabled)
        _state.value = _state.value.copy(virtualSurround = enabled)
    }
    /** 虚拟环绕强度 0-100 */
    fun setVirtualSurroundStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(VirtualSurround.KEY_STRENGTH, x).apply()
        VirtualSurround.setStrength(x)
        _state.value = _state.value.copy(virtualSurroundStrength = x)
    }

    // ==================== 点赞 ====================

    fun toggleLike(songId: Long) {
        PlayHistory.toggleLike(songId)
        _state.value = _state.value.copy(likedSongIds = PlayHistory.getLikes())
        // 点赞变化后立即刷新推荐
        updateRecommendations()
        // 收藏筛选模式下点赞取消后要重新过滤队列
        if (_state.value.onlyLiked) applyFilters(resetToFirst = false)
    }

    /** 队列仅显示点赞歌曲（我的收藏） */
    fun setOnlyLiked(only: Boolean) {
        _state.value = _state.value.copy(onlyLiked = only)
        applyFilters(resetToFirst = true)
    }

    // ==================== 睡眠定时器 ====================

    /** 设置睡眠定时器，minutes <= 0 视为取消 */
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return

        val totalMs = minutes * 60_000L
        _state.value = _state.value.copy(
            sleepTimerMs = totalMs,
            sleepTimerEndMs = System.currentTimeMillis() + totalMs
        )
        sleepTimerJob = viewModelScope.launch {
            delay(totalMs)
            controller?.pause()
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (_state.value.sleepTimerMs > 0) {
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
        }
    }

    // ==================== 听歌报告 ====================

    /**
     * 打开/关闭听歌报告。
     *
     * 每次打开都重读一次明细：音乐是在服务进程里记账的，UI 这边缓存下来会越看越旧
     * （挂后台听了一小时回来还是打开前的数字）。读文件 + 聚合都在 IO 线程。
     */
    fun toggleReport(show: Boolean) {
        _state.value = _state.value.copy(showReport = show)
        if (show) loadReport(_state.value.report.range)
    }

    /** 切换日/周/月/年。明细已在内存里就地重算，不再碰磁盘 */
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

    /** 从报告里点歌：按 ID 找回 Song 再走正常播放路径 */
    fun playSongById(songId: Long) {
        val song = _state.value.allSongs.firstOrNull { it.id == songId } ?: return
        playSong(song)
    }

    private fun loadReport(range: StatRange) {
        reportJob?.cancel()
        // loading 只在没有旧数据时置位：切范围时保留上一份，避免整块闪一下"统计中"
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

    /**
     * 库变化后重算已生成的报告。
     *
     * 明细里只有歌曲 ID，标题/艺术家/曲长都要现查 allSongs。库是异步加载的，
     * 若报告先建好，排行榜会整列显示"已移除的歌曲"，刷新库后新增的歌也对不上。
     */
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

                // 服务还活着（划后台再回来、Activity 被重建）：播放器里那首才是真相，
                // 用它覆盖 prefs 里的记录，免得恢复成更早的一首。
                c.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                    pendingRestoreId = id
                    pendingRestorePos = c.currentPosition.coerceAtLeast(0L)
                }

                // 这里不能无条件 pushQueue。连上的瞬间 UI 队列通常还是空的（库在异步加载中），
                // 而 pushQueue 见到空队列会 clearMediaItems，把服务里正在放的队列直接掐掉，
                // 随后 loadLibrary 又从第 0 首重建 —— "划后台回来变回第一首歌"就是这么来的。
                //
                // 队列已就绪才推：这条兜底是必要的，库有可能比连接先加载完，那一次
                // applyFilters 里的 pushQueue 会因为 controller 还是 null 而空转。
                if (_state.value.queue.isNotEmpty()) {
                    // 优先对齐要恢复的那首；找不到就退回队列现有下标
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

    /** 连接建立初期 controller 状态可能还没就绪，短轮询几次直到拿到有效索引 */
    private suspend fun warmUpSync() {
        repeat(10) {
            delay(200)
            syncFromController()
            if (_state.value.currentIndex >= 0) return
        }
    }

    /** 播放进度轮询：仅在播放中且进度变化时刷新，减少无谓重组 */
    private suspend fun tickPosition() {
        var lastPos = -1L
        while (true) {
            val c = controller
            if (c != null && c.isPlaying) {
                // 队列重建窗口（随机/筛选）里索引错位：跳过这一拍，
                // 等下次轮询时队列与播放器对齐了再刷新进度
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
                // 暂停/无控制器：位置不会变，降频到 2s 一次只为感知播放恢复
                delay(2000)
                continue
            }
            delay(500)
        }
    }

    /**
     * 记录"当前在放哪首、放到哪儿"。
     *
     * 进程被杀之后 MediaSession 也没了，重启只能靠这份记录恢复，
     * 否则冷启动永远从第一首开始。写得很频繁没意义，默认每 5 秒落一次。
     */
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
        val newIndex = c.currentMediaItemIndex
        val newSong = _state.value.queue.getOrNull(newIndex)

        // 队列重建中途（随机/筛选/收藏）：UI 队列已换新，controller 索引还指着
        // 旧位置，读到的 position/duration 是脏的——这个瞬间同步会把进度点
        // 抽到别处再弹回来。检测到错位就整轮跳过，等重建完成后事件再对齐。
        val cid = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (newSong != null && cid != null && newSong.id != cid) return

        // 切歌追踪：如果歌曲变了，记录上一首歌的播放时长
        if (newSong != null && newSong.id != prevSongId && prevSongId >= 0) {
            val playedMs = System.currentTimeMillis() - prevSongStartMs
            val prevDuration = _state.value.allSongs.firstOrNull { it.id == prevSongId }?.durationMs ?: 0L
            PlayHistory.recordPlay(prevSongId, playedMs, prevDuration)
        }
        if (newSong != null && newSong.id != prevSongId) {
            prevSongId = newSong.id
            prevSongStartMs = System.currentTimeMillis()
            // 切歌是关键节点，不走限流，立刻落盘
            _state.value = _state.value.copy(currentIndex = newIndex)
            persistPlayback(throttle = false)
        }

        _state.value = _state.value.copy(
            currentIndex = newIndex,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0L)
        )

        // 切歌后不自动刷新推荐——推荐保持稳定，手动刷新或点赞时才更新
    }

    /** 基于用户行为生成推荐（全库独立打分，不依赖当前歌曲） */
    private fun updateRecommendations() {
        val allSongs = _state.value.allSongs
        if (allSongs.isEmpty()) return
        val exploration = _state.value.exploration
        val recs = Recommender.recommend(allSongs, exploration = exploration)
        _state.value = _state.value.copy(
            recommendations = recs,
            recommendationsVersion = _state.value.recommendationsVersion + 1
        )
    }

    /** 库加载完成/刷新后调用 */
    private fun onLibraryChanged() = updateRecommendations()


    fun setExploration(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_EXPLORATION, v).apply()
        _state.value = _state.value.copy(exploration = v)
        // 探索度变化后立即刷新推荐
        updateRecommendations()
    }

    private fun applyPlaybackModes() {
        val c = controller ?: return
        // 随机播放由本 App 自己重排队列实现（[shuffleQueueByPreference] 的偏好加权洗牌），
        // 所以播放器的 shuffleModeEnabled 必须保持关闭。
        // 两者同时开会叠加两层随机：播放器在已洗好的队列上再套一个均匀随机序，
        // 偏好权重被抹掉，而且"下一首"跳到的行和列表里高亮行的下一行不是同一首。
        c.shuffleModeEnabled = false
        c.repeatMode = when (_state.value.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    private suspend fun loadLibrary() {
        val songs = MusicRepository.loadSongs(getApplication())
        _state.value = _state.value.copy(
            allSongs = songs,
            folders = MusicRepository.buildFolders(songs),
            loading = false
        )
        applyFilters(resetToFirst = true)
        // 随机是持久化的：队列刚按排序建好，开着随机就得立刻洗一遍，
        // 否则"随机图标亮着但下一首还是按标题顺序"。
        if (_state.value.shuffle) shuffleQueueByPreference()
        // 首次库加载完成即生成推荐（不依赖当前歌曲）
        updateRecommendations()
        // 库比报告晚到时（打开报告后才扫完库）补一次，避免排行榜显示"已移除的歌曲"
        refreshReportIfNeeded()
    }

    /**
     * 按 文件夹 → 搜索 → 排序 重建队列。
     * 当前曲目仍在结果中时保留它的播放位置，否则按 resetToFirst 决定是否从头开始。
     */
    private fun applyFilters(resetToFirst: Boolean) {
        val s = _state.value
        val playing = s.currentSong

        var list = s.allSongs

        // 自定义歌单优先：选中歌单时只显示歌单内的歌（按加入顺序），
        // 与文件夹/收藏筛选互斥。
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
            // MediaStore 已按 DATE_ADDED DESC 返回，保持原顺序即为“最近添加”
            SortOrder.RECENT -> list
        }

        val keptIndex = if (playing != null) list.indexOfFirst { it.id == playing.id } else -1

        // 首次建队列时按上次那首歌定位。库是异步加载的，队列建好之前 currentSong 一直是 null，
        // 光靠 keptIndex 只能落到 0，于是每次冷启动/回前台都从第一首开始。
        val restoreId = pendingRestoreId
        val restoreIndex = if (keptIndex < 0 && restoreId != null) {
            list.indexOfFirst { it.id == restoreId }
        } else -1
        val restorePos = if (restoreIndex >= 0) pendingRestorePos else 0L
        // 只在真的能把位置喂给播放器时才消费掉这条记录。controller 还没连上时
        // pushQueue 会空转，记录必须留着让 connectController 那边接手。
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

        // 当前曲目仍在队列里则续播原位置，否则用恢复位置（都没有就从头）
        val keepPosition = when {
            keptIndex >= 0 -> controller?.currentPosition ?: 0L
            restoreIndex >= 0 -> restorePos
            else -> 0L
        }
        pushQueue(startIndex = newIndex.coerceAtLeast(0), startPositionMs = keepPosition)
    }

    /** 把当前队列写入 controller；队列为空则清空播放器 */
    private fun pushQueue(startIndex: Int, startPositionMs: Long) {
        val c = controller ?: return
        val queue = _state.value.queue

        if (queue.isEmpty()) {
            c.clearMediaItems()
            _state.value = _state.value.copy(currentIndex = -1)
            return
        }

        val index = startIndex.coerceIn(0, queue.lastIndex)

        // 目标位置正好是当前正在播放的那一首：保住这首不重建，只同步它前后的条目。
        // 原先这里直接 return，播放器会一直留着旧队列——筛选后 UI 显示 30 首、
        // 播放器里还是全库 500 首，自动切歌会放到筛选外的歌，点歌也会因为
        // 队列不同步而失效（看起来就是点了没反应）。
        val playingId = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (playingId != null && queue.getOrNull(index)?.id == playingId && c.isPlaying) {
            syncQueueAroundCurrent(c, queue, index)
            _state.value = _state.value.copy(currentIndex = index)
            return
        }

        c.setMediaItems(queue.map { it.toMediaItem() }, index, startPositionMs)
        c.prepare()
        _state.value = _state.value.copy(currentIndex = index)
    }

    /**
     * 只替换当前曲目之外的条目：正在放的那一首原地留着，播放不中断。
     * 先删尾再删头，否则删头会让当前曲目的下标位移。
     */
    private fun syncQueueAroundCurrent(c: MediaController, queue: List<Song>, index: Int) {
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur + 1 < count) c.removeMediaItems(cur + 1, count)
        if (cur > 0) c.removeMediaItems(0, cur)

        val after = queue.drop(index + 1)
        val before = queue.take(index)
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

    /**
     * 读取材质。v1.41 把「毛玻璃开关」并入材质枚举，老版本的 haze_enabled=true
     * 且材质仍是默认实色时迁移为 FROSTED，避免升级后观感突然变回纯实色。
     */
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

    /** 界面排布。未存过（老版本升级）时为纵向列表，也就是原有观感 */
    private fun readLayout(): IrisLayout =
        runCatching { IrisLayout.valueOf(prefs.getString(KEY_LAYOUT, IrisLayout.LIST.name)!!) }
            .getOrDefault(IrisLayout.LIST)

    private fun readSelectedFolders(): Set<String> {
        val raw = prefs.getString(KEY_SELECTED_FOLDERS, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.trim() }.toSet()
    }

    /** 循环模式：其它开关都持久化了，这两个原先是漏的，重启会回到默认 */
    private fun readRepeatMode(): RepeatMode =
        runCatching { RepeatMode.valueOf(prefs.getString(KEY_REPEAT, RepeatMode.ALL.name)!!) }
            .getOrDefault(RepeatMode.ALL)

    private fun saveSelectedFolders(sets: Set<String>) {
        prefs.edit().putString(KEY_SELECTED_FOLDERS, sets.joinToString(",")).apply()
    }

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
                    // 悬浮歌词在服务侧解析需要真实文件路径（content URI 的 path 不是文件路径），
                    // 用 extras 透传 filePath
                    .setExtras(
                        android.os.Bundle().apply { putString("file_path", filePath) }
                    )
                    .build()
            )
            .build()

    override fun onCleared() {
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
        /** 上次选中的歌单 ID，-1 = 无（全部/文件夹视图）。需持久化，否则重启回到默认全部。 */
        const val KEY_ACTIVE_PLAYLIST = "active_playlist_id"
        const val KEY_LAYOUT = "ui_layout"
        const val KEY_GLASS_BLUR = "glass_blur"
        const val KEY_BG_BLUR = "bg_blur"
        const val KEY_ROW_SIZE = "row_size"
        const val KEY_ROW_COVER = "row_cover"
        const val KEY_LIKED_BADGE = "liked_badge"
        const val KEY_VISUALIZER = "visualizer_enabled"
        const val KEY_CORNER_BASE = "corner_base"
        const val KEY_EXPLORATION = "exploration"
        const val KEY_SELECTED_FOLDERS = "selected_folders"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_REPEAT = "repeat_mode"
        const val KEY_LAST_SONG = "last_song_id"
        const val KEY_LAST_POS = "last_position_ms"
        const val KEY_SHOW_RECS = "show_recommendations"
        const val KEY_JELLY_ANIM = "jelly_anim"
    }
}