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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.music.data.HeatCell
import com.iris.music.data.ListenReport
import com.iris.music.data.ListenReportBuilder
import com.iris.music.data.SongStat
import com.iris.music.data.StatRange
import com.iris.music.data.formatListenDuration
import com.iris.music.data.formatListenDurationShort
import com.iris.music.ui.theme.GlassContent
import com.iris.music.ui.theme.GlassLevel
import com.iris.music.ui.theme.IrisColors
import com.iris.music.ui.theme.IrisShape
import com.iris.music.ui.theme.irisSurface
import com.iris.music.ui.theme.readableOn
import com.iris.music.ui.theme.readableTextOn

/** 热力图一格的边长与格间距 */
private val HEAT_CELL = 11.dp
private val HEAT_GAP = 3.dp

/** 左侧星期标签栏宽度 */
private val HEAT_LABEL_WIDTH = 18.dp

/** 排行榜默认显示条数，展开后到 100 */
private const val TOP_COLLAPSED = 15

/**
 * 听歌报告：热力图 + 总时长（日/周/月/年）+ 单曲时长排行。
 *
 * 和其它弹层同构的自绘覆盖层（遮罩 + 底部玻璃卡片），内容用 LazyColumn 承载：
 * 排行榜最多 100 行，塞进 verticalScroll 的 Column 里会一次性组完全部行。
 */
@Composable
fun ListenReportSheet(
    report: ListenReport,
    onDismiss: () -> Unit,
    onRangeChange: (StatRange) -> Unit,
    onPlaySong: (Long) -> Unit,
    colors: IrisColors
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    var expanded by remember { mutableStateOf(false) }
    // 点热力图某格后在图上方显示那天的明细；再点空白格清空
    var picked by remember { mutableStateOf<HeatCell?>(null) }
    // 热力图形态：false = 迷你方格，true = 曲线图
    var chartCurve by remember { mutableStateOf(false) }
    // 拖把手下滑关闭
    val drag = rememberSheetDragState()

    IrisSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.92f),
        contentModifier = Modifier.navigationBarsPadding()
    ) {
        // ---- 抓手 + 标题 ----
        SheetDragHandle(drag, onDismiss, colors.card, Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(14.dp))

                    LazyColumn(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp)
                    ) {
                        item {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "听歌报告",
                                    color = onSheet,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "累计 ${formatListenDuration(report.allTimeMs)}",
                                    color = onSheet.copy(alpha = 0.55f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                        }

                        // ---- 范围切换 ----
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(IrisShape.item)
                                    .background(colors.card)
                                    .padding(3.dp)
                            ) {
                                StatRange.entries.forEach { r ->
                                    RangeTab(
                                        label = r.label,
                                        selected = report.range == r,
                                        colors = colors,
                                        modifier = Modifier.weight(1f)
                                    ) { onRangeChange(r) }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                        }

                        // ---- 总时长大字 + 四项副指标 ----
                        item {
                            TotalCard(report, colors, accent, onSheet)
                            Spacer(Modifier.height(16.dp))
                        }

                        // ---- 热力图 ----
                        item {
                            // 标题行：标题 + 右侧「方格/曲线」切换按钮（实色方条底色，同播放页格式徽章）
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionTitle("听歌热力图", onSheet)
                                Spacer(Modifier.weight(1f))
                                Box(
                                    Modifier
                                        .clip(IrisShape.chip)
                                        .background(accent)
                                        .clickable { Haptics.tap(); chartCurve = !chartCurve }
                                        .padding(horizontal = 9.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (chartCurve) "方格" else "曲线",
                                        color = accent.readableTextOn(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                picked?.let {
                                    "${ListenReportBuilder.dayLabel(it.day)} · " +
                                        if (it.ms > 0) formatListenDuration(it.ms) else "没有听歌"
                                } ?: if (chartCurve) "近一年每天的听歌时长，曲线越高听得越久"
                                   else "近一年每天的听歌时长，颜色越深听得越久",
                                color = if (picked != null) accent else onSheet.copy(alpha = 0.55f),
                                fontSize = 11.sp,
                                fontWeight = if (picked != null) FontWeight.Bold else FontWeight.Normal
                            )
                            Spacer(Modifier.height(10.dp))
                            if (chartCurve) {
                                HeatLineChart(
                                    report = report,
                                    colors = colors,
                                    pickedDay = picked?.day,
                                    onPick = { cell ->
                                        Haptics.tick()
                                        picked = if (picked?.day == cell.day) null else cell
                                    }
                                )
                            } else {
                                HeatMap(
                                    report = report,
                                    colors = colors,
                                    onPick = { cell ->
                                        Haptics.tick()
                                        picked = if (picked?.day == cell.day) null else cell
                                    }
                                )
                                Spacer(Modifier.height(10.dp))
                                HeatLegend(colors, onSheet)
                            }
                            Spacer(Modifier.height(18.dp))
                        }

                        // ---- 单曲排行 ----
                        item {
                            SectionTitle("单曲时长排行", onSheet)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${report.range.title}听得最久的歌，点一下直接播放",
                                color = onSheet.copy(alpha = 0.55f),
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        if (report.topSongs.isEmpty()) {
                            item {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(IrisShape.item)
                                        .background(colors.card)
                                        .padding(vertical = 22.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (report.loading) "统计中…" else "${report.range.title}还没有听歌记录",
                                        color = onSheet.copy(alpha = 0.55f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            val topMs = report.topSongs.first().ms.coerceAtLeast(1L)
                            val shown =
                                if (expanded) report.topSongs else report.topSongs.take(TOP_COLLAPSED)
                            itemsIndexed(shown, key = { _, stat -> "stat-${stat.songId}" }) { index, stat ->
                                RankRow(
                                    rank = index + 1,
                                    stat = stat,
                                    fraction = (stat.ms.toFloat() / topMs).coerceIn(0.02f, 1f),
                                    colors = colors,
                                    accent = accent,
                                    onSheet = onSheet,
                                    onClick = { onPlaySong(stat.songId) }
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            if (report.topSongs.size > TOP_COLLAPSED) {
                                item {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(IrisShape.item)
                                            .border(1.dp, onSheet.copy(alpha = 0.18f), IrisShape.item)
                                            .clickable { Haptics.tap(); expanded = !expanded }
                                            .padding(vertical = 11.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            if (expanded) "收起"
                                            else "展开全部 ${report.topSongs.size} 首",
                                            color = onSheet.copy(alpha = 0.7f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(16.dp)) }
                    }

                    // ---- 底部固定：关闭 ----
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(IrisShape.item)
                            .background(accent)
                            .clickable { Haptics.click(); onDismiss() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "完成",
                            color = accent.readableTextOn(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SectionTitle(text: String, onSheet: Color) {
    Text(text, color = onSheet, fontSize = 15.sp, fontWeight = FontWeight.Black)
}

/** 总时长卡：一个大数字 + 2×2 副指标 */
@Composable
private fun TotalCard(
    report: ListenReport,
    colors: IrisColors,
    accent: Color,
    onSheet: Color
) {
    Column(
        Modifier
            .fillMaxWidth()
            .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            "${report.range.title}听歌",
            color = onSheet.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (report.loading) "…" else formatListenDuration(report.totalMs),
            color = accent,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            MetricCell("歌曲", "${report.songCount} 首", onSheet, Modifier.weight(1f))
            MetricCell(
                if (report.range == StatRange.DAY) "记录" else "活跃",
                "${report.activeDays} 天",
                onSheet,
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            MetricCell(
                "日均",
                formatListenDuration(report.avgPerActiveDayMs),
                onSheet,
                Modifier.weight(1f)
            )
            MetricCell(
                if (report.peakDayLabel.isBlank()) "最长一天" else "最长 ${report.peakDayLabel}",
                formatListenDuration(report.peakDayMs),
                onSheet,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, onSheet: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            color = onSheet.copy(alpha = 0.5f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = onSheet,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

// ==================== 热力图 ====================

/**
 * 一年的日历热力图：横轴 53 周、纵轴周一→周日。
 *
 * 371 个格子用 Canvas 一次画完，而不是 371 个 Box —— 后者的布局与重组开销
 * 在低端机上足以让弹层打开时明显掉一帧。点击命中位置由坐标反算格子下标。
 */
@Composable
private fun HeatMap(
    report: ListenReport,
    colors: IrisColors,
    onPick: (HeatCell) -> Unit
) {
    val weeks = report.heatWeeks
    if (weeks.isEmpty()) return

    val onSheet = if (colors.isDark) Color.White else Color.Black
    val emptyColor = if (colors.isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f)
    val base = colors.primary.readableOn(colors.surface, 1.6f)

    val step = HEAT_CELL + HEAT_GAP
    val gridWidth = step * weeks.size - HEAT_GAP
    val gridHeight = step * 7 - HEAT_GAP
    val stepPx = with(LocalDensity.current) { step.toPx() }
    val cellPx = with(LocalDensity.current) { HEAT_CELL.toPx() }
    val radiusPx = cellPx * 0.3f

    val scroll = rememberScrollState()
    // 打开时停在最右端（最近的一周）；用户手动滚过之后不再抢夺位置
    LaunchedEffect(scroll.maxValue) {
        if (scroll.maxValue > 0 && scroll.value == 0) scroll.scrollTo(scroll.maxValue)
    }

    // 最新数据在最右边，横向滚动条挂在整块上（含左侧星期栏则会跟着滚走）
    Row(Modifier.fillMaxWidth()) {
        // 左：星期标签，与网格行一一对齐
        Column(
            Modifier
                .width(HEAT_LABEL_WIDTH)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(HEAT_GAP)
        ) {
            repeat(7) { row ->
                Box(
                    Modifier
                        .height(HEAT_CELL)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 只标单数行（一/三/五），全标会挤在一起
                    val label = when (row) {
                        0 -> "一"
                        2 -> "三"
                        4 -> "五"
                        else -> null
                    }
                    if (label != null) {
                        Text(
                            label,
                            color = onSheet.copy(alpha = 0.45f),
                            fontSize = 9.sp,
                            softWrap = false,
                            // 高度锁在 11dp，字号随系统缩放会被压掉一截；
                            // unbounded 让它按自身高度测量再居中
                            modifier = Modifier.wrapContentHeight(unbounded = true)
                        )
                    }
                }
            }
        }

        Column(Modifier.horizontalScroll(scroll)) {
            // 上：月份标签，按所在列偏移
            Box(Modifier.width(gridWidth).height(14.dp)) {
                report.heatMonths.forEach { m ->
                    Text(
                        m.label,
                        color = onSheet.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        softWrap = false,
                        modifier = Modifier
                            .offset(x = step * m.weekIndex)
                            .wrapContentHeight(unbounded = true)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Canvas(
                Modifier
                    .width(gridWidth)
                    .height(gridHeight)
                    .pointerInput(weeks) {
                        detectTapGestures { offset ->
                            val w = (offset.x / stepPx).toInt()
                            val d = (offset.y / stepPx).toInt()
                            weeks.getOrNull(w)?.getOrNull(d)?.let { if (!it.future) onPick(it) }
                        }
                    }
            ) {
                // 用下标 for 而不是嵌套 forEachIndexed：内层的 return@forEachIndexed
                // 在两层同名标签下有歧义（编译器会警告，且实际跳出的层不直观）
                for (w in weeks.indices) {
                    val col = weeks[w]
                    for (d in col.indices) {
                        val cell = col[d]
                        if (cell.future) continue
                        drawRoundRect(
                            color = heatColor(cell.level, base, emptyColor),
                            topLeft = Offset(w * stepPx, d * stepPx),
                            size = Size(cellPx, cellPx),
                            cornerRadius = CornerRadius(radiusPx, radiusPx)
                        )
                    }
                }
            }
        }
    }
}

/** 图例：少 ▢▢▢▢▢ 多 */
@Composable
private fun HeatLegend(colors: IrisColors, onSheet: Color) {
    val emptyColor = if (colors.isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f)
    val base = colors.primary.readableOn(colors.surface, 1.6f)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("少", color = onSheet.copy(alpha = 0.5f), fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        (0..4).forEach { level ->
            Box(
                Modifier
                    .size(HEAT_CELL)
                    .clip(RoundedCornerShape(3.dp))
                    .background(heatColor(level, base, emptyColor))
            )
            Spacer(Modifier.width(HEAT_GAP))
        }
        Spacer(Modifier.width(3.dp))
        Text("多", color = onSheet.copy(alpha = 0.5f), fontSize = 10.sp)
    }
}

/**
 * 听歌时长曲线图：把一年的格子拉平成一条按天的面积曲线，比迷你方格更直观地看出趋势。
 *
 * 与方格图共用同一份 heatWeeks 数据和同一套「点选某天」交互：横轴 = 全局列序索引、
 * 纵轴 = 当天时长 ÷ 最高值。点曲线取最近的一天回填明细。
 */
@Composable
private fun HeatLineChart(
    report: ListenReport,
    colors: IrisColors,
    pickedDay: Long?,
    onPick: (HeatCell) -> Unit
) {
    val weeks = report.heatWeeks
    if (weeks.isEmpty()) return
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 2.2f)
    val total = weeks.size * 7

    // 展平成按天序列，保留全局列序索引用于 x 定位；future（本周尾部）不参与。
    val days = ArrayList<Pair<Int, HeatCell>>(total)
    for (w in weeks.indices) {
        val col = weeks[w]
        for (d in col.indices) {
            val cell = col[d]
            if (!cell.future) days.add(w * 7 + d to cell)
        }
    }
    if (days.isEmpty()) return

    // 滚动平均：把每天的毛刺抹平成趋势。窗口 7 天，不足时退化为可用天数。
    val smooth = DoubleArray(days.size)
    val win = 7
    val raw = LongArray(days.size) { days[it].second.ms }
    for (i in days.indices) {
        var sum = 0L
        var cnt = 0
        val lo = (i - win / 2).coerceAtLeast(0)
        val hi = (i + win / 2).coerceAtMost(days.lastIndex)
        for (j in lo..hi) { sum += raw[j]; cnt++ }
        smooth[i] = if (cnt == 0) 0.0 else sum.toDouble() / cnt
    }
    val maxV = smooth.max().coerceAtLeast(1.0)

    // 横轴只铺满「有听歌记录」的区间：首日 → 末日。
    // 否则一整年的空白天会把真正有数据的那段硬生生压扁成贴底的一条线。
    val firstI = days.indexOfFirst { it.second.ms > 0 }.coerceAtLeast(0)
    val lastI = days.indexOfLast { it.second.ms > 0 }.let { if (it < 0) days.lastIndex else it }
    val span = (lastI - firstI).coerceAtLeast(1)

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(IrisShape.item)
                .background(if (colors.isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
        ) {
            val pickedIdx = if (pickedDay == null) null
                else days.indexOfFirst { it.second.day == pickedDay }.takeIf { it >= 0 }
            Canvas(
                Modifier
                    .matchParentSize()
                    .pointerInput(days) {
                        detectTapGestures { offset ->
                            // 仅在有效区间内命中；区间外按端点钳制
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            val target = firstI + frac * span
                            val nearest = days.indices.minByOrNull { kotlin.math.abs(it - target) }
                            nearest?.let { onPick(days[it].second) }
                        }
                    }
            ) {
                val stepX = size.width / span
                val topPad = 10.dp.toPx()
                val botPad = 8.dp.toPx()
                val usable = (size.height - topPad - botPad).coerceAtLeast(1f)
                // x 以「有效区间首点」为原点
                fun xOf(i: Int) = (i - firstI) * stepX
                fun yOf(v: Double) = topPad + usable * (1f - (v / maxV).toFloat().coerceIn(0f, 1f))

                // 采样有效区间内的平滑点
                val pts = ArrayList<Offset>(lastI - firstI + 1)
                for (i in firstI..lastI) pts.add(Offset(xOf(i), yOf(smooth[i])))

                val fill = Path()
                val line = Path()
                if (pts.isNotEmpty()) {
                    fun catmull(
                        p0: Offset, p1: Offset, p2: Offset, p3: Offset, to: Path, first: Boolean
                    ) {
                        // Catmull-Rom → 三次贝塞尔，得到连续平滑且不越界的曲线
                        val c1x = p1.x + (p2.x - p0.x) / 6f
                        val c1y = p1.y + (p2.y - p0.y) / 6f
                        val c2x = p2.x - (p3.x - p1.x) / 6f
                        val c2y = p2.y - (p3.y - p1.y) / 6f
                        if (first) to.moveTo(p1.x, p1.y)
                        to.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
                    }
                    // 线
                    line.moveTo(pts[0].x, pts[0].y)
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts.getOrElse(i - 1) { pts[0] }
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        val p3 = pts.getOrElse(i + 2) { pts.last() }
                        catmull(p0, p1, p2, p3, line, first = false)
                    }
                    // 面积填充：复用同一条平滑线，再封口到底
                    fill.moveTo(pts[0].x, size.height - botPad)
                    fill.lineTo(pts[0].x, pts[0].y)
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts.getOrElse(i - 1) { pts[0] }
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        val p3 = pts.getOrElse(i + 2) { pts.last() }
                        catmull(p0, p1, p2, p3, fill, first = false)
                    }
                    fill.lineTo(pts.last().x, size.height - botPad)
                    fill.close()
                }
                drawPath(
                    fill,
                    Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.34f),
                        1f to accent.copy(alpha = 0.02f)
                    )
                )
                drawPath(
                    line,
                    color = accent,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )

                if (pickedIdx != null && pickedIdx in firstI..lastI) {
                    val x = xOf(pickedIdx)
                    val y = yOf(smooth[pickedIdx])
                    drawLine(
                        color = accent.copy(alpha = 0.4f),
                        start = Offset(x, topPad),
                        end = Offset(x, size.height - botPad),
                        strokeWidth = 1.dp.toPx()
                    )
                    // 选中点画在原始当天值上，圆点更贴合实际数据
                    drawCircle(accent, radius = 4.dp.toPx(), center = Offset(x, yOf(raw[pickedIdx].toDouble())))
                    drawCircle(Color.White, radius = 1.8.dp.toPx(), center = Offset(x, yOf(raw[pickedIdx].toDouble())))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        // 月份刻度：仅标注落在有效区间内的月份，按裁剪后区间比例横向定位
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val full = maxWidth
            report.heatMonths.forEach { m ->
                val gidx = m.weekIndex * 7
                val frac = (gidx - firstI).toFloat() / span
                if (frac in -0.04f..0.95f) {
                    Text(
                        m.label,
                        color = onSheet.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        softWrap = false,
                        modifier = Modifier
                            .offset(x = (full * frac.coerceIn(0f, 0.92f)))
                            .wrapContentHeight(unbounded = true)
                    )
                }
            }
        }
    }
}

/**
 * 分档 → 颜色。四档都用主题色的不同透明度，而不是四个写死的色值：
 * 换主题时热力图要跟着变，否则霓虹配色下会突然出现一片绿。
 */
private fun heatColor(level: Int, base: Color, empty: Color): Color = when (level) {
    0 -> empty
    1 -> base.copy(alpha = 0.30f)
    2 -> base.copy(alpha = 0.52f)
    3 -> base.copy(alpha = 0.74f)
    else -> base.copy(alpha = 0.96f)
}

// ==================== 排行榜 ====================

@Composable
private fun RankRow(
    rank: Int,
    stat: SongStat,
    fraction: Float,
    colors: IrisColors,
    accent: Color,
    onSheet: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(IrisShape.item)
            .background(colors.card)
            .clickable { Haptics.tap(); onClick() }
    ) {
        // 底层比例条：直接把"听了多久"画进行背景，比再加一条进度条省一层。
        // 用 matchParentSize + drawBehind 而不是 fillMaxWidth(fraction)：
        // matchParentSize 不参与父级测量，两者叠在一起时宽度比例会被父尺寸覆盖。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        color = accent.copy(alpha = if (rank <= 3) 0.20f else 0.11f),
                        size = Size(size.width * fraction, size.height)
                    )
                }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 名次：前三名用主题色实心，其余只有数字
            Box(
                Modifier.width(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$rank",
                    color = if (rank <= 3) accent else onSheet.copy(alpha = 0.45f),
                    fontSize = if (rank <= 3) 15.sp else 12.sp,
                    fontWeight = if (rank <= 3) FontWeight.Black else FontWeight.Bold,
                    softWrap = false
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stat.title,
                    color = onSheet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stat.artist + if (stat.plays > 0) " · 约 ${stat.plays} 次" else "",
                    color = onSheet.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                formatListenDurationShort(stat.ms),
                color = onSheet,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.End,
                softWrap = false
            )
        }
    }
}

@Composable
private fun RangeTab(
    label: String,
    selected: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    // 选中渐变：背景与文字色补间，切换统计范围不硬跳
    val t by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(220),
        label = "rangeTab"
    )
    Box(
        modifier
            .clip(IrisShape.chip)
            .background(lerp(Color.Transparent, colors.primary.copy(alpha = 0.18f), t))
            .clickable { Haptics.tap(); onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = lerp(onSheet.copy(alpha = 0.72f), colors.primary.readableOn(colors.surface, 3.2f), t),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            softWrap = false
        )
    }
}
