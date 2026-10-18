package com.iris.music.ui

import com.iris.music.BuildConfig

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
import com.iris.music.playback.FloatingLyric
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.iris.music.data.DataTransfer
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

// ==================== 设置面板 ====================

@Composable
internal fun SettingsPanel(
    state: PlayerUiState,
    colors: IrisColors,
    viewModel: com.iris.music.player.PlayerViewModel,
    onPickBackground: () -> Unit,
    sheetDrag: SheetDragState,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val panelContext = LocalContext.current
    var scanRootStatus by remember { mutableStateOf("") }
    val folderPickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    val path = viewModel.resolveScanRoot(uri)
    if (path == null) {
        scanRootStatus = "无法获取该文件夹的真实路径，请从本地存储中选择"
    } else {
        val dir = java.io.File(path)
        val probe = runCatching { dir.listFiles() }.getOrNull()
        val isOtherAppSandbox = path.contains("/Android/data/") &&
            !path.contains(panelContext.packageName)
        when {
            isOtherAppSandbox ->
                scanRootStatus = "其他应用的 Android/data 目录系统禁止读取（Android 11+ 限制，与权限无关）。请把音乐移到 Music、Download 等普通文件夹后再选择。"
            probe == null ->
                scanRootStatus = "无法读取该文件夹，请先在下方「存储权限」开启「所有文件访问」后重新选择。"
            else -> {
                val exts = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "wav", "aac", "ape")
                val hasAudio = probe.any { it.isFile && it.extension.lowercase() in exts } ||
                    probe.any { sub ->
                        sub.isDirectory && runCatching {
                            sub.listFiles()?.any { it.isFile && it.extension.lowercase() in exts } == true
                        }.getOrDefault(false)
                    }
                viewModel.setScanRoot(path)
                scanRootStatus = if (hasAudio) "" else "该文件夹（含一层子目录）未发现音频文件；更深层次的子目录会在扫描后收录。"
            }
        }
    }
}
    val headerColor = colors.primary.readableOn(colors.surface, 3.5f)
    Column(
        Modifier
            .fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
        SheetDragHandle(sheetDrag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))
        Text("设置", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))

        // ==================== 外观 ====================
        CollapsibleSection("外观", colors) {

        Text("明暗模式", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeSwatch("深色", state.mode == IrisMode.DARK, colors,
                modifier = Modifier.weight(1f)) { viewModel.setMode(IrisMode.DARK) }
            ModeSwatch("浅色", state.mode == IrisMode.LIGHT, colors,
                modifier = Modifier.weight(1f)) { viewModel.setMode(IrisMode.LIGHT) }
            ModeSwatch("自动", state.mode == IrisMode.AUTO, colors,
                modifier = Modifier.weight(1f)) { viewModel.setMode(IrisMode.AUTO) }
        }

        Spacer(Modifier.height(14.dp))

        Text("主题", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { theme ->
                        ThemeSwatch(
                            theme = theme,
                            selected = state.theme == theme,
                            colors = colors,
                            onClick = { viewModel.setTheme(theme) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        AnimatedVisibility(
            visible = state.theme == IrisTheme.CUSTOM,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(14.dp))
                Text("自定义配色", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "拖动滑块调配主色与次色，立即生效",
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                CustomColorSection(
                    primaryArgb = state.customPrimaryArgb,
                    secondaryArgb = state.customSecondaryArgb,
                    colors = colors,
                    onColorsChange = viewModel::setCustomColors
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("界面材质", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when (state.surfaceStyle) {
                IrisSurfaceStyle.SOLID -> "实色：不透明卡片，对比强、最省电"
                IrisSurfaceStyle.FROSTED -> "毛玻璃：上栏与歌词层背景模糊，卡片仍是实色"
                IrisSurfaceStyle.LIQUID -> "液态玻璃：真折射取样 + 色散 + 镜面内缘，最费性能"
            },
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IrisSurfaceStyle.entries.forEach { style ->
                ModeSwatch(
                    style.label,
                    state.surfaceStyle == style,
                    colors,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setSurfaceStyle(style) }
            }
        }

        AnimatedVisibility(
            visible = state.surfaceStyle != IrisSurfaceStyle.SOLID,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                Text(
                    "模糊浓度 · ${"%.1f".format(state.glassBlur)}×",
                    color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        state.glassBlur < 0.55f -> "清透：几乎能看清背后内容，最省电"
                        state.glassBlur < 0.9f -> "轻薄：淡淡一层雾"
                        state.glassBlur < 1.25f -> "标准（推荐）"
                        state.glassBlur < 1.6f -> "厚重：背景只剩色块"
                        else -> "极厚：糊成纯色，最费性能"
                    },
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                CompactSlider(
                    value = state.glassBlur,
                    onValueChange = { viewModel.setGlassBlur(it) },
                    valueRange = GLASS_BLUR_MIN..GLASS_BLUR_MAX,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        Column(Modifier.padding(top = 14.dp)) {
            Text("背景模糊 · ${state.bgBlur.toInt()}dp", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.bgBlur < 4f -> "关闭：封面原图直接当背景，最清晰也最抢眼"
                    state.bgBlur < 18f -> "轻微：还看得出封面构图"
                    state.bgBlur < 34f -> "适中：只剩色块与轮廓"
                    state.bgBlur < 50f -> "标准（推荐）：纯色氛围底"
                    else -> "极致：几乎是一片纯色"
                },
                color = colors.subText, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            CompactSlider(
                value = state.bgBlur,
                onValueChange = { viewModel.setBgBlur(it) },
                valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )
        }

        Spacer(Modifier.height(14.dp))

        Text("自定义背景", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.customBackgroundUri != null) "已使用自选图片做背景（仍受上方「背景模糊」影响）"
            else "不设则背景跟随当前专辑封面",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f).height(40.dp)
                    .clip(IrisShape.item)
                    .background(if (state.theme == IrisTheme.CUSTOM) colors.primary else colors.primary.copy(alpha = 0.18f))
                    .clickable { Haptics.tap(); onPickBackground() },
                contentAlignment = Alignment.Center
            ) {
                Text("选择图片",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (state.theme == IrisTheme.CUSTOM) colors.primary.readableTextOn() else colors.primary)
            }
            if (state.customBackgroundUri != null) {
                Box(
                    Modifier
                        .weight(1f).height(40.dp)
                        .clip(IrisShape.item)
                        .background(colors.surface)
                        .clickable { Haptics.tap(); viewModel.setCustomBackground(null) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("恢复封面", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("界面圆角", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                state.cornerBase < 4f -> "直角：锐利硬朗"
                state.cornerBase < 16f -> "微圆：克制内敛"
                state.cornerBase < 30f -> "标准圆角"
                state.cornerBase < 40f -> "大圆角：柔和饱满（推荐）"
                else -> "极圆：接近胶囊"
            } + " · ${state.cornerBase.toInt()}dp",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        CompactSlider(
            value = state.cornerBase,
            onValueChange = { viewModel.setCornerBase(it) },
            valueRange = 0f..44f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        var pendingFontScale by remember(state.fontScale) { mutableStateOf(state.fontScale) }
        Text("字号大小", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                pendingFontScale < 0.92f -> "偏小：紧凑省地"
                pendingFontScale < 1.05f -> "标准（推荐）"
                pendingFontScale < 1.18f -> "偏大：清晰易读"
                else -> "特大：醒目护眼"
            } + " · ${(pendingFontScale * 100).toInt()}%",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        CompactSlider(
            value = pendingFontScale,
            onValueChange = { pendingFontScale = it },
            onValueChangeFinished = { viewModel.setFontScale(pendingFontScale) },
            valueRange = 0.85f..1.30f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        Text("界面字体", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeSwatch("系统", state.uiFont == 0, colors, modifier = Modifier.weight(1f)) { viewModel.setUiFont(0) }
            ModeSwatch("衬线", state.uiFont == 1, colors, modifier = Modifier.weight(1f)) { viewModel.setUiFont(1) }
        }
        Spacer(Modifier.height(14.dp))

        Text("界面排布", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when (state.layout) {
                IrisLayout.LIST -> "纵向排布：歌单列表 + 右滑播放页，原有结构"
                IrisLayout.CAROUSEL -> "横向排布：只剩播放卡片，左右滑动翻歌"
                IrisLayout.STACK -> "堆叠排布：卡片叠成一沓，把最上面那张拖走切歌"
                IrisLayout.COMPACT -> "唱片墙：正方形/长方形磁贴组成画布，可自由拖动探索"
            },
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IrisLayout.entries.forEach { layout ->
                LayoutSwatch(
                    layout = layout,
                    selected = state.layout == layout,
                    colors = colors,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setLayout(layout) }
            }
        }

        Spacer(Modifier.height(14.dp))

        AnimatedVisibility(
            visible = !state.layout.isDeck,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Text("列表行样式", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("紧凑", state.rowSize == 0, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setRowSize(0) }
                    ModeSwatch("标准", state.rowSize == 1, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setRowSize(1) }
                    ModeSwatch("宽松", state.rowSize == 2, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setRowSize(2) }
                }

                Spacer(Modifier.height(14.dp))

                SettingToggleRow("歌单行显示封面", state.showRowCover, colors) { viewModel.setShowRowCover(!state.showRowCover) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("歌单行显示歌手", state.showRowArtist, colors,
                    subtitle = "关闭后歌单行只显示歌名") { viewModel.setShowRowArtist(!state.showRowArtist) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("歌单行显示所属歌单", state.showRowPlaylist, colors,
                    subtitle = "关闭后不再显示歌曲所在的收藏歌单") { viewModel.setShowRowPlaylist(!state.showRowPlaylist) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("搜索框常驻", state.searchPersistent, colors,
                    subtitle = "关闭后上栏收起搜索框，点标题展开，闲置几秒自动收起") { viewModel.setSearchPersistent(!state.searchPersistent) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("歌单行显示点赞角标", state.showLikedBadge, colors,
                    subtitle = "已点赞的歌曲在右下角显示爱心") { viewModel.setShowLikedBadge(!state.showLikedBadge) }
            }
        }

        Spacer(Modifier.height(18.dp))

        } // 结束外观

        // ==================== 播放 ====================
        CollapsibleSection("播放", colors) {

        SettingToggleRow(
            "淡入淡出",
            state.fadeEnabled,
            colors,
            subtitle = "开头音量渐起、结尾渐落，暂停也不再突兀"
        ) { viewModel.setFadeEnabled(!state.fadeEnabled) }

        AnimatedVisibility(
            visible = state.fadeEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Text(
                    "渐变时长 · ${"%.1f".format(state.fadeMs / 1000f)} 秒",
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                CompactSlider(
                    value = state.fadeMs.toFloat(),
                    onValueChange = { viewModel.setFadeMs(it.toLong()) },
                    valueRange = 500f..5000f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            "无声略过 · beta",
            state.silenceSkip,
            colors,
            subtitle = "自动跳过歌曲开头和结尾没有声音的部分"
        ) { viewModel.setSilenceSkip(!state.silenceSkip) }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            "上栏静止时隐藏",
            state.topBarAutoHide,
            colors,
            subtitle = "完全静止时上栏淡到全透明，碰一下即回"
        ) { viewModel.setTopBarAutoHide(!state.topBarAutoHide) }

        Spacer(Modifier.height(18.dp))

        } // 结束播放

        // ==================== 歌词 ====================
        CollapsibleSection("歌词", colors) {

        SettingToggleRow(
            "封面歌词",
            state.coverLyric,
            colors,
            subtitle = "封面左下角单行歌词，换句时模糊渐隐渐出"
        ) { viewModel.setCoverLyric(!state.coverLyric) }
        Spacer(Modifier.height(14.dp))

        SettingToggleRow(
            "逐字流光",
            state.karaokeLyric,
            colors,
            subtitle = "全屏歌词当前行按进度扫光点亮（无词级时间时按句长估算）"
        ) { viewModel.setKaraokeLyric(!state.karaokeLyric) }
        
        Spacer(Modifier.height(14.dp))

        // 全屏歌词颜色开关（去掉了“启用”二字）
        SettingToggleRow(
            "全屏歌词颜色",
            state.lyricUseCustomColor,
            colors,
            subtitle = "关闭后恢复原本的纯白/纯黑样式"
        ) { viewModel.setLyricUseCustomColor(!state.lyricUseCustomColor) }

        AnimatedVisibility(
            visible = state.lyricUseCustomColor,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                // 删除了重复的 Text 标题
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).forEach { theme ->
                        ThemeSwatch(theme = theme, selected = state.lyricTheme == theme, colors = colors, onClick = { viewModel.setLyricTheme(theme) }, modifier = Modifier.weight(1f))
                    }
                }
                AnimatedVisibility(visible = state.lyricTheme == IrisTheme.CUSTOM) {
                    Column(Modifier.padding(top = 10.dp)) {
                        CustomColorSection(primaryArgb = state.lyricCustomPrimary, secondaryArgb = state.lyricCustomSecondary, colors = colors, onColorsChange = viewModel::setLyricCustomColors)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Column {
            Text("歌词对齐", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeSwatch("靠左", state.lyricAlign == 0, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricAlign(0) }
                ModeSwatch("居中", state.lyricAlign == 1, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricAlign(1) }
                ModeSwatch("靠右", state.lyricAlign == 2, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricAlign(2) }
            }
        }
        Spacer(Modifier.height(14.dp))

        Column {
            Text("歌词字体", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeSwatch("系统", state.lyricFont == 0, colors, modifier = Modifier.weight(1f)) { viewModel.setLyricFont(0) }
                ModeSwatch("衬线", state.lyricFont == 1, colors, modifier = Modifier.weight(1f)) { viewModel.setLyricFont(1) }
            }
        }
        Spacer(Modifier.height(14.dp))

        Text("歌词字号 · ${state.lyricFontSize.toInt()}sp", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        CompactSlider(
            value = state.lyricFontSize,
            onValueChange = { viewModel.setLyricFontSize(it) },
            valueRange = 14f..32f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        FloatingLyricSection(colors, headerColor)

        Spacer(Modifier.height(18.dp))

        } // 结束歌词

        // ==================== 动效与反馈 ====================
        CollapsibleSection("动效与反馈", colors) {

        SettingToggleRow(
            "触感反馈",
            state.hapticsEnabled,
            colors,
            subtitle = "按键、切歌、拖动滚动条时轻微震动"
        ) { viewModel.setHapticsEnabled(!state.hapticsEnabled) }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            "物理动效 · beta",
            state.tiltSpectrum || state.coverShake,
            colors,
            subtitle = "倾斜频谱 + 摇动封面，传感器驱动"
        ) { viewModel.setPhysicsFx(!(state.tiltSpectrum || state.coverShake)) }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            "灵动动效",
            state.jellyAnim,
            colors,
            subtitle = "按钮、列表、卡片与面板采用更有弹性的过渡"
        ) { viewModel.setJellyAnim(!state.jellyAnim) }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            "低音马达震动 · beta",
            state.bassHaptics,
            colors,
            subtitle = "跟随低频节奏轻微震动，幅度随鼓点强弱变化"
        ) { viewModel.setBassHaptics(!state.bassHaptics) }

        AnimatedVisibility(
            visible = state.bassHaptics,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Text("震动强度", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("轻点", state.bassHapticsIntensity == 0, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(0) }
                    ModeSwatch("标准", state.bassHapticsIntensity == 1, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(1) }
                    ModeSwatch("重击", state.bassHapticsIntensity == 2, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(2) }
                }

                Spacer(Modifier.height(10.dp))

                Spacer(Modifier.height(4.dp))
                SettingToggleRow(
                    "自适应旋律",
                    state.bassHapticsAdaptive,
                    colors,
                    subtitle = "强度随鼓点力度、时长随鼓点长短自动变化"
                ) { viewModel.setBassHapticsAdaptive(!state.bassHapticsAdaptive) }

                AnimatedVisibility(
                    visible = !state.bassHapticsAdaptive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Text("单次震动时长", color = colors.subText, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val dur = state.bassHapticsPulseMs
                            ModeSwatch("短", dur <= 6, colors,
                                modifier = Modifier.weight(1f)) {
                                viewModel.setBassHapticsPulseMs(4); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                            ModeSwatch("中", dur > 6 && dur < 15, colors,
                                modifier = Modifier.weight(1f)) {
                                viewModel.setBassHapticsPulseMs(10); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                            ModeSwatch("长", dur >= 15, colors,
                                modifier = Modifier.weight(1f)) {
                                viewModel.setBassHapticsPulseMs(18); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text("触发灵敏度", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sens = state.bassHapticsSensitivity
                    ModeSwatch("低", sens <= 3, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsSensitivity(3) }
                    ModeSwatch("中", sens > 3 && sens < 7, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsSensitivity(5) }
                    ModeSwatch("高", sens >= 7, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsSensitivity(8) }
                }

                Spacer(Modifier.height(12.dp))

                Text("低音范围（截止频率）", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${state.bassHapticsLowCutoff}Hz 以下算作低音（走沉震），以上走脆震",
                    color = colors.subText, fontSize = 10.sp
                )
                Spacer(Modifier.height(6.dp))
                CompactSlider(
                    value = (state.bassHapticsLowCutoff - 120) / 200f,
                    onValueChange = {
                        val hz = (120 + (it * 200f)).toInt().let { v -> (v / 10) * 10 }.coerceIn(120, 320)
                        viewModel.setBassHapticsLowCutoff(hz)
                    },
                    valueRange = 0f..1f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        } // 结束动效与反馈

        // ==================== 推荐 ====================
        CollapsibleSection("推荐", colors) {

        Text("推荐探索度", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.exploration < 0.2f) "贴合口味：按相似度和历史喜好推荐"
            else if (state.exploration < 0.5f) "均衡：偏好为主，偶尔新鲜"
            else if (state.exploration < 0.8f) "探索为主：多推没听过的风格"
            else "全随机：完全探索未知",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        CompactSlider(
            value = state.exploration,
            onValueChange = { viewModel.setExploration(it) },
            valueRange = 0f..1f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )

        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "歌单页显示推荐区",
            state.showRecommendations,
            colors,
            subtitle = "关闭后歌单页只显示歌曲列表"
        ) { viewModel.setShowRecommendations(!state.showRecommendations) }

        Spacer(Modifier.height(18.dp))

        } // 结束推荐

        // ==================== 数据 ====================
        CollapsibleSection("数据", colors) {

        Text("指定文件夹", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.scanRoot != null) "当前限定：${state.scanRoot}\n曲库只包含该文件夹之内的歌曲，推荐与搜索同步收窄"
            else "不限定则扫描整个共享存储；选择后只显示该文件夹之内（含子文件夹）的歌曲",
            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f).height(40.dp)
                    .clip(IrisShape.item)
                    .background(colors.primary.copy(alpha = 0.18f))
                    .clickable { Haptics.tap(); folderPickerLauncher.launch(null) },
                contentAlignment = Alignment.Center
            ) {
                Text("选择文件夹", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
            }
            if (state.scanRoot != null) {
                Box(
                    Modifier
                        .weight(1f).height(40.dp)
                        .clip(IrisShape.item)
                        .background(colors.surface)
                        .clickable { Haptics.tap(); viewModel.setScanRoot(null) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("恢复全部", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                }
            }
        }
        if (scanRootStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(scanRootStatus, color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        SettingToggleRow(
            "上栏显示排序",
            state.showSortBar,
            colors,
            subtitle = "关闭后上栏不再展开排序选项，界面更简洁"
        ) { viewModel.setShowSortBar(!state.showSortBar) }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
            && !android.os.Environment.isExternalStorageManager()) {
            Spacer(Modifier.height(12.dp))
            Text("存储权限", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("未授予「所有文件访问」，曲库可能漏掉部分歌曲。", color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth().height(40.dp)
                    .clip(IrisShape.item)
                    .background(colors.primary.copy(alpha = 0.18f))
                    .clickable {
                        Haptics.tap()
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${panelContext.packageName}")
                        )
                        runCatching { panelContext.startActivity(intent) }.onFailure {
                            runCatching {
                                panelContext.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("去开启", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("音乐文件夹", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.heightIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                FolderRow("全部音乐", "${state.folders.sumOf { it.songCount }} 首",
                    state.selectedFolders.isEmpty() && !state.onlyLiked, colors) { viewModel.clearFolders(); viewModel.setOnlyLiked(false) }
            }
            item {
                FolderRow("我的收藏", "${state.likedSongIds.size} 首",
                    state.onlyLiked, colors) { viewModel.setOnlyLiked(true) }
            }
            itemsIndexed(state.folders, key = { _, f -> "folder-${f.path}" }) { _, f ->
                FolderRow(f.name, "${f.songCount} 首",
                    f.path in state.selectedFolders, colors) { viewModel.toggleFolder(f.path) }
            }
        }

        Spacer(Modifier.height(18.dp))

        } // 结束数据

        // ==================== 关于 ====================
        CollapsibleSection("关于", colors) {

        Text(
            "IRIS Music v${BuildConfig.VERSION_NAME}\n纯本地音乐播放器 · 不联网不上传\nKotlin + Jetpack Compose 构建",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(14.dp))

        // 启动动画开关（原「检查更新」已彻底移除）
        SettingToggleRow(
            "启动动画",
            state.splashEnabled,
            colors,
            subtitle = "开启后每次打开 App 播放 Hello 书写动画"
        ) { viewModel.setSplashEnabled(!state.splashEnabled) }

        Spacer(Modifier.height(18.dp))

        } // 结束关于

        // ==================== 我的数据 ====================
        CollapsibleSection("我的数据", colors) {

        DataTransferSection(colors, state.allSongs)

        } // 结束我的数据

        Spacer(Modifier.height(24.dp))
        } // ===== 结束可滚动内容 =====

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(IrisShape.item)
                .background(colors.primary)
                .clickable { Haptics.click(); onDismiss() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("完成", color = colors.primary.readableTextOn(),
                fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionHeader(text: String, colors: IrisColors) {
    Text(
        text,
        color = colors.text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Black
    )
}

@Composable
private fun CollapsibleSection(
    title: String,
    colors: IrisColors,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(IrisShape.item)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); expanded = !expanded }
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Black)
            val rot by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(220, easing = EaseInOutCubic),
                label = "sectionChevron"
            )
            Canvas(Modifier.size(16.dp).graphicsLayer { rotationZ = rot }) {
                val c = colors.subText
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val stroke = w * 0.12f
                drawLine(
                    color = c,
                    start = Offset(w * 0.38f, h * 0.22f),
                    end = Offset(w * 0.66f, mid),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawLine(
                    color = c,
                    start = Offset(w * 0.66f, mid),
                    end = Offset(w * 0.38f, h * 0.78f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 4.dp), content = content)
        }
    }
}

@Composable
private fun DataTransferSection(colors: IrisColors, songs: List<Song>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导出…"
        scope.launch {
            val ok = DataTransfer.export(ctx, uri, songs)
            status = if (ok) "已导出到所选位置" else "导出失败"
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导入…"
        scope.launch {
            status = when (val r = DataTransfer.import(ctx, uri, songs)) {
                is DataTransfer.ImportResult.Success -> {
                    val s = r.summary
                    "导入完成：匹配 ${s.playedSongs} 首、点赞 ${s.likes}、歌单 ${s.playlists}、听歌记录 ${s.listenDays} 天"
                }
                DataTransfer.ImportResult.BadFormat -> "导入失败：文件格式不是 IRIS Music 导出的数据"
                DataTransfer.ImportResult.Failed -> "导入失败：文件无法读取"
            }
            busy = false
        }
    }

    Column {
        Text(
            "把点赞、播放次数、听歌时长和歌单导出成 JSON 文件，换机或重装后导入即可恢复。" +
                "导入按曲目匹配并取较大值合并，重复导入不会翻倍。全程本地读写，不联网。",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f).height(40.dp)
                    .clip(IrisShape.item)
                    .background(if (busy) colors.primary.copy(alpha = 0.18f) else colors.primary)
                    .clickable(enabled = !busy) {
                        Haptics.tap()
                        status = ""
                        exportLauncher.launch(DataTransfer.SUGGESTED_FILE_NAME)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "导出数据",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (busy) colors.primary else colors.primary.readableTextOn()
                )
            }

            Box(
                Modifier
                    .weight(1f).height(40.dp)
                    .clip(IrisShape.item)
                    .background(colors.primary.copy(alpha = 0.18f))
                    .clickable(enabled = !busy) {
                        Haptics.tap()
                        status = ""
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "导入数据",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(status, color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun FloatingLyricSection(colors: IrisColors, headerColor: Color) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_ENABLED, false)) }
    var granted by remember { mutableStateOf(FloatingLyric.hasPermission(ctx)) }
    var font by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_FONT, FloatingLyric.FONT_DEFAULT)) }
    var widthFrac by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_WIDTH, FloatingLyric.WIDTH_DEFAULT)) }
    var bg by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_BG, FloatingLyric.BG_DEFAULT)) }
    
    var useCustomColor by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_USE_CUSTOM, false)) }
    var karaoke by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_KARAOKE, false)) }
    var fixed by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_FIXED, false)) }
    var fixMode by remember { mutableIntStateOf(prefs.getInt(FloatingLyric.KEY_FIX_MODE, 0)) }
    var floatTheme by remember { mutableStateOf(runCatching { IrisTheme.valueOf(prefs.getString(FloatingLyric.KEY_THEME, IrisTheme.MONO.name)!!) }.getOrDefault(IrisTheme.MONO)) }
    var customPrimary by remember { mutableLongStateOf(prefs.getLong(FloatingLyric.KEY_CUSTOM_PRIMARY, 0xFF00F0FFL)) }
    var customSecondary by remember { mutableLongStateOf(prefs.getLong(FloatingLyric.KEY_CUSTOM_SECONDARY, 0xFFFF2E97L)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = FloatingLyric.hasPermission(ctx)
                FloatingLyric.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun writeBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun writeFloat(key: String, v: Float) = prefs.edit().putFloat(key, v).apply()
    fun writeInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    fun writeLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()

    Text("悬浮歌词", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "在桌面/其它应用上方常驻显示当前歌词，单行可拖动移动位置",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(10.dp))
    SettingToggleRow(
        "桌面悬浮歌词",
        enabled,
        colors,
        subtitle = if (!granted) "尚未授予悬浮窗权限" else "常驻前台服务，退到后台也跟随"
    ) {
        val next = !enabled
        enabled = next
        writeBool(FloatingLyric.KEY_ENABLED, next)
        if (next && !FloatingLyric.hasPermission(ctx)) FloatingLyric.requestPermission(ctx)
    }

    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(Modifier.padding(top = 6.dp)) {
            if (!granted) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(IrisShape.item)
                        .background(colors.surface)
                        .clickable { Haptics.tap(); FloatingLyric.requestPermission(ctx) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("去授予悬浮窗权限", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                }
            }

            Spacer(Modifier.height(10.dp))
            // 添加了解释文案
            SettingToggleRow("逐字流光", karaoke, colors, subtitle = "当前行按进度扫光点亮（无词级时间时按句长估算）") {
                karaoke = !karaoke
                writeBool(FloatingLyric.KEY_KARAOKE, karaoke)
            }

            Spacer(Modifier.height(10.dp))
            SettingToggleRow("固定位置", fixed, colors, subtitle = "开启后无法随意拖动") {
                fixed = !fixed
                writeBool(FloatingLyric.KEY_FIXED, fixed)
            }

            AnimatedVisibility(visible = fixed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(top = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeSwatch("居中(仅上下滑)", fixMode == 0, colors, modifier = Modifier.weight(1f)) {
                            fixMode = 0
                            writeInt(FloatingLyric.KEY_FIX_MODE, 0)
                        }
                        ModeSwatch("完全固定", fixMode == 1, colors, modifier = Modifier.weight(1f)) {
                            fixMode = 1
                            writeInt(FloatingLyric.KEY_FIX_MODE, 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            
            // 去掉了“启用”二字
            SettingToggleRow(
                "悬浮歌词颜色",
                useCustomColor,
                colors,
                subtitle = "关闭后恢复原本的纯白样式"
            ) { 
                useCustomColor = !useCustomColor
                writeBool(FloatingLyric.KEY_USE_CUSTOM, useCustomColor)
            }

            AnimatedVisibility(visible = useCustomColor) {
                Column(Modifier.padding(top = 10.dp)) {
                    // 删除了多余的 Text
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).forEach { theme ->
                            ThemeSwatch(
                                theme = theme,
                                selected = floatTheme == theme,
                                colors = colors,
                                onClick = { 
                                    floatTheme = theme
                                    prefs.edit().putString(FloatingLyric.KEY_THEME, theme.name).apply()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    AnimatedVisibility(visible = floatTheme == IrisTheme.CUSTOM) {
                        Column(Modifier.padding(top = 10.dp)) {
                            CustomColorSection(
                                primaryArgb = customPrimary,
                                secondaryArgb = customSecondary,
                                colors = colors,
                                onColorsChange = { p, s ->
                                    customPrimary = p
                                    customSecondary = s
                                    prefs.edit()
                                        .putLong(FloatingLyric.KEY_CUSTOM_PRIMARY, p)
                                        .putLong(FloatingLyric.KEY_CUSTOM_SECONDARY, s)
                                        .apply()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("字号 · ${font.toInt()} sp", color = colors.subText, fontSize = 11.sp)
            CompactSlider(
                value = font,
                onValueChange = { font = it; writeFloat(FloatingLyric.KEY_FONT, it) },
                valueRange = 12f..40f,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )

            Spacer(Modifier.height(10.dp))
            Text("长度 · ${(widthFrac * 100).toInt()}% 屏宽", color = colors.subText, fontSize = 11.sp)
            CompactSlider(
                value = widthFrac,
                onValueChange = { widthFrac = it; writeFloat(FloatingLyric.KEY_WIDTH, it) },
                valueRange = 0.3f..1f,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )

            Spacer(Modifier.height(10.dp))
            Text("黑底透明度 · ${(bg * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
            CompactSlider(
                value = bg,
                onValueChange = { bg = it; writeFloat(FloatingLyric.KEY_BG, it) },
                valueRange = 0f..1f,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    colors: IrisColors,
    subtitle: String? = null,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
            .clickable { Haptics.tap(); onToggle() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = colors.subText, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        val knobAlign by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = IrisMotion.knob(),
            label = "toggleKnob"
        )
        Box(
            Modifier
                .size(40.dp, 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) colors.primary else colors.subText.copy(alpha = 0.55f))
                .padding(2.dp),
            contentAlignment = androidx.compose.ui.BiasAlignment(
                horizontalBias = -1f + 2f * knobAlign,
                verticalBias = 0f
            )
        ) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface))
        }
    }
}

@Composable
private fun ModeSwatch(
    label: String,
    selected: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .irisSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = IrisShape.item,
                solid = if (selected) colors.card.copy(alpha = 0.6f) else colors.card,
                accent = if (selected) colors.primary else null
            )
            .clickable { Haptics.tap(); onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) colors.primary.readableOn(colors.card, 3.2f) else colors.subText,
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LayoutSwatch(
    layout: IrisLayout,
    selected: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = colors.primary.readableOn(colors.card, 3.2f)
    Column(
        modifier
            .irisSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = IrisShape.item,
                solid = if (selected) colors.card.copy(alpha = 0.6f) else colors.card,
                accent = if (selected) colors.primary else null
            )
            .clickable { Haptics.tap(); onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(width = 26.dp, height = 34.dp)) {
            val stroke = 1.4.dp.toPx()
            val frame = accent.copy(alpha = if (selected) 0.9f else 0.42f)
            val fill = accent.copy(alpha = if (selected) 0.55f else 0.28f)
            val r = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            val inner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            drawRoundRect(
                color = frame,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )

            val pad = 4.dp.toPx()
            val w = size.width - pad * 2
            val h = size.height - pad * 2

            when (layout) {
                IrisLayout.LIST -> {
                     val gap = 2.5.dp.toPx()
                     val rowH = (h - gap * 4f) / 5f
                     drawRoundRect(fill, Offset(pad, pad), Size(w, rowH * 0.72f), inner)
                     for (row in 0 until 4) {
                         drawRoundRect(fill.copy(alpha = fill.alpha * 0.82f),
                             Offset(pad, pad + rowH * (row + 1.05f)), Size(w, rowH * 0.72f), inner)
                     }
                }

                IrisLayout.CAROUSEL -> {
                    val cw = w * 0.46f
                    val gap = w * 0.10f
                    val cy = pad + h * 0.16f
                    val ch = h * 0.68f
                    for (i in -1..1) {
                        val x = pad + w / 2f - cw / 2f + i * (cw + gap)
                        drawRoundRect(
                            color = if (i == 0) fill else fill.copy(alpha = fill.alpha * 0.5f),
                            topLeft = Offset(x, cy),
                            size = Size(cw, ch),
                            cornerRadius = inner
                        )
                    }
                }

                IrisLayout.STACK -> {
                    val cw = w * 0.62f
                    val ch = h * 0.60f
                    val stepX = w * 0.09f
                    val stepY = h * 0.05f
                    for (d in 2 downTo 0) {
                        drawRoundRect(
                            color = fill.copy(alpha = fill.alpha * (1f - 0.28f * d)),
                            topLeft = Offset(
                                pad + w - cw - stepX * d - w * 0.04f,
                                pad + h / 2f - ch / 2f - stepY * d
                            ),
                            size = Size(cw, ch),
                            cornerRadius = inner
                        )
                    }
                }

IrisLayout.COMPACT -> {
                     val cols = 3
                     val rows = 4
                     val cellGap = 1.6.dp.toPx()
                     val cellW = (w - cellGap * (cols - 1)) / cols
                     val cellH = (h - cellGap * (rows - 1)) / rows
                     for (row in 0 until rows) {
                         for (col in 0 until cols) {
                             val a = if ((row + col) % 2 == 0) fill.alpha else fill.alpha * 0.6f
                             drawRoundRect(
                                 color = fill.copy(alpha = a),
                                 topLeft = Offset(pad + col * (cellW + cellGap), pad + row * (cellH + cellGap)),
                                 size = Size(cellW, cellH),
                                 cornerRadius = inner
                             )
                         }
                     }
                 }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            layout.label,
            color = if (selected) accent else colors.subText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ThemeSwatch(theme: IrisTheme, selected: Boolean, colors: IrisColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (swatchPrimary, swatchSecondary) = theme.resolvedPair(colors.isDark)
    val cardBg = if (selected) swatchPrimary.copy(alpha = 0.15f) else colors.card
    val labelColor = if (selected) swatchPrimary.readableOn(colors.card) else colors.text.copy(alpha = 0.8f)
    Column(
        modifier
            .irisSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = IrisShape.item,
                solid = cardBg,
                accent = if (selected) swatchPrimary else null
            )
            .clickable { Haptics.tap(); onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row {
            val dotBorder = colors.subText.copy(alpha = 0.45f)
            Box(
                Modifier.size(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(swatchPrimary)
                    .border(1.dp, dotBorder, RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(3.dp))
            Box(
                Modifier.size(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(swatchSecondary)
                    .border(1.dp, dotBorder, RoundedCornerShape(6.dp))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            theme.label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ==================== 自定义配色（HSV 取色） ====================

private fun argbOf(hsv: FloatArray): Long =
    (android.graphics.Color.HSVToColor(hsv).toLong() and 0xFFFFFFFFL)

private fun hsvOf(argb: Long): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), out)
    return out
}

@Composable
private fun CustomColorSection(
    primaryArgb: Long,
    secondaryArgb: Long,
    colors: IrisColors,
    onColorsChange: (Long, Long) -> Unit
) {
    var primaryHsv by remember { mutableStateOf(hsvOf(primaryArgb)) }
    var secondaryHsv by remember { mutableStateOf(hsvOf(secondaryArgb)) }
    var editSecondary by remember { mutableStateOf(false) }
    val activeHsv = if (editSecondary) secondaryHsv else primaryHsv
    val onSlider: (Int, Float) -> Unit = { index, v ->
        val updated = activeHsv.copyOf()
        updated[index] = v.coerceIn(0f, if (index == 0) 360f else 1f)
        if (editSecondary) secondaryHsv = updated else primaryHsv = updated
        onColorsChange(argbOf(primaryHsv), argbOf(secondaryHsv))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorPreviewBox(argbOf(primaryHsv), "主色", !editSecondary, colors,
            Modifier.weight(1f)) { editSecondary = false }
        ColorPreviewBox(argbOf(secondaryHsv), "次色", editSecondary, colors,
            Modifier.weight(1f)) { editSecondary = true }
    }
    Spacer(Modifier.height(12.dp))

    Text("色相 · ${activeHsv[0].toInt()}", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    CompactSlider(value = activeHsv[0], onValueChange = { onSlider(0, it) },
        valueRange = 0f..360f, activeColor = colors.primary, inactiveColor = colors.surface)
    Spacer(Modifier.height(8.dp))
    Text("饱和度 · ${(activeHsv[1] * 100).toInt()}%", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    CompactSlider(value = activeHsv[1], onValueChange = { onSlider(1, it) },
        valueRange = 0f..1f, activeColor = colors.primary, inactiveColor = colors.surface)
    Spacer(Modifier.height(8.dp))
    Text("明度 · ${(activeHsv[2] * 100).toInt()}%", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    CompactSlider(value = activeHsv[2], onValueChange = { onSlider(2, it) },
        valueRange = 0f..1f, activeColor = colors.primary, inactiveColor = colors.surface)
}

@Composable
private fun ColorPreviewBox(
    argb: Long,
    label: String,
    selected: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = Color(argb)
    Column(
        modifier
            .height(52.dp)
            .clip(IrisShape.item)
            .background(c)
            .then(if (selected) Modifier.border(2.dp, colors.text, IrisShape.item) else Modifier)
            .clickable { Haptics.tap(); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.readableTextOn())
    }
}
