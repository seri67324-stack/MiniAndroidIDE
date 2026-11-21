package com.miniide.app.builder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipAlignPackagerTest {

    private fun addStored(z: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = data.size.toLong()
        e.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        e.crc = crc.value
        z.putNextEntry(e)
        z.write(data)
        z.closeEntry()
    }

    private fun addDeflated(z: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.DEFLATED
        z.putNextEntry(e)
        z.write(data)
        z.closeEntry()
    }

    private fun pattern(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    /** Merkezi dizini okuyup STORED girdilerin verisinin dosyadaki başlangıç ofsetini (ad -> ofset) verir. */
    private fun storedDataOffsets(zip: File): Map<String, Long> {
        val b = zip.readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = b.size - 22
        assertEquals(0x06054b50, bb.getInt(eocd))
        val count = bb.getShort(eocd + 10).toInt() and 0xFFFF
        var off = bb.getInt(eocd + 16)
        val result = LinkedHashMap<String, Long>()
        repeat(count) {
            val method = bb.getShort(off + 10).toInt() and 0xFFFF
            val nameLen = bb.getShort(off + 28).toInt() and 0xFFFF
            val extraLen = bb.getShort(off + 30).toInt() and 0xFFFF
            val commentLen = bb.getShort(off + 32).toInt() and 0xFFFF
            val localOff = bb.getInt(off + 42)
            val localNameLen = bb.getShort(localOff + 26).toInt() and 0xFFFF
            val localExtraLen = bb.getShort(localOff + 28).toInt() and 0xFFFF
            val name = String(b, off + 46, nameLen, Charsets.UTF_8)
            if (method == 0) result[name] = (localOff + 30 + localNameLen + localExtraLen).toLong()
            off += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    @Test
    fun storedEntriesAreFourByteAligned() {
        val dir = Files.createTempDirectory("zalign").toFile()
        try {
            val resAp = File(dir, "resources.ap_")
            ZipOutputStream(FileOutputStream(resAp)).use { z ->
                addDeflated(z, "AndroidManifest.xml", "<manifest/>".toByteArray())
                addStored(z, "resources.arsc", pattern(1001, 1))
                addStored(z, "res/a.png", pattern(77, 2))
                addDeflated(z, "assets/notes.txt", pattern(500, 3))
                addStored(z, "res/b.png", pattern(3, 4))
            }
            val dex1 = File(dir, "classes.dex").apply { writeBytes(pattern(4099, 5)) }
            val dex2 = File(dir, "classes2.dex").apply { writeBytes(pattern(10, 6)) }
            val out = File(dir, "unsigned.apk")

            ZipAlignPackager.packageAndAlign(resAp, listOf(dex1, dex2), out)

            val offsets = storedDataOffsets(out)
            listOf("resources.arsc", "res/a.png", "res/b.png", "classes.dex", "classes2.dex").forEach { name ->
                val off = offsets[name]
                assertNotNull("$name STORED olarak yazılmalı", off)
                assertEquals("$name 4-byte hizalı olmalı", 0L, off!! % 4)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun contentsArePreservedAndLargeEntriesStream() {
        val dir = Files.createTempDirectory("zalign").toFile()
        try {
            val big = pattern(3 * 1024 * 1024, 9)
            val arsc = pattern(2048, 7)
            val resAp = File(dir, "resources.ap_")
            ZipOutputStream(FileOutputStream(resAp)).use { z ->
                addStored(z, "resources.arsc", arsc)
                addDeflated(z, "assets/big.bin", big)
            }
            val dex = File(dir, "classes.dex").apply { writeBytes(pattern(5000, 8)) }
            val out = File(dir, "unsigned.apk")

            ZipAlignPackager.packageAndAlign(resAp, listOf(dex), out)

            ZipFile(out).use { zf ->
                assertArrayEquals(arsc, zf.getInputStream(zf.getEntry("resources.arsc")).readBytes())
                assertArrayEquals(big, zf.getInputStream(zf.getEntry("assets/big.bin")).readBytes())
                assertArrayEquals(dex.readBytes(), zf.getInputStream(zf.getEntry("classes.dex")).readBytes())
                assertEquals(ZipEntry.STORED, zf.getEntry("resources.arsc").method)
                assertEquals(ZipEntry.STORED, zf.getEntry("classes.dex").method)
            }
            assertTrue(out.length() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
