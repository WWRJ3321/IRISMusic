package com.iris.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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

    // 严格 1:1 的尺寸换算
    val baseWidthPx = with(density) { 180.dp.toPx() }
    val spacingPx = with(density) { 10.dp.toPx() }
    // 调整这个值可以控制后方卡片保留的宽度，原版是 130f，这里调成 85f 左右让后面多露出两张
    val spaceWidthPx = with(density) { 85.dp.toPx() }
    val divisor = 190f

    // 计算全局的滚动偏移量
    val totalOffset = (lazyListState.firstVisibleItemIndex * (baseWidthPx + spacingPx) - lazyListState.firstVisibleItemScrollOffset).toFloat()

    LazyRow(
        state = lazyListState,
        // 使用自带的吸附行为，完美实现“滑动一次只切一张”且有物理阻尼感
        flingBehavior = rememberSnapFlingBehavior(lazyListState),
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(recommendations.size) { index ->
            val song = recommendations[index]
            
            // 计算当前卡片相对于屏幕最左侧的物理位置
            val minX = index * (baseWidthPx + spacingPx) - totalOffset

            // SwiftUI 挤压算法
            val reducingWidth = (minX / divisor) * spaceWidthPx
            val cappedWidth = minOf(reducingWidth, spaceWidthPx)
            
            val frameWidth = baseWidthPx - (if (minX > 0) cappedWidth else -cappedWidth)
            val safeWidth = maxOf(frameWidth, 1f)
            
            val baseOffsetX = if (minX > 0) 0f else -cappedWidth
            
            // 计算前驱影响，填补空隙
            var previousOffset = 0f
            if (index > 0) {
                val prevMinX = (index - 1) * (baseWidthPx + spacingPx) - totalOffset
                val prevReducingWidth = (prevMinX / divisor) * spaceWidthPx
                previousOffset = if (prevMinX < 0f) 0f else prevReducingWidth
            }

            // 最终绘制位置
            val finalOffsetX = baseOffsetX - previousOffset

            // 外层使用固定宽度占位，防止 LazyRow 在计算时发生抖动，并通过 zIndex 保证层级
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(200.dp)
                    .zIndex((recommendations.size - index).toFloat()),
                contentAlignment = Alignment.CenterStart
            ) {
                // 内层执行真实的宽度缩放与偏移，同时裁剪圆角
                Box(
                    modifier = Modifier
                        .width(with(density) { safeWidth.toDp() })
                        .height(200.dp)
                        // 【关键修复】：强制四舍五入取整像素，彻底解决边缘亚像素渲染产生的“闪线”Bug
                        .graphicsLayer {
                            translationX = finalOffsetX.roundToInt().toFloat()
                        }
                        .clipToBounds()
                        .clip(RoundedCornerShape(25.dp))
                        .background(colors.surface)
                        .clickable { onSelect(song) }
                ) {
                    CarouselCardInnerContent(song, colors)
                }
            }
        }
        
        // 尾部留白，防止最后一张卡片被边缘卡住
        item {
            Spacer(Modifier.width(50.dp))
        }
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
                artworkSize = 180.dp,
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
