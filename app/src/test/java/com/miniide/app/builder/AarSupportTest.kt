package com.miniide.app.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AarSupportTest {

    private fun makeAar(file: File, manifest: String, withRes: Boolean, extraEntry: String? = null) {
        ZipOutputStream(FileOutputStream(file)).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
            put("AndroidManifest.xml", manifest.toByteArray())
            put("classes.jar", byteArrayOf(1, 2, 3))
            put("libs/extra.jar", byteArrayOf(4, 5))
            put("R.txt", "int string app_name 0x7f010000".toByteArray())
            if (withRes) put("res/values/strings.xml", "<resources><string name=\"hi\">Hi</string></resources>".toByteArray())
            if (extraEntry != null) put(extraEntry, byteArrayOf(9))
        }
    }

    @Test
    fun extractsJarsAndResAndReportsRPackage() {
        val dir = Files.createTempDirectory("aar").toFile()
        try {
            val aar = File(dir, "lib.aar")
            makeAar(aar, """<manifest package="com.example.lib"><application/></manifest>""", withRes = true)

            val r = AarSupport.extract(listOf(aar), File(dir, "work"), "com.app")

            assertEquals(2, r.jars.size)
            assertTrue(r.jars.all { it.isFile })
            assertEquals(1, r.resDirs.size)
            assertTrue(File(r.resDirs[0], "values/strings.xml").isFile)
            assertEquals(listOf("com.example.lib"), r.rPackages)
            assertTrue(r.warnings.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aarWithoutResOrWithSamePackageAddsNoRPackage() {
        val dir = Files.createTempDirectory("aar").toFile()
        try {
            val noRes = File(dir, "a.aar")
            val samePkg = File(dir, "b.aar")
            makeAar(noRes, """<manifest package="com.example.a"/>""", withRes = false)
            makeAar(samePkg, """<manifest package="com.app"/>""", withRes = true)

            val r = AarSupport.extract(listOf(noRes, samePkg), File(dir, "work"), "com.app")

            assertEquals(1, r.resDirs.size)
            assertTrue(r.rPackages.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun manifestComponentsProduceWarning() {
        val dir = Files.createTempDirectory("aar").toFile()
        try {
            val aar = File(dir, "ui.aar")
            makeAar(aar, """<manifest package="com.example.ui"><application><activity android:name=".A"/></application></manifest>""", withRes = false)

            val r = AarSupport.extract(listOf(aar), File(dir, "work"), "com.app")

            assertEquals(1, r.warnings.size)
            assertTrue(r.warnings[0].contains("ui.aar"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zipSlipEntriesAreSkipped() {
        val dir = Files.createTempDirectory("aar").toFile()
        try {
            val aar = File(dir, "evil.aar")
            makeAar(aar, """<manifest package="com.example.evil"/>""", withRes = true, extraEntry = "res/../../escaped.txt")

            val r = AarSupport.extract(listOf(aar), File(dir, "work"), "com.app")

            assertTrue(r.warnings.any { it.contains("geçersiz girdi") })
            assertFalse(File(dir, "work/escaped.txt").exists())
            assertFalse(File(dir, "escaped.txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyListReturnsEmptyContents() {
        val r = AarSupport.extract(emptyList(), File("unused"), null)
        assertTrue(r.jars.isEmpty() && r.resDirs.isEmpty() && r.rPackages.isEmpty() && r.warnings.isEmpty())
    }
}
