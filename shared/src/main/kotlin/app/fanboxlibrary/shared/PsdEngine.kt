package app.fanboxlibrary.shared

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin Photoshop PSD / PSB reader and layer compositor.
 * - Parses metadata and channel file offsets lazily (low RAM even for 500 MB PSDs).
 * - Supports 8-bit / 16-bit RGB, Grayscale, CMYK; Raw, RLE (PackBits), ZIP, ZIP-predicted compression.
 * - Supports hierarchical folders (lsct/lsdk), UTF-16BE names (luni), text layer detection (TySh),
 *   clipping masks, layer/group opacity, and common Photoshop blend modes.
 */
data class PsdChannelMeta(
    val id: Int, // 0=R, 1=G, 2=B, -1=A, -2=UserMask, -3=RealUserMask
    val dataLength: Long,
    val fileOffset: Long,
)

data class PsdLayer(
    val index: Int,
    val name: String,
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val blendMode: String,
    val opacity: Int, // 0..255
    val clipping: Boolean,
    val defaultVisible: Boolean,
    val isGroupStart: Boolean, // folder header (lsct 1 or 2)
    val isGroupEnd: Boolean,   // bounding divider (lsct 3)
    val isTextLayer: Boolean,
    val depth: Int,            // indentation depth in top-to-bottom UI order
    val parentGroupIndex: Int?, // index of enclosing folder layer (if any)
    val channels: List<PsdChannelMeta>,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isPixelLayer: Boolean get() = !isGroupStart && !isGroupEnd && width > 0 && height > 0
}

data class PsdImageBuffer(
    val width: Int,
    val height: Int,
    val argb: IntArray, // 0xAARRGGBB packed pixels
)

data class PsdDocument(
    val file: File,
    val isPsb: Boolean,
    val width: Int,
    val height: Int,
    val depth: Int,
    val colorMode: Int, // 1=Grayscale, 3=RGB, 4=CMYK
    val layers: List<PsdLayer>, // stored in Photoshop bottom-to-top compositing order
    val mergedImageOffset: Long,
) {
    /** UI-friendly top-to-bottom visible list excluding invisible section-end markers. */
    val uiLayers: List<PsdLayer>
        get() = layers.asReversed().filterNot { it.isGroupEnd }

    fun defaultVisibilityMap(): Map<Int, Boolean> =
        layers.associate { it.index to it.defaultVisible }

    fun defaultOpacityMap(): Map<Int, Float> =
        layers.associate { it.index to (it.opacity / 255f) }

    fun isEffectivelyVisible(layer: PsdLayer, visibility: Map<Int, Boolean>): Boolean {
        if (visibility[layer.index] == false) return false
        var parentIdx = layer.parentGroupIndex
        while (parentIdx != null) {
            if (visibility[parentIdx] == false) return false
            parentIdx = layers.getOrNull(parentIdx)?.parentGroupIndex
        }
        return true
    }

    fun effectiveOpacity(layer: PsdLayer, opacities: Map<Int, Float>): Float {
        var acc = (opacities[layer.index] ?: (layer.opacity / 255f)).coerceIn(0f, 1f)
        var parentIdx = layer.parentGroupIndex
        while (parentIdx != null) {
            val parent = layers.getOrNull(parentIdx) ?: break
            acc *= (opacities[parent.index] ?: (parent.opacity / 255f)).coerceIn(0f, 1f)
            parentIdx = parent.parentGroupIndex
        }
        return acc
    }
}

object PsdPresets {
    private val textPattern = Regex(
        """(?i)(text|텍스트|セリフ|せりふ|台詞|文字|ふきだし|吹き出し|フキダシ|말풍선|대사|효과음|擬音|オノマトペ|sfx|dialogue|speech|balloon)""",
    )
    private val censorPattern = Regex(
        """(?i)(mosaic|모자이크|モザイク|censor|검열|修正|しゅうせい|黒塗り|白塗り|海苔|のり|ボカシ|ぼかし|blur|光線|白光|白線)""",
    )

    fun isLikelyTextLayer(layer: PsdLayer): Boolean =
        layer.isTextLayer || textPattern.containsMatchIn(layer.name)

    fun isLikelyCensorLayer(layer: PsdLayer): Boolean =
        censorPattern.containsMatchIn(layer.name)

    fun applyHideText(doc: PsdDocument, current: Map<Int, Boolean>): Map<Int, Boolean> {
        val next = current.toMutableMap()
        doc.layers.forEach { layer ->
            if (!layer.isGroupEnd && isLikelyTextLayer(layer)) {
                next[layer.index] = false
            }
        }
        return next
    }

    fun applyHideCensor(doc: PsdDocument, current: Map<Int, Boolean>): Map<Int, Boolean> {
        val next = current.toMutableMap()
        doc.layers.forEach { layer ->
            if (!layer.isGroupEnd && isLikelyCensorLayer(layer)) {
                next[layer.index] = false
            }
        }
        return next
    }
}

object PsdEngine {
    private val shiftJis: Charset = runCatching { Charset.forName("MS932") }.getOrDefault(StandardCharsets.UTF_8)

    fun open(file: File): PsdDocument {
        if (!file.isFile) throw IOException("PSD 파일을 찾을 수 없습니다.")
        RandomAccessFile(file, "r").use { raf ->
            val sig = readAscii(raf, 4)
            if (sig != "8BPS") throw IOException("올바른 PSD/PSB 파일이 아닙니다.")
            val version = raf.readUnsignedShort()
            if (version != 1 && version != 2) throw IOException("지원하지 않는 PSD 버전입니다 ($version).")
            val isPsb = version == 2
            raf.skipBytes(6) // reserved
            val channels = raf.readUnsignedShort()
            val height = raf.readInt()
            val width = raf.readInt()
            val depth = raf.readUnsignedShort()
            val colorMode = raf.readUnsignedShort()
            if (width <= 0 || height <= 0) throw IOException("PSD 캔버스 크기가 올바르지 않습니다.")
            if (depth != 8 && depth != 16) throw IOException("지원하지 않는 비트 심도입니다 (${depth}bit). 8/16bit PSD만 지원합니다.")

            // Section 2: Color Mode Data
            val colorModeLen = raf.readUnsignedInt()
            skipFully(raf, colorModeLen)

            // Section 3: Image Resources
            val resourcesLen = raf.readUnsignedInt()
            skipFully(raf, resourcesLen)

            // Section 4: Layer and Mask Information
            val layerMaskLen = readLength(raf, isPsb)
            val layerMaskEnd = raf.filePointer + layerMaskLen
            val rawLayers = mutableListOf<RawLayerRecord>()

            if (layerMaskLen > 0) {
                val layerInfoLen = readLength(raf, isPsb)
                if (layerInfoLen > 0) {
                    val layerInfoEnd = raf.filePointer + layerInfoLen
                    val rawCount = raf.readShort().toInt()
                    val count = abs(rawCount)

                    for (i in 0 until count) {
                        val top = raf.readInt()
                        val left = raf.readInt()
                        val bottom = raf.readInt()
                        val right = raf.readInt()
                        val chCount = raf.readUnsignedShort()
                        val chInfos = ArrayList<Pair<Int, Long>>(chCount)
                        for (c in 0 until chCount) {
                            val chId = raf.readShort().toInt()
                            val chLen = readLength(raf, isPsb)
                            chInfos.add(chId to chLen)
                        }
                        val blendSig = readAscii(raf, 4)
                        if (blendSig != "8BIM") throw IOException("레이어 블렌드 시그니처 오류: $blendSig")
                        val blendKey = readAscii(raf, 4)
                        val opacity = raf.readUnsignedByte()
                        val clipping = raf.readUnsignedByte() != 0
                        val flags = raf.readUnsignedByte()
                        val visible = (flags and 0x02) == 0
                        raf.readUnsignedByte() // filler

                        val extraLen = raf.readUnsignedInt()
                        val extraEnd = raf.filePointer + extraLen

                        // Layer mask data
                        val maskLen = raf.readUnsignedInt()
                        skipFully(raf, maskLen)

                        // Blending ranges
                        val rangesLen = raf.readUnsignedInt()
                        skipFully(raf, rangesLen)

                        // Pascal layer name (padded to multiple of 4 bytes)
                        val nameLen = raf.readUnsignedByte()
                        val nameBytes = ByteArray(nameLen)
                        raf.readFully(nameBytes)
                        val paddedNameTotal = ((nameLen + 1) + 3) and 3.inv()
                        val padBytes = paddedNameTotal - (nameLen + 1)
                        if (padBytes > 0) skipFully(raf, padBytes.toLong())
                        var layerName = decodeLayerName(nameBytes)

                        var sectionType = 0
                        var isText = false

                        // Additional Layer Information blocks
                        while (raf.filePointer + 12 <= extraEnd) {
                            val addSig = readAscii(raf, 4)
                            if (addSig != "8BIM" && addSig != "8B64") break
                            val key = readAscii(raf, 4)
                            val useLongLen = isPsb && key in PSB_64BIT_KEYS
                            val blockLen = if (useLongLen) raf.readLong() else raf.readUnsignedInt()
                            val blockStart = raf.filePointer
                            val blockEnd = blockStart + blockLen
                            if (blockEnd > extraEnd) break

                            when (key) {
                                "luni" -> {
                                    if (blockLen >= 4) {
                                        val charCount = raf.readInt()
                                        if (charCount in 0..4096 && 4L + charCount * 2L <= blockLen) {
                                            val utf16 = ByteArray(charCount * 2)
                                            raf.readFully(utf16)
                                            val decoded = String(utf16, StandardCharsets.UTF_16BE).trimEnd('\u0000')
                                            if (decoded.isNotEmpty()) layerName = decoded
                                        }
                                    }
                                }
                                "lsct", "lsdk" -> {
                                    if (blockLen >= 4) {
                                        sectionType = raf.readInt()
                                    }
                                }
                                "TySh", "tySh" -> {
                                    isText = true
                                }
                            }
                            // Blocks in Additional Layer Info are padded to even (or 4) bytes depending on writer;
                            // seeking to exact blockEnd (plus 1 if odd) is standard for PSD.
                            val alignedEnd = if (blockLen % 2L == 1L && blockEnd + 1 <= extraEnd) blockEnd + 1 else blockEnd
                            raf.seek(alignedEnd)
                            if (raf.filePointer + 4 <= extraEnd) {
                                val nextSig = readAscii(raf, 4)
                                if (nextSig != "8BIM" && nextSig != "8B64") {
                                    val aligned4 = blockStart + ((blockLen + 3L) and 3L.inv())
                                    if (aligned4 <= extraEnd) raf.seek(aligned4) else raf.seek(alignedEnd)
                                } else {
                                    raf.seek(alignedEnd)
                                }
                            }
                        }
                        raf.seek(extraEnd)

                        rawLayers.add(
                            RawLayerRecord(
                                index = i,
                                name = layerName.ifBlank { "레이어 ${i + 1}" },
                                top = top,
                                left = left,
                                bottom = bottom,
                                right = right,
                                blendMode = blendKey,
                                opacity = opacity,
                                clipping = clipping,
                                visible = visible,
                                isGroupStart = sectionType == 1 || sectionType == 2,
                                isGroupEnd = sectionType == 3,
                                isTextLayer = isText,
                                channelPairs = chInfos,
                            ),
                        )
                    }

                    // Record channel data file offsets in exact layer/channel order
                    for (rec in rawLayers) {
                        val metas = ArrayList<PsdChannelMeta>(rec.channelPairs.size)
                        for ((chId, chLen) in rec.channelPairs) {
                            val offset = raf.filePointer
                            metas.add(PsdChannelMeta(chId, chLen, offset))
                            skipFully(raf, chLen)
                        }
                        rec.channels = metas
                    }

                    if (raf.filePointer < layerInfoEnd) raf.seek(layerInfoEnd)
                }
                raf.seek(layerMaskEnd)
            }

            val mergedImageOffset = raf.filePointer
            val layers = buildHierarchy(rawLayers)

            return PsdDocument(
                file = file,
                isPsb = isPsb,
                width = width,
                height = height,
                depth = depth,
                colorMode = colorMode,
                layers = layers,
                mergedImageOffset = mergedImageOffset,
            )
        }
    }

    /**
     * Composites visible layers into an ARGB pixel buffer scaled to fit [maxDimension].
     * Pass [maxDimension] = Int.MAX_VALUE for full-resolution export.
     */
    fun render(
        doc: PsdDocument,
        visibility: Map<Int, Boolean> = doc.defaultVisibilityMap(),
        opacities: Map<Int, Float> = doc.defaultOpacityMap(),
        maxDimension: Int = 1280,
    ): PsdImageBuffer {
        val longest = max(doc.width, doc.height).coerceAtLeast(1)
        val scale = if (longest > maxDimension) maxDimension.toDouble() / longest.toDouble() else 1.0
        val outW = max(1, (doc.width * scale).roundToInt())
        val outH = max(1, (doc.height * scale).roundToInt())
        val canvas = IntArray(outW * outH) // transparent initially (0x00000000)

        if (doc.layers.isEmpty()) {
            // Fallback: flat PSD with no layer records -> decode merged image section
            return renderMergedFallback(doc, outW, outH)
        }

        RandomAccessFile(doc.file, "r").use { raf ->
            // Track active clipping base alpha mask in output canvas coordinates
            var baseAlpha: ByteArray? = null
            var baseVisible = true

            for (layer in doc.layers) {
                if (layer.isGroupStart || layer.isGroupEnd) {
                    baseAlpha = null
                    baseVisible = true
                    continue
                }

                val isVis = doc.isEffectivelyVisible(layer, visibility)
                val effOpacity = doc.effectiveOpacity(layer, opacities)

                if (!layer.clipping) {
                    baseAlpha = null
                    baseVisible = isVis && effOpacity > 0.001f
                }

                if (!isVis || effOpacity <= 0.001f || !layer.isPixelLayer) continue
                if (layer.clipping && !baseVisible) continue

                val decoded = decodeLayerScaled(
                    raf = raf,
                    doc = doc,
                    layer = layer,
                    scale = scale,
                    outW = outW,
                    outH = outH,
                ) ?: continue

                if (!layer.clipping) {
                    // Check if the next pixel layer is clipped to this one; only allocate baseAlpha when needed
                    val nextLayer = doc.layers.getOrNull(layer.index + 1)
                    if (nextLayer != null && nextLayer.clipping) {
                        val mask = ByteArray(outW * outH)
                        for (ly in 0 until decoded.h) {
                            val cy = decoded.dstY + ly
                            if (cy !in 0 until outH) continue
                            val rowDst = cy * outW
                            val rowSrc = ly * decoded.w
                            for (lx in 0 until decoded.w) {
                                val cx = decoded.dstX + lx
                                if (cx !in 0 until outW) continue
                                val a = (decoded.pixels[rowSrc + lx] ushr 24) and 0xFF
                                val effA = (a * effOpacity).roundToInt().coerceIn(0, 255)
                                mask[rowDst + cx] = effA.toByte()
                            }
                        }
                        baseAlpha = mask
                    }
                }

                blendLayerIntoCanvas(
                    canvas = canvas,
                    outW = outW,
                    outH = outH,
                    decoded = decoded,
                    blendMode = layer.blendMode,
                    opacity = effOpacity,
                    clipMask = if (layer.clipping) baseAlpha else null,
                )
            }
        }

        // Fill remaining transparent background with white only if fully opaque export?
        // Actually keeping alpha allows transparent PNGs; for preview UI, caller draws checkerboard or white behind it.
        return PsdImageBuffer(outW, outH, canvas)
    }

    private data class DecodedLayerPatch(
        val dstX: Int,
        val dstY: Int,
        val w: Int,
        val h: Int,
        val pixels: IntArray,
    )

    private fun decodeLayerScaled(
        raf: RandomAccessFile,
        doc: PsdDocument,
        layer: PsdLayer,
        scale: Double,
        outW: Int,
        outH: Int,
    ): DecodedLayerPatch? {
        val srcW = layer.width
        val srcH = layer.height
        if (srcW <= 0 || srcH <= 0) return null

        val dstX0 = (layer.left * scale).roundToInt()
        val dstY0 = (layer.top * scale).roundToInt()
        val dstX1 = (layer.right * scale).roundToInt()
        val dstY1 = (layer.bottom * scale).roundToInt()

        val visX0 = dstX0.coerceIn(0, outW)
        val visY0 = dstY0.coerceIn(0, outH)
        val visX1 = dstX1.coerceIn(0, outW)
        val visY1 = dstY1.coerceIn(0, outH)
        val patchW = visX1 - visX0
        val patchH = visY1 - visY0
        if (patchW <= 0 || patchH <= 0) return null

        val chMap = layer.channels.associateBy { it.id }
        val rPlane = chMap[0]?.let { decodeChannel(raf, doc, it, srcW, srcH) }
        val gPlane = chMap[1]?.let { decodeChannel(raf, doc, it, srcW, srcH) }
        val bPlane = chMap[2]?.let { decodeChannel(raf, doc, it, srcW, srcH) }
        val kPlane = if (doc.colorMode == 4) chMap[3]?.let { decodeChannel(raf, doc, it, srcW, srcH) } else null
        val aPlane = chMap[-1]?.let { decodeChannel(raf, doc, it, srcW, srcH) }

        if (rPlane == null && gPlane == null && bPlane == null && aPlane == null) return null

        val invScale = 1.0 / scale
        val pixels = IntArray(patchW * patchH)
        for (py in 0 until patchH) {
            val srcY = (((visY0 + py) * invScale) - layer.top).toInt().coerceIn(0, srcH - 1)
            val srcRow = srcY * srcW
            val dstRow = py * patchW
            for (px in 0 until patchW) {
                val srcX = (((visX0 + px) * invScale) - layer.left).toInt().coerceIn(0, srcW - 1)
                val sIdx = srcRow + srcX
                val a = aPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 255
                if (a == 0) {
                    pixels[dstRow + px] = 0
                    continue
                }
                val r: Int
                val g: Int
                val b: Int
                when (doc.colorMode) {
                    1 -> { // Grayscale
                        val gray = rPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 0
                        r = gray; g = gray; b = gray
                    }
                    4 -> { // CMYK (Photoshop stores inverted 0=100% ink, 255=0% ink)
                        val c = rPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 255
                        val m = gPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 255
                        val y = bPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 255
                        val k = kPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 255
                        r = (c * k) / 255
                        g = (m * k) / 255
                        b = (y * k) / 255
                    }
                    else -> { // RGB
                        r = rPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 0
                        g = gPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 0
                        b = bPlane?.get(sIdx)?.toInt()?.and(0xFF) ?: 0
                    }
                }
                pixels[dstRow + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return DecodedLayerPatch(visX0, visY0, patchW, patchH, pixels)
    }

    private fun decodeChannel(
        raf: RandomAccessFile,
        doc: PsdDocument,
        meta: PsdChannelMeta,
        width: Int,
        height: Int,
    ): ByteArray? {
        if (meta.dataLength < 2) return null
        val bytesPerSample = doc.depth / 8
        val rowByteWidth = width * bytesPerSample
        val rawTotal = rowByteWidth * height

        raf.seek(meta.fileOffset)
        val compression = raf.readUnsignedShort()
        val payloadLen = (meta.dataLength - 2).toInt().coerceAtLeast(0)

        val decodedRaw: ByteArray = when (compression) {
            0 -> { // Raw
                val out = ByteArray(rawTotal)
                val toRead = min(rawTotal, payloadLen)
                raf.readFully(out, 0, toRead)
                out
            }
            1 -> { // RLE (PackBits)
                val rowCounts = IntArray(height)
                if (doc.isPsb) {
                    val hdr = ByteArray(height * 4)
                    raf.readFully(hdr)
                    val bb = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
                    for (y in 0 until height) rowCounts[y] = bb.int
                } else {
                    val hdr = ByteArray(height * 2)
                    raf.readFully(hdr)
                    val bb = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
                    for (y in 0 until height) rowCounts[y] = bb.short.toInt() and 0xFFFF
                }
                val out = ByteArray(rawTotal)
                var maxRowLen = 0
                for (c in rowCounts) if (c > maxRowLen) maxRowLen = c
                val rleBuf = ByteArray(maxRowLen)
                for (y in 0 until height) {
                    val rLen = rowCounts[y]
                    if (rLen <= 0) continue
                    raf.readFully(rleBuf, 0, rLen)
                    decodePackBitsRow(rleBuf, rLen, out, y * rowByteWidth, rowByteWidth)
                }
                out
            }
            2, 3 -> { // ZIP without / with prediction
                val compressed = ByteArray(payloadLen)
                raf.readFully(compressed)
                val out = inflateBytes(compressed, rawTotal)
                if (compression == 3) {
                    undoHorizontalPrediction(out, width, height, bytesPerSample)
                }
                out
            }
            else -> return null
        }

        if (bytesPerSample == 1) return decodedRaw
        // Downsample 16-bit big-endian samples to 8-bit by taking MSB
        val eightBit = ByteArray(width * height)
        for (i in eightBit.indices) {
            eightBit[i] = decodedRaw[i * 2]
        }
        return eightBit
    }

    private fun decodePackBitsRow(src: ByteArray, srcLen: Int, dst: ByteArray, dstOffset: Int, rowLen: Int) {
        var si = 0
        var di = 0
        while (si < srcLen && di < rowLen) {
            val header = src[si++].toInt() // signed byte -128..127
            when {
                header in 0..127 -> {
                    val count = min(header + 1, min(srcLen - si, rowLen - di))
                    System.arraycopy(src, si, dst, dstOffset + di, count)
                    si += count
                    di += count
                }
                header in -127..-1 -> {
                    if (si >= srcLen) break
                    val value = src[si++]
                    val count = min(1 - header, rowLen - di)
                    dst.fill(value, dstOffset + di, dstOffset + di + count)
                    di += count
                }
                // -128 is a no-op in PackBits
            }
        }
    }

    private fun inflateBytes(input: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArray(expectedSize)
            var offset = 0
            while (!inflater.finished() && offset < expectedSize) {
                val n = inflater.inflate(out, offset, expectedSize - offset)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                offset += n
            }
            out
        } finally {
            inflater.end()
        }
    }

    private fun undoHorizontalPrediction(buf: ByteArray, width: Int, height: Int, bytesPerSample: Int) {
        if (bytesPerSample == 1) {
            for (y in 0 until height) {
                val row = y * width
                for (x in 1 until width) {
                    buf[row + x] = (buf[row + x] + buf[row + x - 1]).toByte()
                }
            }
        } else {
            val rowBytes = width * 2
            for (y in 0 until height) {
                val row = y * rowBytes
                for (x in 1 until width) {
                    val prev = ((buf[row + (x - 1) * 2].toInt() and 0xFF) shl 8) or (buf[row + (x - 1) * 2 + 1].toInt() and 0xFF)
                    val cur = ((buf[row + x * 2].toInt() and 0xFF) shl 8) or (buf[row + x * 2 + 1].toInt() and 0xFF)
                    val sum = (prev + cur) and 0xFFFF
                    buf[row + x * 2] = (sum ushr 8).toByte()
                    buf[row + x * 2 + 1] = (sum and 0xFF).toByte()
                }
            }
        }
    }

    private fun blendLayerIntoCanvas(
        canvas: IntArray,
        outW: Int,
        outH: Int,
        decoded: DecodedLayerPatch,
        blendMode: String,
        opacity: Float,
        clipMask: ByteArray?,
    ) {
        for (ly in 0 until decoded.h) {
            val cy = decoded.dstY + ly
            if (cy !in 0 until outH) continue
            val cRow = cy * outW
            val pRow = ly * decoded.w
            for (lx in 0 until decoded.w) {
                val cx = decoded.dstX + lx
                if (cx !in 0 until outW) continue
                val src = decoded.pixels[pRow + lx]
                val rawSa = (src ushr 24) and 0xFF
                if (rawSa == 0) continue

                var sa = (rawSa / 255f) * opacity
                if (clipMask != null) {
                    val maskA = (clipMask[cRow + cx].toInt() and 0xFF) / 255f
                    if (maskA <= 0f) continue
                    sa *= maskA
                }
                if (sa <= 0.0005f) continue

                val sr = ((src ushr 16) and 0xFF) / 255f
                val sg = ((src ushr 8) and 0xFF) / 255f
                val sb = (src and 0xFF) / 255f

                val dst = canvas[cRow + cx]
                val da = ((dst ushr 24) and 0xFF) / 255f

                if (da <= 0.0005f) {
                    val outA = (sa * 255f).roundToInt().coerceIn(0, 255)
                    val outR = (sr * 255f).roundToInt().coerceIn(0, 255)
                    val outG = (sg * 255f).roundToInt().coerceIn(0, 255)
                    val outB = (sb * 255f).roundToInt().coerceIn(0, 255)
                    canvas[cRow + cx] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
                    continue
                }

                val dr = ((dst ushr 16) and 0xFF) / 255f
                val dg = ((dst ushr 8) and 0xFF) / 255f
                val db = (dst and 0xFF) / 255f

                val br = blendChannel(dr, sr, blendMode)
                val bg = blendChannel(dg, sg, blendMode)
                val bb = blendChannel(db, sb, blendMode)

                // Standard Porter-Duff SrcOver with blended overlap color
                val outA = sa + da * (1f - sa)
                if (outA <= 0.0005f) {
                    canvas[cRow + cx] = 0
                    continue
                }
                val invOutA = 1f / outA
                val r = ((1f - da) * sa * sr + (1f - sa) * da * dr + sa * da * br) * invOutA
                val g = ((1f - da) * sa * sg + (1f - sa) * da * dg + sa * da * bg) * invOutA
                val b = ((1f - da) * sa * sb + (1f - sa) * da * db + sa * da * bb) * invOutA

                canvas[cRow + cx] =
                    ((outA * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                    ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    (b * 255f).roundToInt().coerceIn(0, 255)
            }
        }
    }

    private fun blendChannel(d: Float, s: Float, mode: String): Float = when (mode) {
        "mul " -> d * s
        "scrn" -> 1f - (1f - d) * (1f - s)
        "over" -> if (d < 0.5f) 2f * d * s else 1f - 2f * (1f - d) * (1f - s)
        "dark" -> min(d, s)
        "lite" -> max(d, s)
        "lddg" -> (d + s).coerceAtMost(1f) // Linear Dodge (Add)
        "lbrn" -> (d + s - 1f).coerceAtLeast(0f) // Linear Burn
        "div " -> if (s >= 0.999f) 1f else (d / (1f - s)).coerceAtMost(1f) // Color Dodge
        "idiv" -> if (s <= 0.001f) 0f else (1f - (1f - d) / s).coerceAtLeast(0f) // Color Burn
        "hLit" -> if (s < 0.5f) 2f * d * s else 1f - 2f * (1f - d) * (1f - s)
        "sLit" -> (1f - 2f * s) * d * d + 2f * s * d
        "diff" -> abs(d - s)
        else -> s // Normal
    }

    private fun renderMergedFallback(doc: PsdDocument, outW: Int, outH: Int): PsdImageBuffer {
        val pixels = IntArray(outW * outH)
        RandomAccessFile(doc.file, "r").use { raf ->
            if (doc.mergedImageOffset + 2 >= raf.length()) return PsdImageBuffer(outW, outH, pixels)
            raf.seek(doc.mergedImageOffset)
            val compression = raf.readUnsignedShort()
            val numCh = if (doc.colorMode == 1) 1 else 3
            val bytesPerSample = doc.depth / 8
            val rowBytes = doc.width * bytesPerSample
            val planes = Array(numCh) { ByteArray(doc.width * doc.height) }

            when (compression) {
                0 -> {
                    val tmp = ByteArray(rowBytes * doc.height)
                    for (c in 0 until numCh) {
                        raf.readFully(tmp)
                        if (bytesPerSample == 1) System.arraycopy(tmp, 0, planes[c], 0, planes[c].size)
                        else for (i in planes[c].indices) planes[c][i] = tmp[i * 2]
                    }
                }
                1 -> {
                    val totalRows = numCh * doc.height
                    val counts = IntArray(totalRows)
                    for (i in 0 until totalRows) {
                        counts[i] = if (doc.isPsb) raf.readInt() else raf.readUnsignedShort()
                    }
                    val rowOut = ByteArray(rowBytes)
                    for (c in 0 until numCh) {
                        for (y in 0 until doc.height) {
                            val rLen = counts[c * doc.height + y]
                            val rle = ByteArray(rLen)
                            raf.readFully(rle)
                            decodePackBitsRow(rle, rLen, rowOut, 0, rowBytes)
                            val dstOff = y * doc.width
                            if (bytesPerSample == 1) System.arraycopy(rowOut, 0, planes[c], dstOff, doc.width)
                            else for (x in 0 until doc.width) planes[c][dstOff + x] = rowOut[x * 2]
                        }
                    }
                }
            }

            for (y in 0 until outH) {
                val sy = (y * doc.height / outH).coerceIn(0, doc.height - 1)
                val sRow = sy * doc.width
                val dRow = y * outW
                for (x in 0 until outW) {
                    val sx = (x * doc.width / outW).coerceIn(0, doc.width - 1)
                    val idx = sRow + sx
                    val r = planes[0][idx].toInt() and 0xFF
                    val g = if (numCh >= 2) planes[1][idx].toInt() and 0xFF else r
                    val b = if (numCh >= 3) planes[2][idx].toInt() and 0xFF else r
                    pixels[dRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return PsdImageBuffer(outW, outH, pixels)
    }

    /**
     * Reconstructs group depth and parent group indices.
     * In Photoshop's bottom-to-top array, a folder appears as:
     *   [groupEnd (lsct=3)] -> [child 1] -> [child 2] -> [groupStart (lsct=1/2)]
     * So walking top-to-bottom (reversed), we see groupStart first, then its children, then groupEnd.
     */
    private fun buildHierarchy(raw: List<RawLayerRecord>): List<PsdLayer> {
        val parents = IntArray(raw.size) { -1 }
        val depths = IntArray(raw.size) { 0 }
        val stack = ArrayDeque<Int>()
        for (i in raw.indices.reversed()) {
            val rec = raw[i]
            when {
                rec.isGroupStart -> {
                    parents[i] = stack.lastOrNull() ?: -1
                    depths[i] = stack.size
                    stack.addLast(i)
                }
                rec.isGroupEnd -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    parents[i] = stack.lastOrNull() ?: -1
                    depths[i] = stack.size
                }
                else -> {
                    parents[i] = stack.lastOrNull() ?: -1
                    depths[i] = stack.size
                }
            }
        }
        return raw.mapIndexed { idx, rec ->
            PsdLayer(
                index = rec.index,
                name = rec.name,
                top = rec.top,
                left = rec.left,
                bottom = rec.bottom,
                right = rec.right,
                blendMode = rec.blendMode,
                opacity = rec.opacity,
                clipping = rec.clipping,
                defaultVisible = rec.visible,
                isGroupStart = rec.isGroupStart,
                isGroupEnd = rec.isGroupEnd,
                isTextLayer = rec.isTextLayer,
                depth = depths[idx],
                parentGroupIndex = parents[idx].takeIf { it >= 0 },
                channels = rec.channels,
            )
        }
    }

    private fun decodeLayerName(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        return if ('\uFFFD' in utf8) String(bytes, shiftJis) else utf8
    }

    private fun readAscii(raf: RandomAccessFile, count: Int): String {
        val buf = ByteArray(count)
        raf.readFully(buf)
        return String(buf, StandardCharsets.US_ASCII)
    }

    private fun RandomAccessFile.readUnsignedInt(): Long = readInt().toLong() and 0xFFFFFFFFL

    private fun readLength(raf: RandomAccessFile, isPsb: Boolean): Long =
        if (isPsb) raf.readLong() else raf.readUnsignedInt()

    private fun skipFully(raf: RandomAccessFile, bytes: Long) {
        if (bytes <= 0) return
        raf.seek(raf.filePointer + bytes)
    }

    private class RawLayerRecord(
        val index: Int,
        val name: String,
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int,
        val blendMode: String,
        val opacity: Int,
        val clipping: Boolean,
        val visible: Boolean,
        val isGroupStart: Boolean,
        val isGroupEnd: Boolean,
        val isTextLayer: Boolean,
        val channelPairs: List<Pair<Int, Long>>,
        var channels: List<PsdChannelMeta> = emptyList(),
    )

    private val PSB_64BIT_KEYS = setOf(
        "LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD",
    )
}
