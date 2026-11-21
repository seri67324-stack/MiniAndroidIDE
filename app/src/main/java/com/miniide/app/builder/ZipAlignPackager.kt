package com.miniide.app.builder

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipAlignPackager {

    private const val BUFFER_SIZE = 8 * 1024

    /**
     * Tüm girdilere verilen sabit zaman damgası: 1981-01-01 00:00 UTC.
     * DOS zaman aralığı (1980+) içinde olmalıdır. Aksi halde (ör. 0) bazı ZipOutputStream gerçekleştirimleri
     * (masaüstü JDK) her girdiye 9 baytlık "genişletilmiş zaman damgası" alanı ekler ve 4-byte hizalamayı bozar.
     */
    private const val ENTRY_TIME_MILLIS = 347_155_200_000L

    /**
     * resources.ap_ ve classes.dex dosyalarını tek bir APK'da birleştirir.
     * Sıkıştırılmamış (STORED) girdileri 4-byte sınırına hizalar (zipalign -p 4 muadili).
     * Girdiler belleğe topluca okunmaz; 8 KB'lık parçalarla akıtılır (büyük assets/ dosyalarında OOM olmaz).
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

                        if (shouldStore(entry)) {
                            // Boyut ve CRC32 zip'in merkezi dizininde zaten kayıtlıdır; yoksa akıştan hesapla.
                            var size = entry.size
                            var crc = entry.crc
                            if (size < 0 || crc < 0) {
                                val (c, s) = checksum { resZip.getInputStream(entry) }
                                crc = c
                                size = s
                            }
                            writeEntry(zos, countingOut, entry.name, true, size, crc) { resZip.getInputStream(entry) }
                        } else {
                            writeEntry(zos, countingOut, entry.name, false, -1L, 0L) { resZip.getInputStream(entry) }
                        }
                    }
                }

                // 2. classes.dex (ve varsa classes2.dex...) dosyalarını ekle
                dexFiles.forEachIndexed { index, dexFile ->
                    val dexEntryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    if (seenEntries.add(dexEntryName)) {
                        val (crc, size) = checksum { FileInputStream(dexFile) }
                        writeEntry(zos, countingOut, dexEntryName, true, size, crc) { FileInputStream(dexFile) }
                    }
                }
            }
        }
    }

    private fun shouldStore(entry: ZipEntry): Boolean =
        entry.name == "resources.arsc" ||
            entry.name.endsWith(".png", ignoreCase = true) ||
            entry.name.endsWith(".webp", ignoreCase = true) ||
            entry.name.endsWith(".ogg", ignoreCase = true) ||
            entry.name.endsWith(".mp3", ignoreCase = true) ||
            entry.method == ZipEntry.STORED

    /** Akıştan (CRC32, bayt sayısı) çifti hesaplar; belleğe yalnızca 8 KB tampon alınır. */
    private fun checksum(open: () -> InputStream): Pair<Long, Long> {
        val crc = CRC32()
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        open().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                total += n
            }
        }
        return crc.value to total
    }

    private fun writeEntry(
        zos: ZipOutputStream,
        countingOut: CountingOutputStream,
        name: String,
        storeUncompressed: Boolean,
        size: Long,
        crc: Long,
        open: () -> InputStream
    ) {
        val newEntry = ZipEntry(name)
        newEntry.time = ENTRY_TIME_MILLIS

        if (storeUncompressed) {
            newEntry.method = ZipEntry.STORED
            newEntry.size = size
            newEntry.compressedSize = size
            newEntry.crc = crc

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
        val buffer = ByteArray(BUFFER_SIZE)
        open().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                zos.write(buffer, 0, n)
            }
        }
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
