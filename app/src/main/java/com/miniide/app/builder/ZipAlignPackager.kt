package com.miniide.app.builder

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipAlignPackager {

    /**
     * resources.ap_ ve classes.dex dosyalarını tek bir APK'da birleştirir.
     * Sıkıştırılmamış (STORED) girdileri 4-byte sınırına hizalar (zipalign -p 4 muadili).
     */
    fun packageAndAlign(resourcesApFile: File, dexFiles: List<File>, outputUnsignedApk: File) {
        if (outputUnsignedApk.exists()) outputUnsignedApk.delete()
        val seenEntries = HashSet<String>()

        FileOutputStream(outputUnsignedApk).use { fos ->
            val countingOut = CountingOutputStream(fos)
            ZipOutputStream(countingOut).use { zos ->
                // 1. resources.ap_ içindeki tüm girdileri kopyala
                ZipFile(resourcesApFile).use { resZip ->
                    val entries = resZip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !seenEntries.add(entry.name)) continue
                        val bytes = resZip.getInputStream(entry).use { it.readBytes() }

                        val storeUncompressed = entry.name == "resources.arsc" ||
                                entry.name.endsWith(".png", ignoreCase = true) ||
                                entry.name.endsWith(".webp", ignoreCase = true) ||
                                entry.name.endsWith(".ogg", ignoreCase = true) ||
                                entry.name.endsWith(".mp3", ignoreCase = true) ||
                                entry.method == ZipEntry.STORED

                        writeEntryAligned(zos, countingOut, entry.name, bytes, storeUncompressed)
                    }
                }

                // 2. classes.dex (ve varsa classes2.dex...) dosyalarını ekle
                dexFiles.forEachIndexed { index, dexFile ->
                    val dexEntryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    if (seenEntries.add(dexEntryName)) {
                        val bytes = FileInputStream(dexFile).use { it.readBytes() }
                        writeEntryAligned(zos, countingOut, dexEntryName, bytes, storeUncompressed = true)
                    }
                }
            }
        }
    }

    private fun writeEntryAligned(
        zos: ZipOutputStream,
        countingOut: CountingOutputStream,
        name: String,
        data: ByteArray,
        storeUncompressed: Boolean
    ) {
        val newEntry = ZipEntry(name)
        newEntry.time = 0L

        if (storeUncompressed) {
            newEntry.method = ZipEntry.STORED
            newEntry.size = data.size.toLong()
            newEntry.compressedSize = data.size.toLong()
            val crc = CRC32()
            crc.update(data)
            newEntry.crc = crc.value

            // ZIP Local File Header = 30 byte + dosya adı uzunluğu + extra alan uzunluğu
            val nameBytesLen = name.toByteArray(Charsets.UTF_8).size
            val dataStartOffsetWithoutExtra = countingOut.bytesWritten + 30 + nameBytesLen
            val alignment = 4
            var padding = ((alignment - (dataStartOffsetWithoutExtra % alignment)) % alignment).toInt()

            if (padding > 0) {
                // Extra alan geçerli bir "id(2) + boyut(2) + veri" kaydı olmalı (0xD935 zipalign başlığı).
                if (padding < 4) padding += 4
                val extra = ByteArray(padding)
                extra[0] = 0x35.toByte()
                extra[1] = 0xD9.toByte()
                extra[2] = ((padding - 4) and 0xFF).toByte()
                extra[3] = (((padding - 4) shr 8) and 0xFF).toByte()
                newEntry.extra = extra
            }
        } else {
            newEntry.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(newEntry)
        zos.write(data)
        zos.closeEntry()
    }

    private class CountingOutputStream(private val out: FileOutputStream) : java.io.OutputStream() {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
