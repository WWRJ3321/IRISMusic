package com.iris.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.music.data.Song
import com.iris.music.ui.theme.IrisColors
import kotlin.math.roundToInt

@Composable
fun MaterialCarouselView(
    recommendations: List<Song>,
    colors: IrisColors,
    onSelect: (Song) -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // 严格按照 SwiftUI 源码中的数值进行 1:1 换算
    val baseWidthPx = with(density) { 180.dp.toPx() }
    val spacingPx = with(density) { 10.dp.toPx() }
    val spaceWidthPx = with(density) { 130.dp.toPx() }
    val divisor = 190f // SwiftUI 原代码的除数

    Layout(
        content = {
            recommendations.forEach { song ->
                CarouselCardContent(song, colors) { onSelect(song) }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 15.dp) // 对应 SwiftUI 的 .padding(.horizontal, 15)
            .horizontalScroll(scrollState)
    ) { measurables, constraints ->
        val count = measurables.size
        val placeables = arrayOfNulls<Placeable>(count)
        val assignedXs = FloatArray(count)
        val finalOffsets = FloatArray(count)

        // 1. 测量并计算每张卡片的真实宽度与物理起始位置
        var currentX = -scrollState.value.toFloat()

        for (i in 0 until count) {
            val minX = currentX
            
            // SwiftUI 核心逻辑：
            val reducingWidth = (minX / divisor) * spaceWidthPx
            val cappedWidth = minOf(reducingWidth, spaceWidthPx)
            
            val frameWidth = baseWidthPx - (if (minX > 0) cappedWidth else -cappedWidth)
            val safeWidth = maxOf(frameWidth, 0f).roundToInt() // 防止宽度变成负数
            
            assignedXs[i] = currentX
            
            val placeable = measurables[i].measure(
                Constraints(
                    minWidth = safeWidth,
                    maxWidth = safeWidth,
                    minHeight = constraints.maxHeight,
                    maxHeight = constraints.maxHeight
                )
            )
            placeables[i] = placeable
            
            currentX += safeWidth + spacingPx
        }

        // 2. 计算前驱影响并放置
        var previousOffset = 0f

        for (i in 0 until count) {
            val minX = assignedXs[i]
            val baseOffset = if (minX > 0) 0f else -minOf((minX / divisor) * spaceWidthPx, spaceWidthPx)
            
            // 最终绘制位置 = 物理起始位置 + 自身视差偏移 - 被前一张卡片的挤压偏移
            finalOffsets[i] = assignedXs[i] + baseOffset - previousOffset
            
            // 为下一张卡片计算 previousOffset
            val reducingWidth = (minX / divisor) * spaceWidthPx
            previousOffset = if (minX < 0f) 0f else reducingWidth
        }

        // 计算总宽度以支持完整滚动
        val totalWidth = maxOf(currentX.toInt(), constraints.maxWidth)

        layout(totalWidth, constraints.maxHeight) {
            // 倒序绘制，让前面的卡片盖住后面的卡片（对应 SwiftUI 默认层级）
            for (i in count - 1 downTo 0) {
                placeables[i]?.placeRelative(finalOffsets[i].roundToInt(), 0)
            }
        }
    }
}

@Composable
private fun CarouselCardContent(song: Song, colors: IrisColors, onClick: () -> Unit) {
    // 完全根据你的截图样式设计的卡片内部
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(25.dp)) // SwiftUI 原代码: cornerRadius: 25
            .background(colors.surface) // 卡片底色
            .clickable { onClick() }
    ) {
        Column(Modifier.fillMaxSize()) {
            // 图片部分
            Box(Modifier.fillMaxWidth().weight(1f)) {
                SongArtwork(
                    filePath = song.filePath,
                    seedId = song.id,
                    artworkSize = 180.dp, // 占满宽度
                    isDark = colors.isDark,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // 文字部分
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = song.title,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    color = colors.subText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
