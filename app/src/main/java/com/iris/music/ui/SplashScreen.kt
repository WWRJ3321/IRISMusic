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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(style: Int, onFinished: () -> Unit) {
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - exitProgress.value))
            .graphicsLayer {
                val scale = 1f + (exitProgress.value * 0.05f)
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        // 稍微缩小一点实际图形比例，留出边界
        val helloW = screenWidth * 0.75f
        val helloH = helloW / 3.45f

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.graphicsLayer {
                alpha = 1f - (exitProgress.value * 1.5f).coerceIn(0f, 1f)
                val textScale = 1f + (exitProgress.value * 0.15f)
                scaleX = textScale
                scaleY = textScale
            }
        ) {
            Canvas(
                Modifier
                    .size(helloW, helloH)
                    .graphicsLayer {
                        translationY = when (style) {
                            1 -> 20.dp.toPx()
                            2 -> -20.dp.toPx()
                            else -> 0f
                        }
                    }
            ) {
                // 因为原曲线有超出 0~1 范围的控制点，我们在这里对画布做一点安全内缩
                val safeW = size.width * 0.9f
                val safeH = size.height * 0.9f
                val offsetX = (size.width - safeW) / 2f
                val offsetY = (size.height - safeH) / 2f
                
                val fullPath = buildHelloPath(safeW, safeH, offsetX, offsetY)
                val measure = PathMeasure().apply { setPath(fullPath, false) }
                val totalLen = measure.length
                if (totalLen <= 0f) return@Canvas

                val trimmedLen = totalLen * drawProgress.value
                if (trimmedLen <= 0f) return@Canvas

                val segPath = Path()
                measure.getSegment(0f, trimmedLen, segPath, true)

                val colors = when (style) {
                    1 -> arrayOf(0f to Color(0xFF3D7BFF), 1f to Color(0xFFFF7EB6))
                    2 -> arrayOf(0f to Color(0xFF3D7BFF), 1f to Color(0xFF9D4EDD))
                    else -> arrayOf(0f to Color.Red, 0.5f to Color.Blue, 1f to Color.Green)
                }

                drawPath(
                    path = segPath,
                    brush = Brush.verticalGradient(*colors, startY = 0f, endY = size.height),
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            if (style == 1) {
                Text(
                    text = "M U S I C",
                    color = Color.White.copy(alpha = 0.6f * drawProgress.value),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 8.sp,
                    modifier = Modifier.offset(y = (-85).dp)
                )
            }

            if (style == 2) {
                Text(
                    text = "music",
                    color = Color(0xFF9D4EDD).copy(alpha = 0.5f * drawProgress.value),
                    fontSize = 28.sp,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.offset(x = 80.dp, y = 60.dp)
                )
            }
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
