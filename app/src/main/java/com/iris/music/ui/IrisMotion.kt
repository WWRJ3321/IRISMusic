/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.iris.music.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 全局动效中心：果冻开关（设置里）驱动整个 App 的弹性手感。
 *
 * 打开：按钮按压、开关拨钮、卡片滑动等交互动画换成低阻尼弹簧（果冻回弹）；
 * 关闭：保持原有的平滑 tween / 高阻尼弹簧。
 *
 * 静态字段由 MainScreen 在状态变化时写入，各组件直接读取，不引入重组依赖。
 */
object IrisMotion {
    /**
     * 果冻动效总开关（PlayerViewModel.jellyAnim 同步而来）。
     * v5.0：改为 snapshot 状态——旧的 @Volatile 普通字段在 LaunchedEffect 里才写入，
     * 首帧总是非果冻规格，切开关后已组合的组件也拿不到新规格（不重组）。
     */
    var jelly: Boolean by androidx.compose.runtime.mutableStateOf(false)

    /**
     * 弹层（设置/歌单/均衡器/睡眠定时/加入歌单/报告）整体入场与退场。
     * v5.0 前这段规格在 MainScreen 里逐字复制了 6 份。面板本身的上滑由 irisSheetSlideIn 负责。
     */
    fun sheetEnter(): androidx.compose.animation.EnterTransition =
        androidx.compose.animation.fadeIn(tween(260, easing = IrisEase.standard))

    /** 遮罩比面板稍晚淡完：面板先滑走，背景再亮回来 */
    fun sheetExit(): androidx.compose.animation.ExitTransition =
        androidx.compose.animation.fadeOut(tween(220, delayMillis = 60, easing = IrisEase.exit))

    /** 按钮按压回弹（按压缩小 → 松手弹回） */
    fun pressScale(): FiniteAnimationSpec<Float> =
        if (jelly) spring(dampingRatio = 0.5f, stiffness = 380f)
        else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** 开关拨钮滑动 */
    fun knob(): FiniteAnimationSpec<Float> =
        if (jelly) spring(dampingRatio = 0.6f, stiffness = 420f)
        else tween(240, easing = IrisEase.emphasized)

    /** 卡片/元素位移动（v5.0：非果冻档由默认 FastOutSlowIn 换成强调减速曲线，起步干脆、收尾更长更柔） */
    fun move(): FiniteAnimationSpec<Float> =
        if (jelly) spring(dampingRatio = 0.68f, stiffness = 340f)
        else tween(IrisDur.MEDIUM, easing = IrisEase.emphasized)
    fun moveDp(): FiniteAnimationSpec<Dp> =
        if (jelly) spring(dampingRatio = 0.68f, stiffness = 340f)
        else tween(IrisDur.MEDIUM, easing = IrisEase.emphasized)
    /** 出现/展开（面板、区块） */
    fun appear(): FiniteAnimationSpec<Float> =
        if (jelly) spring(dampingRatio = 0.7f, stiffness = 300f)
        else tween(IrisDur.MEDIUM, easing = IrisEase.emphasized)

    /**
     * 区块展开 / 收起（设置分区、开关后出现的滑条、歌单输入行……全 App 24 处）。
     * v5.0 前一律是默认 `IrisMotion.expandIn()`：高度用默认弹簧、透明度同时起步，
     * 展开时内容先糊成一团再被撑开。现在：高度走强调曲线，内容稍晚 60ms 淡入（先腾地方再出字）；
     * 收起时内容先快速淡出，高度再收——层次分明。
     */
    fun expandIn(): androidx.compose.animation.EnterTransition =
        androidx.compose.animation.expandVertically(
            animationSpec = if (jelly) spring(dampingRatio = 0.8f, stiffness = 380f)
            else tween(IrisDur.MEDIUM, easing = IrisEase.emphasized),
            expandFrom = androidx.compose.ui.Alignment.Top
        ) + androidx.compose.animation.fadeIn(tween(220, delayMillis = 60, easing = IrisEase.emphasized))

    fun collapseOut(): androidx.compose.animation.ExitTransition =
        androidx.compose.animation.fadeOut(tween(120, easing = IrisEase.exit)) +
            androidx.compose.animation.shrinkVertically(
                animationSpec = tween(260, easing = IrisEase.emphasized),
                shrinkTowards = androidx.compose.ui.Alignment.Top
            )
}

/**
 * 缓动曲线（v5.0 统一）：全 App 只用这三条，手感一致。
 * - [emphasized] 强调减速：绝大多数"出现/移动"——起步快、长尾缓停，最有质感
 * - [exit] 加速离场：退场/淡出——越走越快，不拖泥带水
 * - [standard] 对称缓入缓出：来回往复、颜色渐变
 */
object IrisEase {
    val emphasized = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val exit = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/** 时长刻度（ms）：短 = 微交互，中 = 常规过渡，长 = 大块界面切换 */
object IrisDur {
    const val SHORT = 180
    const val MEDIUM = 320
    const val LONG = 440
}

/** [irisPressable] 的触感强度档位，对应 [Haptics] 的三档。 */
enum class PressFeedback { CLICK, TAP, NONE }

/**
 * 可按压元素的统一交互封装：按压缩放 + 触感 + 无水波点击。
 *
 * 项目里这套样板原本在 9 处逐字重写（PlayerCard 三处、MainScreen 五处、
 * SongDeck/PosterWall 若干）：每处都要自己 remember 一个 InteractionSource、
 * collectIsPressedAsState、animateFloatAsState、再手写 graphicsLayer 和
 * `indication = null`。散开写的代价不只是行数——按压幅度在各处从 0.82 到 0.94
 * 飘了五个值，手感并不统一，改动效果时要逐个文件找。
 *
 * 收拢成 Modifier 后：单一真源、缩放系数集中可调、触感档位显式声明。
 *
 * @param scaleDown 按下时缩到的比例，默认 [PRESS_SCALE_DEFAULT]
 * @param feedback 触感档位，主控制用 CLICK、次级用 TAP
 * @param enabled 为 false 时不响应点击也不缩放
 */
@Composable
fun Modifier.irisPressable(
    scaleDown: Float = PRESS_SCALE_DEFAULT,
    feedback: PressFeedback = PressFeedback.TAP,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = IrisMotion.pressScale(),
        label = "irisPressScale"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled
        ) {
            when (feedback) {
                PressFeedback.CLICK -> Haptics.click()
                PressFeedback.TAP -> Haptics.tap()
                PressFeedback.NONE -> Unit
            }
            onClick()
        }
}

/** 主控制按钮按压幅度：大按钮缩得多一点才有回弹感 */
const val PRESS_SCALE_STRONG = 0.85f

/** 通用按压幅度：图标按钮、开关、条目 */
const val PRESS_SCALE_DEFAULT = 0.92f

/**
 * 底部弹层统一入场：自底部上滑归位。
 * 替代各 Sheet 里各自手写一遍的 `Animatable(1f) + animateTo(0f, tween(300, FastOutSlowIn))
 * + graphicsLayer{ translationY = slide.value * 160.dp.toPx() }`——单一真源，观感统一。
 * 用法：把面板容器的 graphicsLayer 位移那一行换成 `.irisSheetSlideIn()`。
 */
@Composable
fun Modifier.irisSheetSlideIn(offsetDp: Dp = 160.dp): Modifier {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer { translationY = slide.value * offsetDp.toPx() }
}

/**
 * 底部弹层「拖把手下滑关闭」的共享状态。
 *
 * 面板根挂 [sheetDragTranslate]（背景随手指一起位移），把手挂 [sheetDragHandle]
 * （按住拖动 + 松手回弹/关闭）。下滑过阈值或向下快甩即关闭，否则弹簧归位。
 */
class SheetDragState internal constructor() {
    /** 当前下滑位移（px）。0 = 归位，越大越往下。普通状态，便于在手势里直接写。 */
    var offset by mutableFloatStateOf(0f)
    /** 关闭需要滑动的距离 = 面板高度，由 [sheetDragTranslate] 测得后写入。 */
    var dismissPx: Float = Float.MAX_VALUE / 4f
}

@Composable
fun rememberSheetDragState(): SheetDragState = remember { SheetDragState() }

/**
 * 面板根：在已有入场动画之外，再叠一层「拖把手」带来的位移。
 *
 * 必须挂在 [com.iris.music.ui.theme.irisSurface] **之前**：graphicsLayer 的 translationY
 * 只平移它内侧的绘制，irisSurface 画的卡片背景在外侧就不会跟着走——位移放它后面就会出现
 * "文字先下滑、卡片背景不动"。放在前面，平移图层才包住背景 + 内容整体。
 */
@Composable
fun Modifier.sheetDragTranslate(state: SheetDragState): Modifier = this
    .onSizeChanged { if (it.height > 0) state.dismissPx = it.height.toFloat() }
    // r9：改用布局位移而不是 graphicsLayer.translationY。玻璃表面靠 onGloballyPositioned 取
    // 自身坐标去对齐背板；纯图层平移不触发重新布局，拖动时坐标回调跟不上，背板取样位置
    // 落后一帧，卡片上沿的折射内容一跳一跳（用户实录「上边缘在移动的时候抽搐」）。
    // 布局位移每帧都会重新上报坐标；取整像素也避免半格重影。
    .offset { androidx.compose.ui.unit.IntOffset(0, kotlin.math.round(state.offset).toInt()) }

/** 下滑比例超过这个值就关闭 */
private const val SHEET_DISMISS_FRACTION = 0.30f
/** 向下瞬时速度超过这个值（px/s）判定为快甩关闭 */
private const val SHEET_FLING_VELOCITY = 1500f

/**
 * 把手：按住上下拖可拉着面板走，松手过阈值/快甩即关闭，否则回弹。
 *
 * 手势里直接写 [SheetDragState.offset]（普通状态，非受限挂起，可在受限作用域里直接赋值），
 * 收尾动画在 launch 出来的非受限协程里用 Animatable 跑。
 */
@Composable
fun Modifier.sheetDragHandle(
    state: SheetDragState,
    onDismiss: () -> Unit
): Modifier {
    val dismiss = rememberUpdatedState(onDismiss)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // 关闭残留保护：极快"关闭又立即重开"时 AnimatedVisibility 的 fadeOut 还没走完、
                // composition 未销毁，offset 会停在上次的 dismissPx。这里检测到就归零，
                // 否则按下瞬间卡片还停在屏幕外。正常打开时 offset=0，不触发。
                if (state.offset >= state.dismissPx) state.offset = 0f
                var lastY = 0f
                var lastT = 0L
                var velocity = 0f
                var lastDown = true
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    val down = change.pressed
                    val y = change.position.y
                    val t = change.uptimeMillis
                    if (down) {
                        if (lastDown) {
                            lastY = y; lastT = t; lastDown = false
                        } else {
                            val dy = y - lastY
                            if (t > lastT) velocity = dy * 1000f / (t - lastT)
                            // 只允许往下拉，往上钳到 0
                            state.offset = (state.offset + dy).coerceAtLeast(0f)
                            lastY = y; lastT = t
                        }
                        change.consume()
                    } else {
                        break
                    }
                }
                val start = state.offset
                val threshold = state.dismissPx * SHEET_DISMISS_FRACTION
                if (start >= threshold || velocity >= SHEET_FLING_VELOCITY) {
                    scope.launch {
                        Animatable(start).animateTo(
                            state.dismissPx,
                            tween(200, easing = LinearOutSlowInEasing)
                        ) { state.offset = value }
                        // 触发关闭后不要重置 offset：AnimatedVisibility 的 fadeOut 退出动画
                        // 还要渲染 180ms，节点此时仍存活。若这里立刻归零，卡片会从屏幕底
                        // 弹回原位再淡出 —— 就是"弹下去还闪一下"。让它停在屏幕外随 fadeOut
                        // 淡掉即可；下次打开是全新 composition，remember 的 offset 自然回到 0。
                        dismiss.value()
                    }
                } else {
                    scope.launch {
                        Animatable(start).animateTo(
                            0f,
                            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                        ) { state.offset = value }
                    }
                }
            }
        }
}

/**
 * 底部弹层的可拖拽把手：一条居中圆角药丸 + 上下撑高的命中区，按住下滑关闭面板。
 *
 * 直接把它放在面板内容顶部即可——它自带足够的纵向命中区（[hitHeight]），
 * 不再需要外面再套一层 Box 去撑高度。[pillColor] 决定药丸颜色（跟随卡片底色）。
 */
@Composable
fun SheetDragHandle(
    state: SheetDragState,
    onDismiss: () -> Unit,
    pillColor: Color,
    modifier: Modifier = Modifier,
    hitHeight: Dp = 24.dp
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(hitHeight)
            .sheetDragHandle(state, onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(pillColor)
        )
    }
}