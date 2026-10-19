package com.iris.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.iris.music.data.Song
import com.iris.music.ui.theme.IrisColors
import androidx.compose.ui.draw.clipToBounds

@Composable
fun MaterialCarouselView(
    recommendations: List<Song>,
    colors: IrisColors,
    onSelect: (Song) -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // SwiftUI 的基础数值换算
    val baseWidthPx = with(density) { 180.dp.toPx() }
    val spacingPx = with(density) { 10.dp.toPx() }
    val spaceWidthPx = with(density) { 130.dp.toPx() }
    val divisor = 190f

    // 使用 Row 替代自定义 Layout，固定每个卡片的占位，避免引起滚动死循环
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .horizontalScroll(scrollState)
            .padding(horizontal = 15.dp), // 左右边距
        horizontalArrangement = Arrangement.spacedBy(10.dp) // 卡片间距 10dp
    ) {
        recommendations.forEachIndexed { index, song ->
            // 计算当前卡片的 minX（相对于屏幕左侧的位置）
            val minX = index * (baseWidthPx + spacingPx) - scrollState.value

            // 1. 计算压缩/拉伸后的宽度
            val reducingWidth = (minX / divisor) * spaceWidthPx
            val cappedWidth = minOf(reducingWidth, spaceWidthPx)
            val frameWidth = baseWidthPx - (if (minX > 0) cappedWidth else -cappedWidth)
            val finalWidth = maxOf(frameWidth, 1f) // 防止宽度为0崩溃

            // 2. 计算自身偏移量
            val baseOffsetX = if (minX > 0) 0f else -cappedWidth

            // 3. 计算前一张卡片带来的挤压影响（让后面的卡片向左补位）
            var previousOffset = 0f
            if (index > 0) {
                val prevMinX = (index - 1) * (baseWidthPx + spacingPx) - scrollState.value
                val prevReducingWidth = (prevMinX / divisor) * spaceWidthPx
                previousOffset = if (prevMinX < 0) 0f else prevReducingWidth
            }

            // 最终绘制的 X 偏移量
            val finalOffsetX = baseOffsetX - previousOffset

            // 外层固定占位 180x200，防止滚动条抖动，并设置 zIndex 保证左侧盖住右侧
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(200.dp)
                    .zIndex((recommendations.size - index).toFloat()),
                contentAlignment = Alignment.TopStart // 必须靠左对齐，宽度缩小时才能留出右边的间距
            ) {
                // 内层动态改变宽度、偏移和裁剪
                Box(
                    modifier = Modifier
                        .width(with(density) { finalWidth.toDp() })
                        .height(200.dp)
                        .offset(x = with(density) { finalOffsetX.toDp() })
                        .clipToBounds() // 确保内容不会溢出这个被压缩的盒子
                        .clip(RoundedCornerShape(25.dp))
                        .background(colors.surface)
                        .clickable { onSelect(song) }
                ) {
                    CarouselCardInnerContent(song, colors)
                }
            }
        }

        // 尾部留白，让最后一张卡片也能滑到最左侧
        Spacer(Modifier.width(50.dp))
    }
}

@Composable
private fun CarouselCardInnerContent(song: Song, colors: IrisColors) {
    Column(Modifier.fillMaxSize()) {
        // 图片部分
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SongArtwork(
                filePath = song.filePath,
                seedId = song.id,
                artworkSize = 200.dp, // 传大一点让裁切填满
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
