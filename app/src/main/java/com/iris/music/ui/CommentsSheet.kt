package com.iris.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.iris.music.player.MusicComment
import com.iris.music.ui.theme.*
import kotlinx.coroutines.delay

/**
 * 评论区弹层：IrisSheet 外壳 + 吸顶折叠标题 + 评论列表 + 分页加载。
 *
 * 完全 1:1 复刻 NewSettingsScreen.kt 的吸顶折叠样式（同 TopListSheet）。
 *
 * 分页逻辑：
 * - 滑到倒数第 3 项时触发 onLoadMore（提前 2 项预加载，视觉上不卡）
 * - 底部显示加载指示器（isLoading 时转圈，hasMore 但未加载时提示"上滑加载更多"）
 * - 已加载数 >= 总数时不显示底部提示，整体加载完
 * - 防重入由 ViewModel 的 isCommentsLoading + hasMore 共同保证
 */
@Composable
internal fun CommentsSheet(
    songTitle: String,
    comments: List<MusicComment>,
    totalCount: Long,
    isLoading: Boolean,
    hasMore: Boolean,
    defaultCoverUri: android.net.Uri?,
    colors: IrisColors,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit
) {
    val drag = rememberSheetDragState()
    val listState = rememberLazyListState()

    val isScrolled = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 50

    // 滑到底自动加载：监听最后一项是否进入视口前 3 项范围
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            hasMore && !isLoading && totalItems > 0 && lastVisible >= totalItems - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    IrisSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.85f)
    ) {
        val panelBg = if (colors.isDark) Color(0xFF000000) else Color(0xFFF2F2F7)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(panelBg)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                SheetDragHandle(drag, onDismiss, colors.card)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(64.dp))
                Spacer(Modifier.height(4.dp))

                // 副标题：当前歌曲名（用户能确认打开的是哪首歌的评论）
                if (songTitle.isNotBlank()) {
                    Text(
                        songTitle,
                        color = colors.subText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }

                if (comments.isEmpty() && !isLoading) {
                    // 空态
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "还没有评论",
                            color = colors.subText,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 30.dp)
                    ) {
                        items(comments, key = { "comment-${it.id}" }) { comment ->
                            CommentRow(
                                comment = comment,
                                colors = colors,
                                defaultCoverUri = defaultCoverUri
                            )
                        }
                        // 底部加载指示器（占位条，不放进 items key 里避免动画冲突）
                        if (hasMore || isLoading) {
                            item(key = "comment-footer") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoading) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = colors.primary,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "加载中…",
                                                color = colors.subText,
                                                fontSize = 12.sp
                                            )
                                        }
                                    } else if (hasMore) {
                                        Text(
                                            "上滑加载更多",
                                            color = colors.subText,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 顶部渐变遮罩
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                panelBg,
                                panelBg.copy(alpha = 0.95f),
                                panelBg.copy(alpha = 0.7f),
                                Color.Transparent
                            )
                        )
                    )
                    .zIndex(1f)
            )

            // 折叠标题：有总数时显示"评论 (N)"，否则只显示"评论"
            val titleText = if (totalCount > 0) "评论 ($totalCount)" else "评论"
            CollapsingTitle(
                title = titleText,
                isScrolled = isScrolled,
                colors = colors,
                // "评论 (N)" 更宽，居中时给更大的偏移补偿
                centerOffset = if (totalCount > 0) 54.dp else 32.dp,
                modifier = Modifier.zIndex(2f)
            )
        }
    }
}

/**
 * 评论行：头像 + 昵称 + 内容 + 点赞数。
 * 头像走 3 秒超时兜底，占位用昵称首字母（没有头像时也能看出是谁）。
 */
@Composable
private fun CommentRow(
    comment: MusicComment,
    colors: IrisColors,
    defaultCoverUri: android.net.Uri?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        NetworkAvatar(
            uri = comment.avatarUrl,
            fallbackUri = defaultCoverUri,
            nickname = comment.nickname,
            seedId = comment.id,
            isDark = colors.isDark,
            modifier = Modifier.size(36.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                comment.nickname,
                color = colors.subText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                comment.content,
                color = colors.text,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "♥ ${comment.likedCount}",
                color = colors.subText,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * 评论头像：3 秒超时 + 兜底 + 占位。
 * 三阶段：网络头像 → 兜底图 → 昵称首字母。
 * 占位用首字母比纯色块更有信息量——评论区里能一眼看出不同用户。
 */
@Composable
private fun NetworkAvatar(
    uri: String,
    fallbackUri: android.net.Uri?,
    nickname: String,
    seedId: Long,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    var phase by remember(uri) { mutableStateOf(0) }

    LaunchedEffect(uri) {
        delay(3000)
        if (phase == 0) {
            phase = if (fallbackUri != null) 1 else 2
        }
    }

    val hue = (seedId % 360).toFloat()
    val placeholderColor = Color.hsv(hue, 0.45f, if (isDark) 0.25f else 0.85f)

    Box(
        modifier.background(placeholderColor),
        contentAlignment = Alignment.Center
    ) {
        when (phase) {
            0 -> AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { phase = if (fallbackUri != null) 1 else 2 },
                modifier = Modifier.fillMaxSize()
            )
            1 -> AsyncImage(
                model = fallbackUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { phase = 2 },
                modifier = Modifier.fillMaxSize()
            )
            else -> {
                // 占位：昵称首字母
                val initial = nickname.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                Text(
                    initial,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
