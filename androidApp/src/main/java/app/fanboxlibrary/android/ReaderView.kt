package app.fanboxlibrary.android

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fanboxlibrary.shared.LibraryPost
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun ReaderView(post: LibraryPost, startId: String, back: () -> Unit, error: (String) -> Unit) {
    val context = LocalContext.current
    val images = remember(post) { post.assets.filter { it.isImage } }
    if (images.isEmpty()) {
        TextButton(onClick = back) { Text("‹ 작품으로 돌아가기") }
        return
    }
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { images.size }
    val scope = rememberCoroutineScope()
    val visible = images[pager.currentPage]
    val imageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }
    Column(Modifier.fillMaxSize().background(Color(0xff19201c))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("‹ 작품", color = Color.White) }
            Text(visible.name, Modifier.weight(1f), color = Color.White,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { openAttachment(context, visible, error) }) { Text("다른 앱", color = Color.White) }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), key = { images[it].id }) { index ->
            val asset = images[index]
            var scale by remember(asset.id) { mutableFloatStateOf(1f) }
            var offset by remember(asset.id) { mutableStateOf(Offset.Zero) }
            Box(Modifier.fillMaxSize().clipToBounds()
                .pointerInput(asset.id) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2f
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
                }, contentAlignment = Alignment.Center) {
                SubcomposeAsyncImage(
                    model = File(asset.path), contentDescription = asset.name,
                    imageLoader = imageLoader, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    },
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                    error = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("이미지를 표시할 수 없습니다. 다른 앱으로 열어 보세요.", color = Color.White)
                        }
                    }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = pager.currentPage > 0, onClick = {
                scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
            }) { Text("이전", color = if (pager.currentPage > 0) Color.White else Color.Gray) }
            Text("${pager.currentPage + 1} / ${images.size}", color = Color.White)
            TextButton(enabled = pager.currentPage < images.lastIndex, onClick = {
                scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(images.lastIndex)) }
            }) { Text("다음", color = if (pager.currentPage < images.lastIndex) Color.White else Color.Gray) }
        }
    }
}
