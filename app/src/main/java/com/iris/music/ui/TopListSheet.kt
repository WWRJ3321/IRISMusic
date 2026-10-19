package com.iris.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.iris.music.player.TopListInfo
import com.iris.music.ui.theme.*
import kotlinx.coroutines.delay

/**
 * 榜单弹层：IrisSheet 外壳 + 吸顶折叠标题 + 榜单列表。
 *
 * 完全 1:1 复刻 NewSettingsScreen.kt 的吸顶折叠样式：
 * - 顶部半透明模糊遮罩（渐变从背景色到透明，让标题在任何滚动位置都清晰）
 * - 标题"榜单"未滚动时居中、大字；向下滑时向左下缩小平移
 * - 内容区是 LazyColumn，滚动位置驱动标题折叠状态
 *
 * 不用原生 ModalBottomSheet：它自带的 Surface 是实色容器，
 * 拿不到折射背板，液态玻璃下会露出一块实心方块。IrisSheet 是项目自绘的
 * 玻璃弹层外壳，与设置/均衡器同款观感。
 */
@Composable
internal fun TopListSheet(
    topLists: List<TopListInfo>,
    defaultCoverUri: android.net.Uri?,
    colors: IrisColors,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    val drag = rememberSheetDragState()
    val listState = rememberLazyListState()

    // 折叠判定：首项不再是第 0 个，或者第 0 项已经滚出 50px 以上
    val isScrolled = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 50

    IrisSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.85f)
    ) {
        val panelBg = if (colors.isDark) Color(0xFF000000) else Color(0xFFF2F2F7)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(panelBg)
        ) {
            // 面板内容：拖把手 + 顶部占位 + 列表
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                SheetDragHandle(drag, onDismiss, colors.card)
                Spacer(Modifier.height(10.dp))
                // 顶部留白给折叠标题（与 NewSettingsPanel 布局一致）
                Box(Modifier.fillMaxWidth().height(64.dp))
                Spacer(Modifier.height(4.dp))

                if (topLists.isEmpty()) {
                    // 空态
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "榜单加载中…",
                            color = colors.subText,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 30.dp)
                    ) {
                        items(topLists, key = { "toplist-${it.id}" }) { topList ->
                            TopListRow(
                                topList = topList,
                                colors = colors,
                                defaultCoverUri = defaultCoverUri,
                                onClick = { onSelect(topList.id) }
                            )
                        }
                    }
                }
            }

            // 顶部渐变遮罩：标题层之下，列表层之上，保证任何滚动位置标题都清晰
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                panelBg,
                                panelBg.copy(alpha = 0.95f),
                                panelBg.copy(alpha = 0.7f),
                                Color.Transparent
                            )
                        )
                    )
                    .zIndex(1f)
            )

            // 折叠标题：由 listState 的滚动位置驱动
            CollapsingTitle(
                title = "榜单",
                isScrolled = isScrolled,
                colors = colors,
                modifier = Modifier.zIndex(2f)
            )
        }
    }
}

/**
 * 榜单行：封面 + 名称 + 更新频率。
 * 封面走 3 秒超时兜底，与推荐卡/歌曲行一致。
 */
@Composable
private fun TopListRow(
    topList: TopListInfo,
    colors: IrisColors,
    defaultCoverUri: android.net.Uri?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { Haptics.tap(); onClick() }
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetworkTopListCover(
            uri = topList.coverImgUrl,
            fallbackUri = defaultCoverUri,
            seedId = topList.id,
            isDark = colors.isDark,
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                topList.name,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                topList.updateFrequency,
                color = colors.subText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 榜单封面：3 秒超时 + 兜底 + 占位，与 NetworkRecommendCover 同策略。
 * 三阶段：网络图 → 兜底图 → 纯占位（音符）。
 */
@Composable
private fun NetworkTopListCover(
    uri: String,
    fallbackUri: android.net.Uri?,
    seedId: Long,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    var phase by remember(uri) { mutableStateOf(0) }

    LaunchedEffect(uri) {
        delay(3000)
        if (phase == 0) {
            phase = if (fallbackUri != null) 1 else 2
        }
    }

    val hue = (seedId % 360).toFloat()
    val placeholderColor = Color.hsv(hue, 0.35f, if (isDark) 0.18f else 0.92f)

    Box(
        modifier.background(placeholderColor),
        contentAlignment = Alignment.Center
    ) {
        when (phase) {
            0 -> AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { phase = if (fallbackUri != null) 1 else 2 },
                modifier = Modifier.fillMaxSize()
            )
            1 -> AsyncImage(
                model = fallbackUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { phase = 2 },
                modifier = Modifier.fillMaxSize()
            )
            else -> {
                Text(
                    "♪",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
