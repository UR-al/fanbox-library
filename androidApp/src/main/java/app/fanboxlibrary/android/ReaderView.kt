package app.fanboxlibrary.android

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fanboxlibrary.shared.LibraryPost
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun ReaderView(
    post: LibraryPost,
    visiblePosts: List<LibraryPost>,
    startId: String,
    onSwitchPost: (LibraryPost, startAtEnd: Boolean) -> Unit,
    back: () -> Unit,
    error: (String) -> Unit,
) {
    val colors = LocalAndroidGalleryColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val boundaryThresholdPx = with(density) { 88.dp.toPx() }

    val images = remember(post) { post.assets.filter { it.isImage } }
    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize().background(colors.cinemaBg), contentAlignment = Alignment.Center) {
            TextButton(onClick = back) { Text("‹ 작품으로 돌아가기", color = Color.White) }
        }
        return
    }

    val postIndex = remember(visiblePosts, post.id) { visiblePosts.indexOfFirst { it.id == post.id } }
    val prevPost = remember(visiblePosts, postIndex) {
        (postIndex - 1 downTo 0).firstOrNull { visiblePosts[it].assets.any { a -> a.isImage } }?.let { visiblePosts[it] }
    }
    val nextPost = remember(visiblePosts, postIndex) {
        if (postIndex < 0) null
        else (postIndex + 1 until visiblePosts.size).firstOrNull { visiblePosts[it].assets.any { a -> a.isImage } }?.let { visiblePosts[it] }
    }

    val originalIndices = remember(images) { images.indices.filter { !images[it].variant.equals("textless", true) } }
    val textlessIndices = remember(images) { images.indices.filter { images[it].variant.equals("textless", true) } }
    val hasBothVariants = originalIndices.isNotEmpty() && textlessIndices.isNotEmpty()

    val initialPage = remember(post.id, startId) { images.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage) { images.size }
    LaunchedEffect(post.id, startId) {
        val target = images.indexOfFirst { it.id == startId }.coerceIn(0, images.lastIndex)
        if (pager.currentPage != target) pager.scrollToPage(target)
    }

    val scope = rememberCoroutineScope()
    val visible = images[pager.currentPage.coerceIn(0, images.lastIndex)]
    val isCurrentTextless = visible.variant.equals("textless", true)
    var currentZoomed by remember(post.id, pager.currentPage) { mutableStateOf(false) }

    // Do not auto-switch works when swiping past the first/last image of a work;
    // users can switch works intentionally via bottom bar buttons or PostView swipe.
    val boundarySwipe = remember { object : NestedScrollConnection {} }
    val overscrollX = 0f

    val imageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }

    Column(Modifier.fillMaxSize().background(colors.cinemaBg)) {
        // Top Cinema Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xE60B0D13))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = back) { Text("‹ 작품", color = Color.White, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f)) {
                Text(
                    post.label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    visible.name,
                    color = Color(0xFF9AA3B5),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (hasBothVariants) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isCurrentTextless) colors.emerald else Color(0x33FFFFFF))
                        .border(1.dp, if (isCurrentTextless) colors.emerald else Color(0x44FFFFFF), RoundedCornerShape(18.dp))
                        .clickable {
                            val targetPage = if (isCurrentTextless) {
                                val pos = textlessIndices.indexOf(pager.currentPage).coerceAtLeast(0)
                                originalIndices[pos.coerceAtMost(originalIndices.lastIndex)]
                            } else {
                                val pos = originalIndices.indexOf(pager.currentPage).coerceAtLeast(0)
                                textlessIndices[pos.coerceAtMost(textlessIndices.lastIndex)]
                            }
                            scope.launch { pager.animateScrollToPage(targetPage) }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text(
                        if (isCurrentTextless) "Textless" else "원본",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            TextButton(onClick = { openAttachment(context, visible, error) }) {
                Text("다른 앱", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

        // Main Swipeable Image Stage
        Box(Modifier.fillMaxWidth().weight(1f)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().nestedScroll(boundarySwipe),
                key = { "${post.id}:${images[it].id}" },
            ) { index ->
                val asset = images[index]
                var scale by remember(asset.id) { mutableFloatStateOf(1f) }
                var offset by remember(asset.id) { mutableStateOf(Offset.Zero) }
                LaunchedEffect(scale, pager.currentPage) {
                    if (index == pager.currentPage) currentZoomed = scale > 1.02f
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(asset.id) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.2f
                                offset = Offset.Zero
                            })
                        }
                        .pointerInput(asset.id) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed >= 2 || (pressed > 0 && scale > 1f)) {
                                        val next = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                        val pan = offset * (next / scale) + event.calculatePan()
                                        val dx = size.width * (next - 1f) / 2
                                        val dy = size.height * (next - 1f) / 2
                                        offset = Offset(pan.x.coerceIn(-dx, dx), pan.y.coerceIn(-dy, dy))
                                        scale = next
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    SubcomposeAsyncImage(
                        model = File(asset.path),
                        contentDescription = asset.name,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                        loading = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = colors.accent)
                            }
                        },
                        error = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("이미지를 표시할 수 없습니다. 다른 앱으로 열어 보세요.", color = Color.White)
                            }
                        },
                    )
                }
            }

            // Edge pull indicator for switching work
            if (overscrollX > 24f && prevPost != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.accent.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text("‹ 이전 작품", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else if (overscrollX < -24f && nextPost != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.accent.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text("다음 작품 ›", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Bottom Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xE60B0D13))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prevPost != null) {
                IconButton(onClick = { onSwitchPost(prevPost, false) }) {
                    Icon(Icons.Rounded.ChevronLeft, "이전 작품", tint = colors.accent)
                }
            } else {
                Spacer(Modifier.width(40.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = pager.currentPage > 0,
                    onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                ) {
                    Text("이전", color = if (pager.currentPage > 0) Color.White else Color.Gray)
                }
                Text(
                    "${pager.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    enabled = pager.currentPage < images.lastIndex,
                    onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(images.lastIndex)) } },
                ) {
                    Text("다음", color = if (pager.currentPage < images.lastIndex) Color.White else Color.Gray)
                }
            }

            if (nextPost != null) {
                IconButton(onClick = { onSwitchPost(nextPost, false) }) {
                    Icon(Icons.Rounded.ChevronRight, "다음 작품", tint = colors.accent)
                }
            } else {
                Spacer(Modifier.width(40.dp))
            }
        }
    }
}
