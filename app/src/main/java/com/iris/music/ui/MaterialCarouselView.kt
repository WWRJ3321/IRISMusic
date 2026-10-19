package com.iris.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.iris.music.data.Song
import com.iris.music.ui.theme.IrisColors
import kotlin.math.roundToInt

@Composable
fun MaterialCarouselView(
    recommendations: List<Song>,
    colors: IrisColors,
    onSelect: (Song) -> Unit
) {
    val density = LocalDensity.current
    val lazyListState = rememberLazyListState()

    val baseWidth = 180.dp
    val spacing = 10.dp
    // 控制后方卡片露出的宽度，调小这个值，后方露出的卡片越多
    val spaceWidthPx = with(density) { 85.dp.toPx() }
    val divisor = 190f

    LazyRow(
        state = lazyListState,
        // 原生吸附，完美解决“一滑飞七八个”，滑一次只切一张，且松手有平滑物理阻尼
        flingBehavior = rememberSnapFlingBehavior(lazyListState),
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentPadding = PaddingValues(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        items(recommendations.size) { index ->
            val song = recommendations[index]
            
            // 从 LazyRow 的底层状态中直接获取该卡片的物理位置（绝对精准）
            val itemInfo = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == index }
            val offset = itemInfo?.offset ?: return@items

            // 核心计算：根据偏移量计算压缩宽度和位移
            val minX = offset.toFloat()
            val reducingWidth = (minX / divisor) * spaceWidthPx
            val cappedWidth = minOf(reducingWidth, spaceWidthPx)
            
            // 当前卡片的实际宽度
            val currentWidth = with(density) { 
                (baseWidth.toPx() - (if (minX > 0) cappedWidth else -cappedWidth)).toDp() 
            }
            
            // 自身视差偏移
            val baseOffsetX = if (minX > 0) 0f else -cappedWidth
            
            // 计算前一张卡片的挤压影响，让后面的卡片补位
            var previousOffset = 0f
            if (index > 0) {
                val prevInfo = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == index - 1 }
                if (prevInfo != null) {
                    val prevMinX = prevInfo.offset.toFloat()
                    val prevReducingWidth = (prevMinX / divisor) * spaceWidthPx
                    previousOffset = if (prevMinX < 0f) 0f else prevReducingWidth
                }
            }

            // 最终绘制偏移量
            val finalOffsetX = baseOffsetX - previousOffset

            // 外层固定宽度占位，保证 LazyRow 滚动长度恒定，绝不白屏
            Box(
                modifier = Modifier
                    .width(baseWidth)
                    .height(200.dp)
                    .zIndex((recommendations.size - index).toFloat()),
                contentAlignment = Alignment.TopStart
            ) {
                // 内层动态改变宽度和位置
                Box(
                    modifier = Modifier
                        .width(currentWidth)
                        .height(200.dp)
                        .graphicsLayer {
                            // 强制取整像素，解决图片边缘闪烁的 Bug
                            translationX = finalOffsetX.roundToInt().toFloat()
                        }
                        .clip(RoundedCornerShape(25.dp))
                        .background(colors.surface)
                        .clickable { onSelect(song) }
                ) {
                    CarouselCardInnerContent(song, colors)
                }
            }
        }

        // 尾部留白，让最后一张卡片也能滑到最前方
        item {
            Spacer(Modifier.width(50.dp))
        }
    }
}

@Composable
private fun CarouselCardInnerContent(song: Song, colors: IrisColors) {
    Column(Modifier.fillMaxSize()) {
        // 图片
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SongArtwork(
                filePath = song.filePath,
                seedId = song.id,
                artworkSize = 200.dp,
                isDark = colors.isDark,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // 文字
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
