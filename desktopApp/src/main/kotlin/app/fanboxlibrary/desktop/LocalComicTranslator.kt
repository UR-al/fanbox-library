package app.fanboxlibrary.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class TranslatorModelPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val modelFileName: String,
    val modelUrl: String,
    val minModelBytes: Long,
    val mmprojFileName: String,
    val mmprojUrl: String,
    val minMmprojBytes: Long,
) {
    QWEN3_VL_2B(
        id = "qwen3-vl-2b-abliterated",
        title = "초소형 무검열 2B (1.5GB · 초고속)",
        subtitle = "Qwen3-VL-2B-Abliterated · 일본어 세로만화 + 영어 코믹스 지원",
        modelFileName = "Qwen3-VL-2B-Instruct-abliterated.Q4_K_M.gguf",
        modelUrl = "https://huggingface.co/mradermacher/Qwen3-VL-2B-Instruct-abliterated-GGUF/resolve/main/Qwen3-VL-2B-Instruct-abliterated.Q4_K_M.gguf",
        minModelBytes = 900_000_000L,
        mmprojFileName = "Qwen3-VL-2B-Instruct-abliterated.mmproj-Q8_0.gguf",
        mmprojUrl = "https://huggingface.co/mradermacher/Qwen3-VL-2B-Instruct-abliterated-GGUF/resolve/main/Qwen3-VL-2B-Instruct-abliterated.mmproj-Q8_0.gguf",
        minMmprojBytes = 350_000_000L,
    ),
    QWEN3_VL_4B(
        id = "qwen3-vl-4b-abliterated",
        title = "고품질 무검열 4B (2.8GB · 추천)",
        subtitle = "Qwen3-VL-4B-Abliterated · 은어·성인 대사·긴 문맥 번역 강화",
        modelFileName = "Qwen3-VL-4B-Instruct-abliterated.Q4_K_M.gguf",
        modelUrl = "https://huggingface.co/mradermacher/Qwen3-VL-4B-Instruct-abliterated-GGUF/resolve/main/Qwen3-VL-4B-Instruct-abliterated.Q4_K_M.gguf",
        minModelBytes = 2_000_000_000L,
        mmprojFileName = "Qwen3-VL-4B-Instruct-abliterated.mmproj-Q8_0.gguf",
        mmprojUrl = "https://huggingface.co/mradermacher/Qwen3-VL-4B-Instruct-abliterated-GGUF/resolve/main/Qwen3-VL-4B-Instruct-abliterated.mmproj-Q8_0.gguf",
        minMmprojBytes = 350_000_000L,
    );

    companion object {
        fun fromId(id: String): TranslatorModelPreset =
            entries.firstOrNull { it.id == id } ?: QWEN3_VL_4B
    }
}

/**
 * Normalized 0..1000 bounding box [xmin, ymin, xmax, ymax] + original & Korean translated dialogue.
 */
internal data class TranslatedBubble(
    val index: Int,
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val original: String,
    val korean: String,
) {
    val hasValidBox: Boolean
        get() = xmax > xmin + 8f && ymax > ymin + 8f && xmin >= 0f && ymin >= 0f && xmax <= 1005f && ymax <= 1005f
}

internal data class PageTranslationResult(
    val presetId: String,
    val bubbles: List<TranslatedBubble>,
    val rawSummary: String = "",
)

internal object LocalComicTranslator {
    private const val FALLBACK_VULKAN_ZIP =
        "https://github.com/ggml-org/llama.cpp/releases/download/b12676/llama-b12676-bin-win-vulkan-x64.zip"
    private const val FALLBACK_CPU_ZIP =
        "https://github.com/ggml-org/llama.cpp/releases/download/b12676/llama-b12676-bin-win-cpu-x64.zip"

    private val mutex = Mutex()

    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val inferHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    @Volatile private var serverProcess: Process? = null
    @Volatile private var serverPort: Int = 0
    @Volatile private var activeLoadedPreset: TranslatorModelPreset? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { serverProcess?.destroyForcibly() }
            },
        )
    }

    private fun baseDir(): File {
        val local = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        return File(File(local, "FanboxLibrary"), "local-translator").apply { mkdirs() }
    }

    private fun binDir(): File = File(baseDir(), "bin").apply { mkdirs() }
    private fun modelsDir(): File = File(baseDir(), "models").apply { mkdirs() }
    private fun cacheDir(): File = File(baseDir(), "cache").apply { mkdirs() }

    private fun presetConfigFile(): File = File(baseDir(), "selected-preset.txt")

    fun selectedPreset(): TranslatorModelPreset {
        val saved = runCatching {
            val f = presetConfigFile()
            if (f.isFile) f.readText(StandardCharsets.UTF_8).trim() else ""
        }.getOrDefault("")
        return TranslatorModelPreset.fromId(saved.ifEmpty { TranslatorModelPreset.QWEN3_VL_4B.id })
    }

    fun saveSelectedPreset(preset: TranslatorModelPreset) {
        runCatching { presetConfigFile().writeText(preset.id, StandardCharsets.UTF_8) }
    }

    fun isServerRunning(): Boolean = serverProcess?.isAlive == true && serverPort > 0

    fun isPresetInstalled(preset: TranslatorModelPreset): Boolean {
        val serverExe = findLlamaServerExe() ?: return false
        val mFile = File(modelsDir(), preset.modelFileName)
        val mmFile = File(modelsDir(), preset.mmprojFileName)
        return serverExe.isFile &&
            mFile.isFile && mFile.length() >= preset.minModelBytes &&
            mmFile.isFile && mmFile.length() >= preset.minMmprojBytes
    }

    private fun findLlamaServerExe(): File? {
        val root = binDir()
        if (!root.isDirectory) return null
        return root.walkTopDown().maxDepth(4).firstOrNull {
            it.isFile && it.name.equals("llama-server.exe", ignoreCase = true)
        }
    }

    fun stopServer() {
        val proc = serverProcess
        serverProcess = null
        serverPort = 0
        activeLoadedPreset = null
        if (proc != null) {
            runCatching {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    fun loadCachedTranslation(sha256: String): PageTranslationResult? {
        val file = File(cacheDir(), "$sha256.json")
        if (!file.isFile) return null
        return runCatching {
            val root = SimpleJson.parse(file.readText(StandardCharsets.UTF_8)) as? Map<*, *> ?: return null
            val presetId = (root["presetId"] as? String).orEmpty()
            val arr = (root["bubbles"] as? List<*>).orEmpty()
            val bubbles = arr.mapIndexedNotNull { idx, item ->
                val m = item as? Map<*, *> ?: return@mapIndexedNotNull null
                val kor = (m["korean"] as? String)?.trim().orEmpty()
                if (kor.isEmpty()) return@mapIndexedNotNull null
                TranslatedBubble(
                    index = (m["index"] as? Number)?.toInt() ?: (idx + 1),
                    xmin = (m["xmin"] as? Number)?.toFloat() ?: 0f,
                    ymin = (m["ymin"] as? Number)?.toFloat() ?: 0f,
                    xmax = (m["xmax"] as? Number)?.toFloat() ?: 0f,
                    ymax = (m["ymax"] as? Number)?.toFloat() ?: 0f,
                    original = (m["original"] as? String)?.trim().orEmpty(),
                    korean = kor,
                )
            }
            if (bubbles.isEmpty()) null else PageTranslationResult(presetId, bubbles, (root["rawSummary"] as? String).orEmpty())
        }.getOrNull()
    }

    private fun saveCachedTranslation(sha256: String, result: PageTranslationResult) {
        if (result.bubbles.isEmpty()) return
        val file = File(cacheDir(), "$sha256.json")
        val sb = StringBuilder()
        sb.append("""{"presetId":${jsonQuote(result.presetId)},"rawSummary":${jsonQuote(result.rawSummary)},"bubbles":[""")
        result.bubbles.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append(
                """{"index":${b.index},"xmin":${b.xmin.roundToInt()},"ymin":${b.ymin.roundToInt()},"xmax":${b.xmax.roundToInt()},"ymax":${b.ymax.roundToInt()},"original":${jsonQuote(b.original)},"korean":${jsonQuote(b.korean)}}""",
            )
        }
        sb.append("]}")
        runCatching { file.writeText(sb.toString(), StandardCharsets.UTF_8) }
    }

    suspend fun ensureInstalledAndRunning(
        preset: TranslatorModelPreset,
        onStatus: (String) -> Unit,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            // 1. Ensure llama-server.exe exists
            if (findLlamaServerExe() == null) {
                onStatus("로컬 번역 엔진(llama.cpp Vulkan GPU) 다운로드 중…")
                val zipFile = File(baseDir(), "llama-engine.zip")
                downloadFileWithProgress(FALLBACK_VULKAN_ZIP, zipFile, "번역 엔진") { onStatus(it) }
                onStatus("번역 엔진 압축 해제 중…")
                unzipFlatOrNested(zipFile, binDir())
                zipFile.delete()
            }

            // 2. Ensure main GGUF model exists
            val modelFile = File(modelsDir(), preset.modelFileName)
            if (!modelFile.isFile || modelFile.length() < preset.minModelBytes) {
                downloadFileWithProgress(preset.modelUrl, modelFile, preset.title) { onStatus(it) }
            }

            // 3. Ensure vision mmproj GGUF exists
            val mmprojFile = File(modelsDir(), preset.mmprojFileName)
            if (!mmprojFile.isFile || mmprojFile.length() < preset.minMmprojBytes) {
                downloadFileWithProgress(preset.mmprojUrl, mmprojFile, "비전 어댑터(mmproj)") { onStatus(it) }
            }

            // 4. Start or switch llama-server.exe if not already running with this preset
            if (isServerRunning() && activeLoadedPreset == preset && checkServerHealthy(serverPort)) {
                return@withContext
            }

            stopServer()
            val exe = findLlamaServerExe() ?: throw IOException("llama-server.exe 실행 파일을 찾을 수 없습니다.")
            val started = tryStartServerProcess(exe, modelFile, mmprojFile, useGpu = true, onStatus = onStatus)
                ?: run {
                    // Fallback to CPU build if Vulkan runtime fails on older machines
                    onStatus("GPU 초기화 실패 — 호환 CPU 엔진으로 전환 중…")
                    val cpuZip = File(baseDir(), "llama-cpu.zip")
                    downloadFileWithProgress(FALLBACK_CPU_ZIP, cpuZip, "CPU 호환 엔진") { onStatus(it) }
                    val cpuDir = File(binDir(), "cpu").apply { mkdirs() }
                    unzipFlatOrNested(cpuZip, cpuDir)
                    cpuZip.delete()
                    val cpuExe = cpuDir.walkTopDown().firstOrNull { it.name.equals("llama-server.exe", true) }
                        ?: throw IOException("CPU 엔진 실행 파일을 찾을 수 없습니다.")
                    tryStartServerProcess(cpuExe, modelFile, mmprojFile, useGpu = false, onStatus = onStatus)
                }
                ?: throw IOException("로컬 번역 모델을 메모리에 올리지 못했습니다.")

            serverProcess = started.first
            serverPort = started.second
            activeLoadedPreset = preset
        }
    }

    private suspend fun tryStartServerProcess(
        exe: File,
        modelFile: File,
        mmprojFile: File,
        useGpu: Boolean,
        onStatus: (String) -> Unit,
    ): Pair<Process, Int>? {
        val port = ServerSocket(0).use { it.localPort }
        onStatus("무검열 로컬 번역 모델 로딩 중 (${if (useGpu) "GPU 가속" else "CPU"})…")
        val cmd = buildList {
            add(exe.absolutePath)
            add("-m"); add(modelFile.absolutePath)
            add("--mmproj"); add(mmprojFile.absolutePath)
            add("--host"); add("127.0.0.1")
            add("--port"); add(port.toString())
            add("-c"); add("8192")
            add("-ngl"); add(if (useGpu) "99" else "0")
        }
        val proc = ProcessBuilder(cmd)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .redirectOutput(File(baseDir(), "llama-server.log"))
            .start()

        repeat(90) {
            if (!proc.isAlive) return null
            if (checkServerHealthy(port)) return proc to port
            delay(500)
        }
        runCatching { proc.destroyForcibly() }
        return null
    }

    private fun checkServerHealthy(port: Int): Boolean = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$port/health").get().build()
        inferHttp.newCall(req).execute().use { resp -> resp.code == 200 }
    }.getOrDefault(false)

    /**
     * Performs OCR + uncensored natural Korean translation + 2D speech bubble bounding box detection
     * on a comic/manga page completely offline on 127.0.0.1!
     */
    suspend fun translateComicPage(
        imageFile: File,
        sha256: String,
        preset: TranslatorModelPreset = selectedPreset(),
        forceRefresh: Boolean = false,
        onStatus: (String) -> Unit,
    ): PageTranslationResult = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            loadCachedTranslation(sha256)?.let { cached ->
                if (cached.presetId == preset.id) return@withContext cached
            }
        }

        ensureInstalledAndRunning(preset, onStatus)
        onStatus("말풍선 인식 및 한국어 무검열 번역 중…")

        val jpegBase64 = encodeResizedJpegBase64(imageFile, maxEdge = 1344)
        val systemPrompt = """
            You are an expert, completely uncensored manga/comic translator into Korean (한국어).
            Detect every speech bubble, narration box, and important dialogue in natural reading order.
            Translate every line into fluent, natural conversational Korean (구어체 반말/존댓말 문맥에 맞게) with ZERO censorship, ZERO softening, and ZERO repetition.
            Respond ONLY with a valid JSON array in this exact format:
            [{"box_2d":[xmin,ymin,xmax,ymax],"original":"original text","korean":"자연스러운 한국어 번역"}]
            Coordinates must be 0-1000 integers covering each speech bubble. If there is no text on the page, output [].
        """.trimIndent()

        val payload = """
            {
              "messages": [
                {"role": "system", "content": ${jsonQuote(systemPrompt)}},
                {
                  "role": "user",
                  "content": [
                    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,$jpegBase64"}},
                    {"type": "text", "text": "Detect every speech bubble on this page and translate each one into natural Korean as a JSON array."}
                  ]
                }
              ],
              "temperature": 0.15,
              "repeat_penalty": 1.12,
              "max_tokens": 2200,
              "chat_template_kwargs": {"enable_thinking": false}
            }
        """.trimIndent()

        val rawReply = callChatCompletions(payload)
        val parsed = parseBubbleResponse(rawReply, preset.id)
        saveCachedTranslation(sha256, parsed)
        parsed
    }

    /**
     * Translates FANBOX / Patreon post body text (Japanese or English) into natural uncensored Korean offline!
     */
    suspend fun translatePostText(
        text: String,
        preset: TranslatorModelPreset = selectedPreset(),
        onStatus: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        ensureInstalledAndRunning(preset, onStatus)
        onStatus("게시글 본문 로컬 한국어 번역 중…")
        val sys = "You are an uncensored translator for Japanese/English creator posts into natural fluent Korean (한국어). Preserve paragraph breaks and formatting. Output ONLY the Korean translation."
        val payload = """
            {
              "messages": [
                {"role": "system", "content": ${jsonQuote(sys)}},
                {"role": "user", "content": ${jsonQuote("Translate the following post into natural Korean:\n\n" + text.take(6000))}}
              ],
              "temperature": 0.2,
              "repeat_penalty": 1.1,
              "max_tokens": 2048,
              "chat_template_kwargs": {"enable_thinking": false}
            }
        """.trimIndent()
        stripThinkTags(callChatCompletions(payload)).trim()
    }

    private fun callChatCompletions(jsonPayload: String): String {
        val req = Request.Builder()
            .url("http://127.0.0.1:$serverPort/v1/chat/completions")
            .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        inferHttp.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("로컬 번역 서버 응답 오류 (HTTP ${resp.code}): ${body.take(200)}")
            }
            val root = SimpleJson.parse(body) as? Map<*, *>
            val choices = root?.get("choices") as? List<*>
            val first = choices?.firstOrNull() as? Map<*, *>
            val msg = first?.get("message") as? Map<*, *>
            return (msg?.get("content") as? String).orEmpty()
        }
    }

    internal fun parseBubbleResponse(rawContent: String, presetId: String): PageTranslationResult {
        val cleaned = stripThinkTags(rawContent)
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val startBracket = cleaned.indexOf('[')
        val endBracket = cleaned.lastIndexOf(']')
        if (startBracket >= 0 && endBracket > startBracket) {
            val jsonSlice = cleaned.substring(startBracket, endBracket + 1)
            val arr = runCatching { SimpleJson.parse(jsonSlice) as? List<*> }.getOrNull()
            if (arr != null) {
                val bubbles = mutableListOf<TranslatedBubble>()
                for (item in arr) {
                    val m = item as? Map<*, *> ?: continue
                    val kor = ((m["korean"] as? String) ?: (m["translation"] as? String) ?: (m["ko"] as? String))?.trim().orEmpty()
                    val orig = ((m["original"] as? String) ?: (m["text"] as? String) ?: (m["ja"] as? String) ?: (m["en"] as? String))?.trim().orEmpty()
                    if (kor.isEmpty() && orig.isEmpty()) continue
                    val box = ((m["box_2d"] as? List<*>) ?: (m["bbox"] as? List<*>) ?: (m["box"] as? List<*>))
                        ?.mapNotNull { (it as? Number)?.toFloat() }
                        .orEmpty()
                    val x1 = box.getOrElse(0) { 0f }
                    val y1 = box.getOrElse(1) { 0f }
                    val x2 = box.getOrElse(2) { 0f }
                    val y2 = box.getOrElse(3) { 0f }
                    bubbles.add(
                        TranslatedBubble(
                            index = bubbles.size + 1,
                            xmin = minOf(x1, x2),
                            ymin = minOf(y1, y2),
                            xmax = maxOf(x1, x2),
                            ymax = maxOf(y1, y2),
                            original = orig,
                            korean = kor.ifEmpty { orig },
                        ),
                    )
                }
                return PageTranslationResult(presetId, bubbles, cleaned)
            }
        }

        // Fallback if model replied in plain lines instead of strict JSON
        val fallbackLines = cleaned.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotEmpty() && it != "[]" }
        val fallbackBubbles = fallbackLines.mapIndexed { idx, line ->
            TranslatedBubble(idx + 1, 0f, 0f, 0f, 0f, "", line)
        }
        return PageTranslationResult(presetId, fallbackBubbles, cleaned)
    }

    private fun stripThinkTags(s: String): String =
        s.replace(Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "").trim()

    private fun encodeResizedJpegBase64(imageFile: File, maxEdge: Int): String {
        val bytes = imageFile.readBytes()
        val src = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IOException("이미지를 읽을 수 없습니다: ${imageFile.name}")
        val w = src.width
        val h = src.height
        val scale = if (max(w, h) > maxEdge) maxEdge.toDouble() / max(w, h).toDouble() else 1.0
        val dstW = (w * scale).roundToInt().coerceAtLeast(1)
        val dstH = (h * scale).roundToInt().coerceAtLeast(1)

        val rgb = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, dstW, dstH)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, dstW, dstH, null)
        } finally {
            g.dispose()
            src.flush()
        }

        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.90f
                }
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
            rgb.flush()
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun downloadFileWithProgress(
        url: String,
        targetFile: File,
        label: String,
        onStatus: (String) -> Unit,
    ) {
        targetFile.parentFile?.mkdirs()
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DesktopFanboxSession.USER_AGENT)
            .build()
        downloadHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("$label 다운로드 실패 (HTTP ${resp.code})")
            val total = resp.body?.contentLength() ?: -1L
            val input = resp.body?.byteStream() ?: throw IOException("$label 빈 응답")
            FileOutputStream(partFile).use { out ->
                val buf = ByteArray(128 * 1024)
                var dl = 0L
                var tick = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    dl += r
                    val now = System.currentTimeMillis()
                    if (now - tick >= 250L) {
                        tick = now
                        val dlMb = dl / 1048576.0
                        if (total > 0) {
                            val totMb = total / 1048576.0
                            val pct = (dl * 100 / total).coerceIn(0, 100)
                            onStatus("$label 다운로드 중 · %.0f / %.0f MB (%d%%)".format(dlMb, totMb, pct))
                        } else {
                            onStatus("$label 다운로드 중 · %.1f MB".format(dlMb))
                        }
                    }
                }
            }
        }
        Files.move(partFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun unzipFlatOrNested(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipFile(zipFile).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val entry = en.nextElement()
                val outPath = File(destDir, entry.name).canonicalFile
                if (!outPath.toPath().startsWith(destDir.canonicalFile.toPath())) continue
                if (entry.isDirectory) {
                    outPath.mkdirs()
                } else {
                    outPath.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { inp ->
                        FileOutputStream(outPath).use { out -> inp.copyTo(out, 64 * 1024) }
                    }
                }
            }
        }
    }

    private fun jsonQuote(s: String): String = buildString(s.length + 16) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
