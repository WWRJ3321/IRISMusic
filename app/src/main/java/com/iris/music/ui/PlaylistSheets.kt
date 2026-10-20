package com.iris.music.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

import com.iris.music.audio.BassHaptics
import com.iris.music.audio.EqualizerController
import com.iris.music.data.MusicFolder
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.data.RepeatMode
import com.iris.music.data.Playlist
import com.iris.music.data.Playlists
import com.iris.music.data.Song
import com.iris.music.data.formatLabel
import com.iris.music.player.PlayerUiState
import com.iris.music.player.PlayerViewModel
import com.iris.music.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
// ==================== 歌单管理弹层 ====================

/**
 * 歌单管理弹层：查看/新建/删除歌单，切换「文件夹视图 / 歌单视图」。
 *
 * 与设置/均衡器/睡眠定时器同构的自绘覆盖层——遮罩 + 底部玻璃卡片，
 * 复用 [irisSurface] 保证弹层观感统一。
 */
@Composable
internal fun PlaylistSheet(
    state: PlayerUiState,
    colors: IrisColors,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black

    // 新建歌单输入框
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    // 待删除的歌单（二次确认用）
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    // 拖把手下滑关闭
    val drag = rememberSheetDragState()

    // 外层 Box：让「删除二次确认」能覆盖在弹层之上
    Box(Modifier.fillMaxSize()) {
        IrisSheet(
            colors = colors,
            onDismiss = onDismiss,
            drag = drag,
            contentModifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 10.dp, bottom = 24.dp)
        ) {
            SheetDragHandle(drag, onDismiss, colors.card)
            Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("歌单", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (state.activePlaylistId == null) "文件夹视图" else "歌单视图",
                            color = colors.subText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    // 视图切换：全部音乐优先置顶
                    val showAll = state.activePlaylistId == null
                    FolderRow("全部音乐", "${state.allSongs.size} 首", showAll, colors) {
                        onSelect(null)
                    }

                    Spacer(Modifier.height(4.dp))

                    // 「我喜欢」：红心集合以歌单形态出现在同一个列表里，
                    // 数据源仍是 PlayHistory 的红心，不额外占存储。
                    // 恒存在列表第二位，不可删除、不可重命名。
                    val likedCount = state.likedSongIds.size
                    FolderRow(
                        "♥ 我喜欢",
                        "$likedCount 首",
                        state.activePlaylistId == Playlists.LIKED_PLAYLIST_ID,
                        colors
                    ) { onSelect(Playlists.LIKED_PLAYLIST_ID) }

                    Spacer(Modifier.height(6.dp))

                    // 歌单列表
                    if (state.playlists.isEmpty()) {
                        Text(
                            "还没有歌单，点下方「新建歌单」创建一个吧",
                            color = colors.subText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        state.playlists.forEach { p ->
                            PlaylistRow(
                                playlist = p,
                                active = state.activePlaylistId == p.id,
                                colors = colors,
                                onClick = { onSelect(p.id) },
                                onDelete = { pendingDelete = p }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 新建歌单
                    if (showCreate) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = newName,
                                onValueChange = { newName = it },
                                singleLine = true,
                                placeholder = { Text("歌单名称", color = colors.subText, fontSize = 13.sp) },
                                textStyle = TextStyle(color = colors.text, fontSize = 13.sp),
                                modifier = Modifier.weight(1f),
                                shape = IrisShape.item,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC),
                                    unfocusedContainerColor = if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = colors.primary
                                )
                            )
                            Box(
                                Modifier
                                    .height(44.dp)
                                    .clip(IrisShape.item)
                                    .background(accent)
                                    .clickable {
                                        Haptics.click()
                                        val n = newName.trim()
                                        if (n.isNotEmpty()) {
                                            onCreate(n)
                                            newName = ""
                                            showCreate = false
                                        }
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("创建", color = accent.readableTextOn(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(IrisShape.item)
                                .background(colors.card)
                                .clickable { Haptics.tap(); showCreate = true },
                            contentAlignment = Alignment.Center
                         ) {
                             Text("＋ 新建歌单", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                         }
                     }
        }

        // 删除二次确认弹层：覆盖在歌单面板之上
        if (pendingDelete != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { pendingDelete = null }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .irisSurface(GlassLevel.SHEET, colors, IrisShape.item)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "删除歌单「${pendingDelete?.name}」？",
                            color = if (colors.isDark) Color.White else Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "歌单内的 ${pendingDelete?.songCount ?: 0} 首歌不会被删除",
                            color = colors.subText,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 取消
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(IrisShape.item)
                                    .background(colors.card)
                                    .clickable { Haptics.tap(); pendingDelete = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("取消", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            // 确认删除
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(IrisShape.item)
                                    .background(accent)
                                    .clickable {
                                        Haptics.click()
                                        pendingDelete?.let { onDelete(it.id) }
                                        pendingDelete = null
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("删除", color = accent.readableTextOn(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 歌单行：名称 + 歌曲数 + 选中态 + 删除按钮 */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    active: Boolean,
    colors: IrisColors,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(240),
        label = "playlistActive"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .irisSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = IrisShape.item,
                solid = lerp(colors.row, colors.primary.copy(alpha = 0.12f), t),
                accent = if (t > 0.5f) colors.primary else null
            )
            .clickable { Haptics.tap(); onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp).clip(IrisShape.chip)
            .background(lerp(colors.card, colors.primary, t)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), t),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text("${playlist.songCount} 首", color = colors.subText, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        // 删除按钮：X（用主题文字色，深色=白、浅色=黑）
        Box(
            Modifier
                .size(28.dp)
                .clip(IrisShape.chip)
                .background(colors.card)
                .clickable { Haptics.click(); onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(12.dp)) {
                val c = if (colors.isDark) Color.White else Color.Black
                val w = size.minDimension * 0.18f
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

// ==================== 添加到歌单弹层 ====================
/**
 * 「加入歌单」弹层里的一行：歌单名 + 状态文字 + 点按加入/移出。
 * 自定义歌单与「我喜欢」虚拟歌单共用同一套外观与交互。
 */
@Composable
private fun AddRow(
    name: String,
    trailing: String,
    alreadyIn: Boolean,
    colors: IrisColors,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .irisSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = IrisShape.item,
                accent = if (alreadyIn) colors.primary else null
            )
            // 已加入 → 点按移出；未加入 → 点按加入。两边都不关弹层，
            // 状态实时翻转，可在同一屏连续编排多个歌单。
            .clickable { Haptics.tap(); onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            Modifier.weight(1f),
            color = colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            trailing,
            color = if (alreadyIn) accent else colors.subText,
            fontSize = 11.sp,
            fontWeight = if (alreadyIn) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * 长按歌曲后弹出的「添加到歌单」弹层。
 * 列出所有歌单（可勾选加入/已加入态），支持一键新建歌单并把当前歌加入。
 */
@Composable
internal fun AddToPlaylistSheet(
    song: Song,
    state: PlayerUiState,
    colors: IrisColors,
    onDismiss: () -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onAdd: (Long) -> Unit,
    /** 从已加入的歌单移除（弹层不关闭，"已加入"状态实时翻转，可连续操作多个歌单） */
    onRemove: (Long) -> Unit = {}
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    // 拖把手下滑关闭
    val drag = rememberSheetDragState()

    IrisSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        contentModifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))
        Text("加入歌单", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song.title,
                        color = colors.subText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(16.dp))
// 「我喜欢」置顶：它就是歌单体系里那条虚拟歌单，数据源是红心集合。
                    // 行为与自定义歌单完全一致——点心即加入这里，再点移出。
                    val likedIn = song.id in state.likedSongIds
                    AddRow(
                        name = "♥ 我喜欢",
                        trailing = if (likedIn) "已喜欢 · 点按移出" else "点按喜欢",
                        alreadyIn = likedIn,
                        colors = colors,
                        accent = accent,
                        onClick = {
                            if (likedIn) onRemove(Playlists.LIKED_PLAYLIST_ID)
                            else onAdd(Playlists.LIKED_PLAYLIST_ID)
                        }
                    )
                    Spacer(Modifier.height(4.dp))

                    if (state.playlists.isEmpty()) {
                        Text(
                            "还没有歌单，点下方「新建歌单」创建一个吧",
                            color = colors.subText,
                            fontSize = 13.sp
                        )
                    } else {
                        state.playlists.forEach { p ->
                            val alreadyIn = Playlists.contains(p.id, song.id)
                            AddRow(
                                name = p.name,
                                trailing = if (alreadyIn) "已加入 · 点按移出" else "${p.songCount} 首",
                                alreadyIn = alreadyIn,
                                colors = colors,
                                accent = accent,
                                onClick = { if (alreadyIn) onRemove(p.id) else onAdd(p.id) }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 新建歌单并加入
                    if (showCreate) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = newName,
                                onValueChange = { newName = it },
                                singleLine = true,
                                placeholder = { Text("歌单名称", color = colors.subText, fontSize = 13.sp) },
                                textStyle = TextStyle(color = colors.text, fontSize = 13.sp),
                                modifier = Modifier.weight(1f),
                                shape = IrisShape.item,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC),
                                    unfocusedContainerColor = if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = colors.primary
                                )
                            )
                            Box(
                                Modifier
                                    .height(44.dp)
                                    .clip(IrisShape.item)
                                    .background(accent)
                                    .clickable {
                                        Haptics.click()
                                        val n = newName.trim()
                                        if (n.isNotEmpty()) onCreateAndAdd(n)
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("创建并加入", color = accent.readableTextOn(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(IrisShape.item)
                                .background(colors.card)
                                .clickable { Haptics.tap(); showCreate = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("＋ 新建歌单并加入", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
    }
}