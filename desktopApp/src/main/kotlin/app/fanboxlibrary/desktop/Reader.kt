package app.fanboxlibrary.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fanboxlibrary.shared.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class Preview(val bitmap: ImageBitmap? = null, val width: Int = 0, val height: Int = 0,
                            val loading: Boolean = false)

private val decodeThreads = Dispatchers.IO.limitedParallelism(3)

private fun decode(file: File, edge: Int): Preview {
    if (!file.isFile) return Preview()
    ImageIO.createImageInputStream(file)?.use { stream ->
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return Preview()
        try {
            reader.setInput(stream, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            val sample = ceil(max(width, height).toDouble() / edge).toInt().coerceAtLeast(1)
            val options = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            return Preview(reader.read(0, options).toComposeImageBitmap(), width, height)
        } finally { reader.dispose() }
    }
    return Preview()
}

@Composable
internal fun loadImage(file: File, hash: String, edge: Int): Preview = key(file.path, hash, edge) {
    val preview by produceState(Preview(loading = true)) {
        value = try { withContext(decodeThreads) { decode(file, edge) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Preview() }
    }
    preview
}

@Composable
internal fun PostReader(post: LibraryPost, enabled: Boolean, back: () -> Unit, edit: () -> Unit,
                        browse: (String) -> Unit, open: (LibraryAsset) -> Unit, export: (LibraryAsset) -> Unit) {
    val images = remember(post) { post.assets.filter { it.isImage } }
    val attachments = remember(post) { post.assets.filterNot { it.isImage } }
    var reading by remember(images.isEmpty()) { mutableStateOf(images.isNotEmpty()) }
    var page by remember { mutableStateOf(0) }
    val active = page.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(post.id) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            it.key == Key.Escape -> { back(); true }
            reading && it.key == Key.DirectionLeft && active > 0 -> { page = active - 1; true }
            reading && it.key == Key.DirectionRight && active < images.lastIndex -> { page = active + 1; true }
            else -> false
        }
    }.focusable().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = back) { Text("‹ 보관함") }
            Column(Modifier.weight(1f)) {
                Text(post.titleLabel(), style = MaterialTheme.typography.h6, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${post.artistLabel()} · ${post.yearLabel()}", style = MaterialTheme.typography.caption, color = muted)
            }
            if (post.sourceUrl.isNotBlank()) TextButton(onClick = { browse(post.sourceUrl) }) { Text("원문 열기") }
            OutlinedButton(onClick = edit, enabled = enabled && post.assets.isNotEmpty()) { Text("작품으로 묶기") }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShelfFilter("이미지 ${images.size}", reading) { reading = true }
            ShelfFilter("첨부 ${attachments.size}", !reading) { reading = false }
        }
        if (!reading) {
            if (attachments.isEmpty()) Text("첨부파일이 없습니다.", Modifier.padding(12.dp), color = muted)
            LazyColumn {
                items(attachments, key = { it.id }) { asset ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(asset.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${asset.variant} · ${formatSize(asset.size)}", style = MaterialTheme.typography.caption, color = muted)
                        }
                        TextButton(onClick = { export(asset) }) { Text("다른 이름으로 저장") }
                        if (canOpen(asset)) OutlinedButton(onClick = { open(asset) }) { Text("열기") }
                    }
                    Divider(color = line)
                }
            }
        } else if (images.isEmpty()) {
            Text("보관된 이미지가 없습니다.", Modifier.padding(12.dp), color = muted)
        } else {
            val image = images[active]
            key(image.id, image.sha256) {
                ImagePage(image, active, images.size, { page = it }, { open(image) })
            }
        }
    }
}

@Composable
private fun ColumnScope.ImagePage(asset: LibraryAsset, page: Int, pages: Int, change: (Int) -> Unit, open: () -> Unit) {
    val preview = loadImage(File(asset.path), asset.sha256, 4608)
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pixelScale by remember { mutableStateOf(1f) }
    fun magnify(new: Float) {
        scale = new.coerceIn(1f, max(12f, pixelScale))
        if (scale == 1f) pan = Offset.Zero
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF26242A)), contentAlignment = Alignment.Center) {
        val bitmap = preview.bitmap
        if (bitmap == null) {
            Text(if (preview.loading) "이미지를 여는 중…" else "미리보기를 열 수 없습니다. 원본 열기를 이용하세요.",
                color = Color.White)
        } else {
            val density = LocalDensity.current
            val viewWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val viewHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val fit = min(viewWidth / preview.width, viewHeight / preview.height)
            LaunchedEffect(fit) { pixelScale = 1f / fit }
            val boundX = max(0f, (preview.width * fit * scale - viewWidth) / 2f)
            val boundY = max(0f, (preview.height * fit * scale - viewHeight) / 2f)
            val translated = Offset(pan.x.coerceIn(-boundX, boundX), pan.y.coerceIn(-boundY, boundY))
            Box(Modifier.fillMaxSize().pointerInput(scale, boundX, boundY) {
                detectDragGestures { change, amount ->
                    change.consume()
                    pan = Offset((pan.x.coerceIn(-boundX, boundX) + amount.x).coerceIn(-boundX, boundX),
                        (pan.y.coerceIn(-boundY, boundY) + amount.y).coerceIn(-boundY, boundY))
                }
            }) {
                Image(bitmap, asset.name, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = translated.x; translationY = translated.y
                }, contentScale = ContentScale.Fit)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { change(page - 1) }, enabled = page > 0) { Text("이전") }
        Text("${page + 1} / $pages", style = MaterialTheme.typography.body2)
        TextButton(onClick = { change(page + 1) }, enabled = page + 1 < pages) { Text("다음") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { magnify(scale / 1.5f) }, enabled = scale > 1) { Text("축소") }
        Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.caption)
        TextButton(onClick = { magnify(scale * 1.5f) }, enabled = !preview.loading && preview.bitmap != null) { Text("확대") }
        TextButton(onClick = { magnify(1f) }) { Text("맞춤") }
        TextButton(onClick = { magnify(pixelScale) }, enabled = pixelScale > 1f) { Text("1:1") }
        TextButton(onClick = open) { Text("원본 열기") }
    }
    Text("${asset.name} · ${asset.variant} · ${formatSize(asset.size)}",
        style = MaterialTheme.typography.caption, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
