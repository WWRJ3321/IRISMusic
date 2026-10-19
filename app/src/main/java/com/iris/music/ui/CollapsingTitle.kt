package com.iris.music.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.iris.music.ui.theme.IrisColors

/**
 * 吸顶折叠标题：未滚动时居中大字，滚动后向左下缩小平移。
 *
 * 从 NewSettingsScreen.kt 的"设置"标题抽取，逻辑与动画参数完全一致：
 * - isScrolled = false：水平居中（靠 maxWidth 反推），字号大
 * - isScrolled = true：靠左（默认 20dp），垂直位置略上移，字号缩小
 * - 两态之间用 spring 动画过渡（dampingRatio 0.8 / stiffness 400）
 *
 * 必须放在带明确宽度的容器里（BoxWithConstraints 需要拿到 maxWidth 才能居中）。
 * 调用方负责铺顶部渐变遮罩、并在视觉层级上把它 zIndex 提到遮罩之上。
 */
@Composable
internal fun CollapsingTitle(
    title: String,
    isScrolled: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    /** 未滚动时的垂直位置 */
    expandedY: Dp = 54.dp,
    /** 滚动后的垂直位置 */
    collapsedY: Dp = 46.dp,
    /** 未滚动时的字号 */
    expandedSize: Float = 24f,
    /** 滚动后的字号 */
    collapsedSize: Float = 18f,
    /** 滚动后的水平位置 */
    collapsedX: Dp = 20.dp,
    /** 居中时的宽度补偿：标题文本宽度的估算值的一半，用于让文本视觉居中 */
    centerOffset: Dp = 32.dp
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 居中位置：容器宽度一半，减去文本宽度估算的一半
        val targetX = if (isScrolled) collapsedX else (maxWidth / 2f - centerOffset)
        val targetY = if (isScrolled) collapsedY else expandedY
        val targetSize = if (isScrolled) collapsedSize else expandedSize

        val animatedX by animateDpAsState(
            targetValue = targetX,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "collapsingTitleX"
        )
        val animatedY by animateDpAsState(
            targetValue = targetY,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "collapsingTitleY"
        )
        val animatedSize by animateFloatAsState(
            targetValue = targetSize,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "collapsingTitleSize"
        )

        Text(
            title,
            color = colors.text,
            fontSize = animatedSize.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = animatedX, y = animatedY)
                .zIndex(2f)
        )
    }
}
