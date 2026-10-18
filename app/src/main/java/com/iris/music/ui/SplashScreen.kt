package com.iris.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun SplashScreen(style: Int, isDark: Boolean, onFinished: () -> Unit) {
    val drawProgress = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        drawProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 4000,
                easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
            )
        )
        delay(400)
        exitProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
        )
        onFinished()
    }

    val bgColor = if (isDark) Color.Black else Color.White
    val textColor = if (isDark) Color.White else Color.Black

    Box(
        Modifier
            .fillMaxSize()
            .background(bgColor.copy(alpha = 1f - exitProgress.value))
            .graphicsLayer {
                val scale = 1f + (exitProgress.value * 0.05f)
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 初始建立宽高
            val baseW = size.width * 0.85f
            val baseH = baseW / 3.45f
            
            val fullPath = buildHelloPath(baseW, baseH, 0f, 0f)
            val bounds = fullPath.getBounds()
            
            if (bounds.width > 0 && bounds.height > 0) {
                // 加上 10dp 的线条宽度，算出实际可能占用的最大矩形
                val strokeWidthPx = 10.dp.toPx()
                val realW = bounds.width + strokeWidthPx * 2
                val realH = bounds.height + strokeWidthPx * 2
                
                // 留出足够的安全边距，使用 0.65 倍的画布宽高来缩放，保证绝对不截断
                val scale = min(
                    (size.width * 0.65f) / realW,
                    (size.height * 0.65f) / realH
                )
                
                // 计算绝对居中的偏移量
                val dx = (size.width - realW * scale) / 2f - (bounds.left - strokeWidthPx) * scale
                val dy = (size.height - realH * scale) / 2f - (bounds.top - strokeWidthPx) * scale

                withTransform({
                    scale(scale, scale, pivot = Offset.Zero)
                    translate(dx / scale, dy / scale)
                    // 微调位置
                    when (style) {
                        1 -> translate(0f, 120f / scale)
                        2 -> translate(0f, -120f / scale)
                    }
                }) {
                    val measure = PathMeasure().apply { setPath(fullPath, false) }
                    val totalLen = measure.length
                    val trimmedLen = totalLen * drawProgress.value

                    if (trimmedLen > 0f) {
                        val segPath = Path()
                        measure.getSegment(0f, trimmedLen, segPath, true)

                        val colors = when (style) {
                            1 -> arrayOf(0f to Color(0xFF3D7BFF), 1f to Color(0xFFFF7EB6))
                            2 -> arrayOf(0f to Color(0xFF3D7BFF), 1f to Color(0xFF9D4EDD))
                            else -> arrayOf(0f to Color.Red, 0.5f to Color.Blue, 1f to Color.Green)
                        }

                        drawPath(
                            path = segPath,
                            brush = Brush.verticalGradient(
                                *colors,
                                startY = bounds.top - strokeWidthPx,
                                endY = bounds.bottom + strokeWidthPx
                            ),
                            style = Stroke(
                                width = strokeWidthPx / scale, // 抵消缩放
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }

        if (style == 1) {
            Text(
                text = "M U S I C",
                color = textColor.copy(alpha = 0.6f * drawProgress.value),
                fontSize = 16.sp,
                fontFamily = FontFamily.Serif,
                letterSpacing = 8.sp,
                modifier = Modifier.offset(y = (-110).dp)
            )
        }

        if (style == 2) {
            Text(
                text = "music",
                color = if (isDark) Color(0xFF9D4EDD).copy(alpha = 0.7f * drawProgress.value) 
                        else Color(0xFF7B2CBF).copy(alpha = 0.7f * drawProgress.value),
                fontSize = 28.sp,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.offset(x = 80.dp, y = 100.dp)
            )
        }
    }
}

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
