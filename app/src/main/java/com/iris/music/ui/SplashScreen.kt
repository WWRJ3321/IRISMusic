package com.iris.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 启动动画：手写体 "Hello" 描边书写。
 *
 * 从 SwiftUI 工程 HelloAnimation/ContentView.swift 的 HelloText Shape 1:1 移植：
 * - 21 段三次贝塞尔曲线坐标原样搬运，仅做 SwiftUI → Compose API 映射
 * - PathMeasure.getSegment 等价于 SwiftUI .trim(from: 0, to: progress)
 * - 4 秒 easeInOut 描边、10dp 圆头圆角线帽、垂直红→蓝→绿渐变，与原版完全一致
 * - 书写完成停 400ms，再 500ms 淡出，淡出结束回调 onFinished
 *
 * 覆盖在 MainActivity 最外层 Box 之上，纯黑背景直接盖住主界面（含所有液态玻璃）。
 * 只在 Activity.onCreate 创建一次，动画结束后由宿主移除。不引用任何现有 UI 组件，
 * 也不触碰 Haze / RenderEffect / 玻璃背板。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // 描边进度 0→1，4 秒 easeInOut
    val drawProgress = remember { Animatable(0f) }
    // 整体淡出透明度
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        drawProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 4000,
                // 标准 CSS ease-in-out 的三次贝塞尔
                easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
            )
        )
        delay(400)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 500)
        )
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { this.alpha = alpha.value }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 宽度占屏宽 88%，高度按原版 SwiftUI `frame(height: 100) + 撑满宽度`
            // 得到的约 3.45 宽高比等比缩放，视觉上与原版一致（横向略微拉长的手写体）
            val pathW = size.width * 0.88f
            val pathH = pathW / 3.45f
            val offsetX = (size.width - pathW) / 2f
            val offsetY = (size.height - pathH) / 2f

            val fullPath = buildHelloPath(pathW, pathH, offsetX, offsetY)
            val measure = PathMeasure().apply { setPath(fullPath, false) }
            val totalLen = measure.length
            if (totalLen <= 0f) return@Canvas

            val trimmedLen = totalLen * drawProgress.value
            if (trimmedLen <= 0f) return@Canvas

            val segPath = Path()
            measure.getSegment(0f, trimmedLen, segPath, true)

            drawPath(
                path = segPath,
                brush = Brush.verticalGradient(
                    0f to Color.Red,
                    0.5f to Color.Blue,
                    1f to Color.Green,
                    startY = offsetY,
                    endY = offsetY + pathH
                ),
                style = Stroke(
                    width = 10.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/**
 * 从 ContentView.swift 的 HelloText Shape 逐段翻译。
 *
 * SwiftUI: path.addCurve(to:control1:control2:)
 * Compose: path.cubicTo(control1.x, control1.y, control2.x, control2.y, to.x, to.y)
 *
 * 所有坐标归一化后原样搬运，一字未改。
 */
private fun buildHelloPath(w: Float, h: Float, ox: Float, oy: Float): Path {
    fun x(v: Float) = ox + v * w
    fun y(v: Float) = oy + v * h
    return Path().apply {
        moveTo(x(0.00095f), y(0.88718f))
        cubicTo(x(0.00993f), y(0.87738f), x(0.16556f), y(0.56785f), x(0.19536f), y(0.31015f))
        cubicTo(x(0.22517f), y(0.05245f), x(0.18590f), y(-0.0680f), x(0.15043f), y(0.04964f))
        cubicTo(x(0.11495f), y(0.16729f), x(0.09792f), y(1.02023f), x(0.10028f), y(0.93200f))
        cubicTo(x(0.10265f), y(0.84376f), x(0.12157f), y(0.47822f), x(0.18354f), y(0.47822f))
        cubicTo(x(0.25733f), y(0.51463f), x(0.19915f), y(0.81575f), x(0.22327f), y(0.88718f))
        cubicTo(x(0.24740f), y(0.95861f), x(0.33586f), y(0.89978f), x(0.38553f), y(0.71351f))
        cubicTo(x(0.43519f), y(0.52724f), x(0.38978f), y(0.43060f), x(0.35998f), y(0.45441f))
        cubicTo(x(0.33018f), y(0.47822f), x(0.27956f), y(0.71631f), x(0.35478f), y(0.87317f))
        cubicTo(x(0.42999f), y(1.03004f), x(0.51892f), y(0.69390f), x(0.53453f), y(0.62808f))
        cubicTo(x(0.55014f), y(0.56225f), x(0.63955f), y(0.05805f), x(0.57332f), y(0.00623f))
        cubicTo(x(0.50710f), y(-0.04559f), x(0.48486f), y(0.50623f), x(0.48723f), y(0.60146f))
        cubicTo(x(0.48959f), y(0.69670f), x(0.50378f), y(0.87597f), x(0.54588f), y(0.91239f))
        cubicTo(x(0.58798f), y(0.94880f), x(0.68070f), y(0.64768f), x(0.70719f), y(0.51043f))
        cubicTo(x(0.73368f), y(0.37317f), x(0.76679f), y(0.03984f), x(0.73273f), y(0.01323f))
        cubicTo(x(0.69868f), y(-0.01338f), x(0.68259f), y(0.07205f), x(0.67171f), y(0.15048f))
        cubicTo(x(0.66083f), y(0.22892f), x(0.62204f), y(0.86057f), x(0.69678f), y(0.92639f))
        cubicTo(x(0.77152f), y(0.99222f), x(0.78855f), y(0.42997f), x(0.87275f), y(0.47822f))
        cubicTo(x(0.97340f), y(0.51043f), x(0.92329f), y(0.85998f), x(0.91438f), y(0.89776f))
        cubicTo(x(0.87047f), y(1.08403f), x(0.77956f), y(1.00000f), x(0.79943f), y(0.69608f))
        cubicTo(x(0.81523f), y(0.45436f), x(0.86282f), y(0.43277f), x(0.92006f), y(0.53081f))
        cubicTo(x(0.95979f), y(0.57703f), x(0.98959f), y(0.49440f), x(0.99905f), y(0.43200f))
    }
}
