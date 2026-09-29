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
import android.content.Intent
import android.net.Uri
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
import com.iris.music.data.UpdateChecker
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
    onSelectFolder: (String?) -> Unit,
    onThemeChange: (IrisTheme) -> Unit,
    onModeChange: (IrisMode) -> Unit,
    onRowSizeChange: (Int) -> Unit,
    onRowCoverChange: (Boolean) -> Unit,
    onLikedBadgeChange: (Boolean) -> Unit,
    onLikedFilterChange: (Boolean) -> Unit,
    onExplorationChange: (Float) -> Unit,
    onCornerBaseChange: (Float) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onSurfaceStyleChange: (IrisSurfaceStyle) -> Unit,
    onLayoutChange: (IrisLayout) -> Unit,
    onGlassBlurChange: (Float) -> Unit,
    onBgBlurChange: (Float) -> Unit,
    onFadeChange: (Boolean) -> Unit,
    onFadeMsChange: (Long) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onShowRecsChange: (Boolean) -> Unit,
    onJellyAnimChange: (Boolean) -> Unit,
    onSilenceSkipChange: (Boolean) -> Unit,
    onPhysicsFxChange: (Boolean) -> Unit,
    onCoverLyricChange: (Boolean) -> Unit,
    onKaraokeLyricChange: (Boolean) -> Unit,
    onTopBarAutoHideChange: (Boolean) -> Unit,
    onLyricAlignChange: (Int) -> Unit,
    onBassHapticsChange: (Boolean) -> Unit,
    onBassHapticsIntensityChange: (Int) -> Unit,
    onBassHapticsPulseMsChange: (Int) -> Unit,
    onBassHapticsSensitivityChange: (Int) -> Unit,
    onBassHapticsLowCutoffChange: (Int) -> Unit,
    onBassHapticsAdaptiveChange: (Boolean) -> Unit,
    onCustomColorsChange: (Long, Long) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    sheetDrag: SheetDragState,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    // 主题色直接当文字用，在浅色模式下会翻车：霓虹青/黄落在白色面板上对比度只有 1.5 左右。
    // 分组标题统一走可读修正，深色模式下修正函数会原样返回，不影响原有观感。
    val headerColor = colors.primary.readableOn(colors.surface, 3.5f)
    Column(
        Modifier
            .fillMaxWidth()
    ) {
        // ===== 可滚动的设置内容 =====
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
        SectionHeader("外观", colors)
        Spacer(Modifier.height(10.dp))

        // ---- 明暗模式 ----
        Text("明暗模式", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeSwatch("深色", state.mode == IrisMode.DARK, colors,
                modifier = Modifier.weight(1f)) { onModeChange(IrisMode.DARK) }
            ModeSwatch("浅色", state.mode == IrisMode.LIGHT, colors,
                modifier = Modifier.weight(1f)) { onModeChange(IrisMode.LIGHT) }
            ModeSwatch("自动", state.mode == IrisMode.AUTO, colors,
                modifier = Modifier.weight(1f)) { onModeChange(IrisMode.AUTO) }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 主题 ----
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
                            onClick = { onThemeChange(theme) },
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
            // 只有选了「自定义」主题才展开调色区：进入时 CustomColorSection 重新组合，
            // HSV 从外部 argb 同步；离开时整段移出组合树，避免常显死控件。
            Column {
                Spacer(Modifier.height(14.dp))
                // ---- 自定义配色（紧跟主题）----
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
                    onColorsChange = onCustomColorsChange
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // ---- 表面材质 ----
        // 毛玻璃从独立开关并入这里：三者本质是同一维度（浮层背后怎么处理），
        // 拆成"材质选择 + 毛玻璃开关"会出现"实色 + 毛玻璃""液态玻璃 + 毛玻璃"这类
        // 语义打架的组合，后者还会把折射素材糊掉。
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
                ) { onSurfaceStyleChange(style) }
            }
        }

        // ---- 模糊浓度 ----
        // 只在毛玻璃/液态玻璃下出现：实色没有可调的模糊，摆着是死控件。
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
                    onValueChange = { onGlassBlurChange(it) },
                    valueRange = GLASS_BLUR_MIN..GLASS_BLUR_MAX,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        // ---- 背景模糊 ----
        // 独立于「模糊浓度」：那个管浮层背后的玻璃，这个管最底层封面本身糊多少，
        // 实色材质下也生效，所以不放在上面那个 AnimatedVisibility 里。
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
                onValueChange = { onBgBlurChange(it) },
                valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )
        }

        Spacer(Modifier.height(14.dp))

        Spacer(Modifier.height(14.dp))
        // ---- 自定义背景 ----
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
                        .clickable { Haptics.tap(); onClearBackground() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("恢复封面", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 界面圆角 ----
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
            onValueChange = { onCornerBaseChange(it) },
            valueRange = 0f..44f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        // ---- 字号大小 ----
        // 拖动时只改本地值，松手才提交全局——否则每次 onValueChange 都切 Density，
        // 触发整树重测量打断手势，表现为"拉不动"。
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
            onValueChangeFinished = { onFontScaleChange(pendingFontScale) },
            valueRange = 0.85f..1.30f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        // ---- 界面排布 ----
        // 和材质、配色一样是独立一维：这里决定"整个 App 长什么结构"。
        // 卡片模式下歌单和上栏都不存在，所以下面那些歌单相关的设置项要跟着藏起来。
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
                ) { onLayoutChange(layout) }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 歌单行大小 / 行封面 ----
        // 卡片模式下没有歌单，这两项摆着是死控件
        AnimatedVisibility(
            visible = !state.layout.isDeck,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Text("歌单行大小", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("紧凑", state.rowSize == 0, colors,
                        modifier = Modifier.weight(1f)) { onRowSizeChange(0) }
                    ModeSwatch("标准", state.rowSize == 1, colors,
                        modifier = Modifier.weight(1f)) { onRowSizeChange(1) }
                    ModeSwatch("宽松", state.rowSize == 2, colors,
                        modifier = Modifier.weight(1f)) { onRowSizeChange(2) }
                }

                Spacer(Modifier.height(14.dp))

                SettingToggleRow("歌单行显示封面", state.showRowCover, colors) { onRowCoverChange(!state.showRowCover) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("歌单行显示点赞角标", state.showLikedBadge, colors,
                    subtitle = "已点赞的歌曲在右下角显示爱心") { onLikedBadgeChange(!state.showLikedBadge) }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ==================== 播放 ====================
        SectionHeader("播放", colors)
        Spacer(Modifier.height(14.dp))

        SettingToggleRow(
            "淡入淡出",
            state.fadeEnabled,
            colors,
            subtitle = "开头音量渐起、结尾渐落，暂停也不再突兀"
        ) { onFadeChange(!state.fadeEnabled) }

        // 时长滑块只在开启时出现：关闭状态下它没有意义，摆着反而干扰
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
                    onValueChange = { onFadeMsChange(it.toLong()) },
                    valueRange = 500f..5000f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 无声略过（beta） ----
        SettingToggleRow(
            "无声略过 · beta",
            state.silenceSkip,
            colors,
            subtitle = "自动跳过歌曲开头和结尾没有声音的部分"
        ) { onSilenceSkipChange(!state.silenceSkip) }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            "上栏静止时隐藏",
            state.topBarAutoHide,
            colors,
            subtitle = "完全静止时上栏淡到全透明，碰一下即回"
        ) { onTopBarAutoHideChange(!state.topBarAutoHide) }

        Spacer(Modifier.height(18.dp))

        // ==================== 歌词 ====================
        SectionHeader("歌词", colors)
        Spacer(Modifier.height(14.dp))

        SettingToggleRow(
            "封面歌词",
            state.coverLyric,
            colors,
            subtitle = "封面左下角单行歌词，换句时模糊渐隐渐出"
        ) { onCoverLyricChange(!state.coverLyric) }
        Spacer(Modifier.height(14.dp))

        SettingToggleRow(
            "逐字流光",
            state.karaokeLyric,
            colors,
            subtitle = "全屏歌词当前行按进度扫光点亮（无词级时间时按句长估算）"
        ) { onKaraokeLyricChange(!state.karaokeLyric) }
        Spacer(Modifier.height(14.dp))
        Column {
            Text("歌词对齐", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeSwatch("靠左", state.lyricAlign == 0, colors,
                    modifier = Modifier.weight(1f)) { onLyricAlignChange(0) }
                ModeSwatch("居中", state.lyricAlign == 1, colors,
                    modifier = Modifier.weight(1f)) { onLyricAlignChange(1) }
                ModeSwatch("靠右", state.lyricAlign == 2, colors,
                    modifier = Modifier.weight(1f)) { onLyricAlignChange(2) }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---- 悬浮歌词 ----
        FloatingLyricSection(colors, headerColor)

        Spacer(Modifier.height(18.dp))

        // ==================== 动效与反馈 ====================
        SectionHeader("动效与反馈", colors)
        Spacer(Modifier.height(14.dp))

        // ---- 触感反馈 ----
        SettingToggleRow(
            "触感反馈",
            state.hapticsEnabled,
            colors,
            subtitle = "按键、切歌、拖动滚动条时轻微震动"
        ) { onHapticsChange(!state.hapticsEnabled) }

        Spacer(Modifier.height(10.dp))

        // ---- 物理动效 ----
        SettingToggleRow(
            "物理动效 · beta",
            state.tiltSpectrum || state.coverShake,
            colors,
            subtitle = "倾斜频谱 + 摇动封面，传感器驱动"
        ) { onPhysicsFxChange(!(state.tiltSpectrum || state.coverShake)) }

        Spacer(Modifier.height(10.dp))

        // ---- 果冻动效 ----
        SettingToggleRow(
            "果冻动效",
            state.jellyAnim,
            colors,
            subtitle = "全局弹性动画：按钮、开关、卡片回弹带果冻感"
        ) { onJellyAnimChange(!state.jellyAnim) }

        Spacer(Modifier.height(10.dp))

        // ---- 低音马达震动（beta） ----
        SettingToggleRow(
            "低音马达震动 · beta",
            state.bassHaptics,
            colors,
            subtitle = "跟随低频节奏轻微震动，幅度随鼓点强弱变化"
        ) { onBassHapticsChange(!state.bassHaptics) }

        // 强度档位只在开启时出现（同渐变时长的做法）
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
                        modifier = Modifier.weight(1f)) { onBassHapticsIntensityChange(0) }
                    ModeSwatch("标准", state.bassHapticsIntensity == 1, colors,
                        modifier = Modifier.weight(1f)) { onBassHapticsIntensityChange(1) }
                    ModeSwatch("重击", state.bassHapticsIntensity == 2, colors,
                        modifier = Modifier.weight(1f)) { onBassHapticsIntensityChange(2) }
                }

                Spacer(Modifier.height(10.dp))

                // 自适应：震动强度跟随鼓点力度、时长跟随鼓点时长，开启后隐藏手动时长档
                Spacer(Modifier.height(4.dp))
                SettingToggleRow(
                    "自适应旋律",
                    state.bassHapticsAdaptive,
                    colors,
                    subtitle = "强度随鼓点力度、时长随鼓点长短自动变化"
                ) { onBassHapticsAdaptiveChange(!state.bassHapticsAdaptive) }

                // 单次震动时长仅在「自适应」关闭时显示（自适应时由鼓点时长接管）
                AnimatedVisibility(
                    visible = !state.bassHapticsAdaptive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(Modifier.height(10.dp))

                        // 单次震动时长：三档（短/中/长 = 8/20/30ms），按钮替代滑条——
                        // 底部滑条手势竞争太强调不动，改成点选一档最稳。
                        Text("单次震动时长", color = colors.subText, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val dur = state.bassHapticsPulseMs
                            ModeSwatch("短", dur <= 6, colors,
                                modifier = Modifier.weight(1f)) {
                                onBassHapticsPulseMsChange(4); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                            ModeSwatch("中", dur > 6 && dur < 15, colors,
                                modifier = Modifier.weight(1f)) {
                                onBassHapticsPulseMsChange(10); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                            ModeSwatch("长", dur >= 15, colors,
                                modifier = Modifier.weight(1f)) {
                                onBassHapticsPulseMsChange(18); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 触发灵敏度：三档（低/中/高）。始终可单独调节，不受自适应影响。
                Text("触发灵敏度", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sens = state.bassHapticsSensitivity
                    ModeSwatch("低", sens <= 3, colors,
                        modifier = Modifier.weight(1f)) { onBassHapticsSensitivityChange(3) }
                    ModeSwatch("中", sens > 3 && sens < 7, colors,
                        modifier = Modifier.weight(1f)) { onBassHapticsSensitivityChange(5) }
                    ModeSwatch("高", sens >= 7, colors,
                        modifier = Modifier.weight(1f)) { onBassHapticsSensitivityChange(8) }
                }

                Spacer(Modifier.height(12.dp))

                // 低音截止频率：多少 Hz 以下算作"低音"（走底鼓闷震），120–320Hz 可调。
                // 调高 → 更多中低频鼓（如厚底鼓/低 tom）归入低音走沉震；调低 → 只有最低的底鼓算低音。
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
                        // 120–320Hz，步进 10Hz
                        val hz = (120 + (it * 200f)).toInt().let { v -> (v / 10) * 10 }.coerceIn(120, 320)
                        onBassHapticsLowCutoffChange(hz)
                    },
                    valueRange = 0f..1f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ==================== 推荐 ====================
        SectionHeader("推荐", colors)
        Spacer(Modifier.height(14.dp))

        // ---- 推荐探索度 ----
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
            onValueChange = { onExplorationChange(it) },
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
        ) { onShowRecsChange(!state.showRecommendations) }

        Spacer(Modifier.height(18.dp))

        // ==================== 数据 ====================
        SectionHeader("数据", colors)
        Spacer(Modifier.height(14.dp))

        // ---- 文件夹 ----
        Text("音乐文件夹", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.heightIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                FolderRow("全部音乐", "${state.folders.sumOf { it.songCount }} 首",
                    state.selectedFolders.isEmpty() && !state.onlyLiked, colors) { onSelectFolder(null); onLikedFilterChange(false) }
            }
            item {
                FolderRow("我的收藏", "${state.likedSongIds.size} 首",
                    state.onlyLiked, colors) { onLikedFilterChange(true) }
            }
            itemsIndexed(state.folders, key = { _, f -> "folder-${f.path}" }) { _, f ->
                FolderRow(f.name, "${f.songCount} 首",
                    f.path in state.selectedFolders, colors) { onSelectFolder(f.path) }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ==================== 关于 ====================
        SectionHeader("关于", colors)
        Spacer(Modifier.height(8.dp))
        Text(
            "IRIS Music v${BuildConfig.VERSION_NAME}\n纯本地音乐播放器 · 不联网不上传\nKotlin + Jetpack Compose 构建",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(14.dp))

        // ---- 检查更新（opt-in）----
        UpdateCheckSection(colors)

        Spacer(Modifier.height(18.dp))

        // ==================== 我的数据 ====================
        SectionHeader("我的数据", colors)
        Spacer(Modifier.height(8.dp))
        DataTransferSection(colors, state.allSongs)

        Spacer(Modifier.height(24.dp))
        } // ===== 结束可滚动内容 =====

        // ===== 底部固定：完成按钮 =====
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

/** 一级分组标题：醒目、中性（用主文字色），与彩色子项标题区分开层级。 */
@Composable
private fun SectionHeader(text: String, colors: IrisColors) {
    Text(
        text,
        color = colors.text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Black
    )
}

/**
 * 我的数据：导出 / 导入听歌数据（JSON，全程离线）。
 *
 * 存在意义是让推荐算法依赖的长期行为数据可以跟着人走——换机、刷机、重装不再清零。
 * 走 SAF（ACTION_CREATE_DOCUMENT / OpenDocument），因此不需要任何存储写权限。
 */
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
                        // 部分机型对 application/json 过滤过严，放宽到任意类型再由解析兜底
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

/**
 * 检查更新（opt-in）：开关默认关；关闭时完全不碰网络。
 * 开启后显示「检查更新」按钮，手动点按才发起一次 GitHub API GET，
 * 结果三态：已是最新 / 发现新版本（点击跳转 release 页）/ 检查失败。
 */
@Composable
private fun UpdateCheckSection(colors: IrisColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = ctx.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean(UpdateChecker.KEY_ENABLED, false)) }
    var checking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf<String?>(null) }

    Column {
        SettingToggleRow(
            "检查更新",
            enabled,
            colors,
            subtitle = "开启后可手动检查 GitHub 上的新版本，仅此访问网络"
        ) {
            enabled = !enabled
            prefs.edit().putBoolean(UpdateChecker.KEY_ENABLED, enabled).apply()
            if (!enabled) {
                // 关闭时清掉结果残留
                statusText = ""
                newTag = null
            }
        }

        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 检查按钮：主色，与设置页其它按钮同构
                    Box(
                        Modifier
                            .weight(1f).height(40.dp)
                            .clip(IrisShape.item)
                            .background(if (checking) colors.primary.copy(alpha = 0.18f) else colors.primary)
                            .clickable(enabled = !checking) {
                                Haptics.tap()
                                checking = true
                                statusText = "正在检查…"
                                newTag = null
                                scope.launch {
                                    when (val r = UpdateChecker.check()) {
                                        is UpdateChecker.Result.NewVersion -> {
                                            newTag = r.tag
                                            statusText = "发现新版本 ${r.tag}"
                                        }
                                        UpdateChecker.Result.UpToDate -> statusText = "已是最新版本"
                                        UpdateChecker.Result.Failed -> statusText = "检查失败：网络不可用或稍后再试"
                                    }
                                    checking = false
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (checking) "检查中…" else "检查更新",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (checking) colors.primary else colors.primary.readableTextOn()
                        )
                    }
                }

                if (statusText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        statusText,
                        color = colors.subText, fontSize = 12.sp,
                        fontWeight = if (newTag != null) FontWeight.Bold else FontWeight.Normal
                    )
                    // 发现新版本时给个跳转 release 页的入口
                    if (newTag != null) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth().height(40.dp)
                                .clip(IrisShape.item)
                                .background(colors.primary.copy(alpha = 0.18f))
                                .clickable {
                                    Haptics.tap()
                                    ctx.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/WWRJ3321/IRISMusic/releases/latest")
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("前往下载", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 悬浮歌词设置区：开关 + 字号/长度/透明度三个滑条，全部读写 iris_prefs，
 * 由服务侧 FloatingLyric 的 OnSharedPreferenceChangeListener 实时生效。
 * 无悬浮窗权限时给出授权入口，从系统授权页返回后自动重新同步显隐。
 */
@Composable
private fun FloatingLyricSection(colors: IrisColors, headerColor: Color) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_ENABLED, false)) }
    var granted by remember { mutableStateOf(FloatingLyric.hasPermission(ctx)) }
    var font by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_FONT, FloatingLyric.FONT_DEFAULT)) }
    var widthFrac by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_WIDTH, FloatingLyric.WIDTH_DEFAULT)) }
    var bg by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_BG, FloatingLyric.BG_DEFAULT)) }

    // 从系统授权页返回时刷新权限并让服务重新同步显隐
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
        // 首次开启且无权限：跳系统授权页（授权返回后 ON_RESUME 自动显示）
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

/**
 * 设置项开关行。原来每个开关都是一整段 Row + Box 手写胶囊，
 * 加一个开关就复制 20 行；抽出来后新增开关只需一行调用。
 */
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
        // 拨钮位置走动画，不是瞬移（果冻开关下为弹性滑动）
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

/**
 * 界面排布选择卡：上方一枚手机缩略预览，下方标签。
 *
 * 只用色块表达结构——纵向是两列格子，横向是三张并排的竖卡，堆叠是错开的一沓。
 * 和参考图同构，但完全 Canvas 自绘（项目不引图标库，也不该为三张示意图加位图资源）。
 */
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

            // 手机外框
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
// 纵向歌单：标题栏 + 一条条歌曲行
                     val gap = 2.5.dp.toPx()
                     val rowH = (h - gap * 4f) / 5f
                     drawRoundRect(fill, Offset(pad, pad), Size(w, rowH * 0.72f), inner)
                     for (row in 0 until 4) {
                         drawRoundRect(fill.copy(alpha = fill.alpha * 0.82f),
                             Offset(pad, pad + rowH * (row + 1.05f)), Size(w, rowH * 0.72f), inner)
                     }
                }

                IrisLayout.CAROUSEL -> {
                    // 三张并排竖卡，中间那张满高、两侧被裁掉一半
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
                    // 一沓错开的卡：从最里层往外画，最后那张最实
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
                     // 唱片墙：手机框内铺满一格格方形封面卡（3 列 × 4 行网格）
                     val cols = 3
                     val rows = 4
                     val cellGap = 1.6.dp.toPx()
                     val cellW = (w - cellGap * (cols - 1)) / cols
                     val cellH = (h - cellGap * (rows - 1)) / rows
                     for (row in 0 until rows) {
                         for (col in 0 until cols) {
                             // 交替深浅让"一格格封面"更清楚，不是一块实心
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
    // 色板必须用「当前明暗模式下实际生效」的色对：黑白主题的枚举值是白+黑，
    // 浅色模式下白色圆点和白色标题会直接消失在白卡上。
    val (swatchPrimary, swatchSecondary) = theme.resolvedPair(colors.isDark)
    val cardBg = if (selected) swatchPrimary.copy(alpha = 0.15f) else colors.card
    // 选中态标题落在半透明主题底上，仍以卡片色为背景基准反算可读色
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
            // 圆点描边：纯白/纯黑圆点在同色卡片上没有边界，加一圈细描边兜底
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
    // HSV 草稿：改成「持有 FloatArray 的 mutableStateOf」，每次改动写入一个**新数组**。
    // 原来用无 key 的 remember { FloatArray } 原地改 + 一个从未被读取的 bump，
    // 拖动时本节根本不重组，预览/滑块只能靠上层回传的间接重组刷新，
    // 表现为「拖动不跟手、要重进设置才看到颜色变」。
    // 用新数组引用触发重组：滑块 value 读当前数组，引用一变就重绘，且不重建滑块子树，
    // 拖动手势状态得以保留（比 key(bump) 更安全）。
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
