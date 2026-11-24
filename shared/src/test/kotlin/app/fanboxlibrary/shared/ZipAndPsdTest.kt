package app.fanboxlibrary.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipAndPsdTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun parsesAndCompositesLayeredPsdWithVisibilityToggleAndTextPreset() {
        val psd = temp.newFile("sample.psd")
        psd.writeBytes(buildSyntheticPsd())

        val doc = PsdEngine.open(psd)
        assertEquals(4, doc.width)
        assertEquals(4, doc.height)
        assertFalse(doc.isPsb)

        val uiLayers = doc.uiLayers
        assertEquals(3, uiLayers.size)
        assertEquals("セリフ", uiLayers[0].name)
        assertTrue(uiLayers[0].isTextLayer)
        assertEquals("フォルダ", uiLayers[1].name)
        assertTrue(uiLayers[1].isGroupStart)
        assertEquals("背景", uiLayers[2].name)
        assertEquals(1, uiLayers[2].depth)

        // Default render: red background (255,0,0) covered by green text layer (0,255,0) -> green!
        val full = PsdEngine.render(doc, maxDimension = 16)
        assertEquals(0xFF00FF00.toInt(), full.argb[0])

        // Hide text layers via preset -> red background revealed!
        val textlessVis = PsdPresets.applyHideText(doc, doc.defaultVisibilityMap())
        val textless = PsdEngine.render(doc, visibility = textlessVis, maxDimension = 16)
        assertEquals(0xFFFF0000.toInt(), textless.argb[0])

        // Hide parent folder -> child layer inside folder also hidden -> transparent!
        val folderHidden = textlessVis.toMutableMap().apply {
            put(uiLayers[1].index, false)
        }
        val empty = PsdEngine.render(doc, visibility = folderHidden, maxDimension = 16)
        assertEquals(0x00000000, empty.argb[0])
    }

    @Test
    fun exploresAndExtractsZipIntoLibraryStore() {
        val zip = temp.newFile("pack.zip")
        val psdBytes = buildSyntheticPsd()
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("差分/work.psd"))
            zos.write(psdBytes)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("__MACOSX/._work.psd"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        val items = ZipExplorer.listEntries(zip)
        assertEquals(1, items.size)
        assertTrue(items[0].isPsd)
        assertEquals("差分/work.psd", items[0].displayName)

        val home = temp.newFolder("lib")
        val store = LibraryStore(home)
        store.importManaged(PostDraft(title = "ZIP 테스트"), listOf(ImportSource("pack.zip") { zip.inputStream() }))
        val post = store.posts().single()

        val report = ZipExplorer.extractIntoLibrary(zip, store, post)
        assertEquals(1, report.added)
        val updated = store.posts().single()
        assertTrue(updated.assets.any { it.name == "work.psd" })
    }

    /**
     * Builds a valid 4x4 8-bit RGB PSD with bottom-to-top layers:
     * 0: lsct=3 (group end marker)
     * 1: "背景" (red 4x4 solid, RLE compressed)
     * 2: "フォルダ" (lsct=1 open folder header)
     * 3: "セリフ" (green 4x4 solid, TySh text layer, Raw compressed)
     */
    private fun buildSyntheticPsd(): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        // Header
        dos.writeBytes("8BPS")
        dos.writeShort(1) // PSD
        dos.write(ByteArray(6))
        dos.writeShort(3) // channels
        dos.writeInt(4)   // height
        dos.writeInt(4)   // width
        dos.writeShort(8) // depth
        dos.writeShort(3) // RGB

        // Color mode data (0) & Image resources (0)
        dos.writeInt(0)
        dos.writeInt(0)

        // Layer info payload
        val layerInfo = ByteArrayOutputStream()
        val ldos = DataOutputStream(layerInfo)
        ldos.writeShort(4) // 4 layer records

        val redRle = encodeSolidRle(4, 4, 0xFF.toByte())
        val zeroRle = encodeSolidRle(4, 4, 0x00.toByte())
        val greenRaw = encodeSolidRaw(4, 4, 0xFF.toByte())
        val zeroRaw = encodeSolidRaw(4, 4, 0x00.toByte())

        // Layer 0: group end divider (lsct = 3)
        writeLayerRecord(ldos, 0, 0, 0, 0, emptyList(), "</Layer group>", sectionType = 3, isText = false)
        // Layer 1: red background inside folder
        writeLayerRecord(
            ldos, 0, 0, 4, 4,
            listOf(-1 to redRle.size, 0 to redRle.size, 1 to zeroRle.size, 2 to zeroRle.size),
            "背景",
            sectionType = 0,
            isText = false,
        )
        // Layer 2: folder start (lsct = 1)
        writeLayerRecord(ldos, 0, 0, 0, 0, emptyList(), "フォルダ", sectionType = 1, isText = false)
        // Layer 3: green text layer on top
        writeLayerRecord(
            ldos, 0, 0, 4, 4,
            listOf(-1 to greenRaw.size, 0 to zeroRaw.size, 1 to greenRaw.size, 2 to zeroRaw.size),
            "セリフ",
            sectionType = 0,
            isText = true,
        )

        // Channel data in order:
        // Layer 0: none
        // Layer 1: A, R, G, B (RLE)
        ldos.write(redRle); ldos.write(redRle); ldos.write(zeroRle); ldos.write(zeroRle)
        // Layer 2: none
        // Layer 3: A, R, G, B (Raw)
        ldos.write(greenRaw); ldos.write(zeroRaw); ldos.write(greenRaw); ldos.write(zeroRaw)

        if (layerInfo.size() % 2 == 1) ldos.writeByte(0)
        val layerInfoBytes = layerInfo.toByteArray()

        // Layer & mask section
        dos.writeInt(4 + layerInfoBytes.size)
        dos.writeInt(layerInfoBytes.size)
        dos.write(layerInfoBytes)

        // Merged image (Raw compression + 3x16 zeros)
        dos.writeShort(0)
        dos.write(ByteArray(3 * 16))
        return out.toByteArray()
    }

    private fun writeLayerRecord(
        dos: DataOutputStream,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        channels: List<Pair<Int, Int>>,
        unicodeName: String,
        sectionType: Int,
        isText: Boolean,
    ) {
        dos.writeInt(top); dos.writeInt(left); dos.writeInt(bottom); dos.writeInt(right)
        dos.writeShort(channels.size)
        channels.forEach { (id, len) ->
            dos.writeShort(id)
            dos.writeInt(len)
        }
        dos.writeBytes("8BIM")
        dos.writeBytes("norm")
        dos.writeByte(255) // opacity
        dos.writeByte(0)   // clipping
        dos.writeByte(0)   // visible
        dos.writeByte(0)   // filler

        val extra = ByteArrayOutputStream()
        val edos = DataOutputStream(extra)
        edos.writeInt(0) // mask len
        edos.writeInt(0) // blending ranges len
        // Pascal name (ascii fallback, padded to multiple of 4)
        edos.writeByte(1); edos.writeByte('L'.code); edos.writeByte(0); edos.writeByte(0)

        // luni unicode name block
        val utf16 = unicodeName.toByteArray(StandardCharsets.UTF_16BE)
        edos.writeBytes("8BIM"); edos.writeBytes("luni")
        edos.writeInt(4 + utf16.size)
        edos.writeInt(unicodeName.length)
        edos.write(utf16)

        if (sectionType != 0) {
            edos.writeBytes("8BIM"); edos.writeBytes("lsct")
            edos.writeInt(4)
            edos.writeInt(sectionType)
        }
        if (isText) {
            edos.writeBytes("8BIM"); edos.writeBytes("TySh")
            edos.writeInt(4)
            edos.writeInt(0)
        }

        val extraBytes = extra.toByteArray()
        dos.writeInt(extraBytes.size)
        dos.write(extraBytes)
    }

    private fun encodeSolidRaw(w: Int, h: Int, value: Byte): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(0) // Raw
        dos.write(ByteArray(w * h) { value })
        return out.toByteArray()
    }

    private fun encodeSolidRle(w: Int, h: Int, value: Byte): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(1) // RLE
        // Each row is 2 bytes: [-(w-1), value]
        repeat(h) { dos.writeShort(2) }
        repeat(h) {
            dos.writeByte((1 - w) and 0xFF)
            dos.writeByte(value.toInt() and 0xFF)
        }
        return out.toByteArray()
    }
}
