package com.iris.music.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

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
    val view = LocalView.current
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
                    .drawWithContent {
                        val progress = animProgress.value
                        if (progress <= 0f) return@drawWithContent
                        
                        // 计算最大半径（屏幕对角线）
                        val maxRadius = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                        val radius = maxRadius * progress
                        
                        // 根据点击坐标创建圆形路径
                        val path = Path().apply {
                            addOval(
                                androidx.compose.ui.geometry.Rect(
                                    center = ThemeRevealState.center,
                                    radius = radius
                                )
                            )
                        }
                        // 用圆形挖孔（DestinationOut），把旧截图一点点擦除
                        clipPath(path) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    // 注意：这里用的是 clipPath，实际上是反向裁剪。为了简单，我们用另一种方式：
                    // 由于 Compose 没有直接的 "保留外部" 裁剪，我们用 Path 描边遮罩。
                    .drawWithContent {
                        val progress = animProgress.value
                        val maxRadius = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                        val radius = maxRadius * progress
                        
                        val path = Path().apply {
                            // 全屏矩形
                            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                            // 减去一个圆
                            val circle = Path().apply {
                                addOval(androidx.compose.ui.geometry.Rect(ThemeRevealState.center, radius))
                            }
                            // 这里用 Path.op 减去圆形
                            op(this, circle, androidx.compose.ui.graphics.PathOperation.Difference)
                        }
                        
                        clipPath(path) {
                            this@drawWithContent.drawContent()
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
