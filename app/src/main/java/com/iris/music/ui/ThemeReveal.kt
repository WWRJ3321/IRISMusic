package com.iris.music.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 全局的主题波纹切换状态管理器
 */
object ThemeRevealState {
    var isAnimating by mutableStateOf(false)
    var bitmap by mutableStateOf<Bitmap?>(null)
    var center by mutableStateOf(Offset.Zero)
}

/**
 * 波纹切换宿主：包裹整个界面，当状态改变时截屏并播放波纹动画
 */
@Composable
fun ThemeRevealHost(content: @Composable () -> Unit) {
    val animProgress = remember { Animatable(0f) }

    // 拦截状态：当触发切换时，进行截屏和动画
    LaunchedEffect(ThemeRevealState.isAnimating) {
        if (ThemeRevealState.isAnimating) {
            animProgress.snapTo(0f)
            // 播放波纹扩散动画 (0.6秒)
            animProgress.animateTo(1f, animationSpec = tween(600))
            // 动画结束后清理状态
            ThemeRevealState.isAnimating = false
            ThemeRevealState.bitmap = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 底层：真实的 UI（新主题）
        content()

        // 顶层：旧的截屏（带波纹裁剪）
        val bmp = ThemeRevealState.bitmap
        if (ThemeRevealState.isAnimating && bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // 必须开启离屏渲染，才能使用 DstOut 混合模式
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // 先把旧图整个画出来
                        drawContent()
                        
                        val progress = animProgress.value
                        if (progress > 0f) {
                            // 计算屏幕对角线长度作为最大半径，保证能完全扩散出去
                            val maxRadius = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                            val radius = maxRadius * progress
                            
                            // 用 DstOut 模式画圆：相当于在旧图上挖个圆孔，露出底下的新界面
                            drawCircle(
                                color = Color.Transparent, // 颜色值不重要，DstOut 只看透明度/混合结果
                                radius = radius,
                                center = ThemeRevealState.center,
                                blendMode = BlendMode.DstOut
                            )
                        }
                    }
            )
        }
    }
}

/**
 * 触发波纹切换的辅助函数
 * @param view 根视图（用于截屏）
 * @param clickPosition 点击的坐标（用于圆形扩散的圆心）
 * @param onSwitch 切换主题的具体逻辑
 */
fun triggerThemeReveal(
    view: android.view.View,
    clickPosition: Offset,
    onSwitch: () -> Unit
) {
    if (ThemeRevealState.isAnimating) return // 防抖
    
    // 1. 截取当前屏幕
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    view.draw(canvas)
    
    // 2. 设置状态并执行切换
    ThemeRevealState.bitmap = bitmap
    ThemeRevealState.center = clickPosition
    ThemeRevealState.isAnimating = true
    
    // 3. 在下一帧执行新主题应用，这样截图才是旧主题
    view.post {
        onSwitch()
    }
}
