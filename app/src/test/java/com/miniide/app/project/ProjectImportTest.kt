package com.miniide.app.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ProjectImportTest {

    private val manifestWithPackage = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.demo.app">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34" />
    <application>
        <activity android:name=".MainActivity">
            <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
        </activity>
    </application>
</manifest>"""

    private val gradleManifest = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name=".MainActivity">
            <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
        </activity>
    </application>
</manifest>"""

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun classicProjectInSubfolderKeepsFolderName() {
        val tmp = tempDir("zip"); val out = tempDir("out")
        try {
            write(File(tmp, "MyApp/AndroidManifest.xml"), manifestWithPackage)
            write(File(tmp, "MyApp/src/com/demo/app/MainActivity.java"), "package com.demo.app; public class MainActivity {}")
            val r = ProjectManager.importFromExtracted(tmp, "Fallback", out)
            assertEquals("MyApp", r.projectDir.name)
            assertTrue(File(r.projectDir, "AndroidManifest.xml").isFile)
            assertTrue(File(r.projectDir, "src/com/demo/app/MainActivity.java").isFile)
            assertFalse(r.hasKotlinFiles)
        } finally { tmp.deleteRecursively(); out.deleteRecursively() }
    }

    @Test
    fun manifestAtZipRootUsesFallbackName() {
        val tmp = tempDir("zip"); val out = tempDir("out")
        try {
            write(File(tmp, "AndroidManifest.xml"), manifestWithPackage)
            write(File(tmp, "src/A.java"), "class A {}")
            val r = ProjectManager.importFromExtracted(tmp, "Fallback", out)
            assertEquals("Fallback", r.projectDir.name)
        } finally { tmp.deleteRecursively(); out.deleteRecursively() }
    }

    @Test
    fun deeplyNestedClassicProjectIsFound() {
        val tmp = tempDir("zip"); val out = tempDir("out")
        try {
            write(File(tmp, "a/b/c/d/Proj/AndroidManifest.xml"), manifestWithPackage)
            val r = ProjectManager.importFromExtracted(tmp, "Fallback", out)
            assertEquals("Proj", r.projectDir.name)
        } finally { tmp.deleteRecursively(); out.deleteRecursively() }
    }

    @Test
    fun gradleLayoutIsConvertedToClassicLayout() {
        val tmp = tempDir("zip"); val out = tempDir("out")
        try {
            val app = File(tmp, "Repo-main/app")
            write(File(app, "src/main/AndroidManifest.xml"), gradleManifest)
            write(File(app, "build.gradle.kts"), """
                android {
                    namespace = "com.demo.gradle"
                    defaultConfig { applicationId = "com.demo.gradle"; minSdk = 24; targetSdk = 34 }
                }
            """.trimIndent())
            write(File(app, "src/main/java/com/demo/gradle/MainActivity.java"), "package com.demo.gradle; public class MainActivity {}")
            write(File(app, "src/main/res/values/strings.xml"), "<resources></resources>")

            val r = ProjectManager.importFromExtracted(tmp, "Fallback", out)
            assertEquals("Repo-main", r.projectDir.name)

            val manifest = File(r.projectDir, "AndroidManifest.xml").readText()
            assertTrue(manifest.contains("package=\"com.demo.gradle\""))
            assertTrue(manifest.contains("android:minSdkVersion=\"24\""))
            assertTrue(File(r.projectDir, "src/com/demo/gradle/MainActivity.java").isFile)
            assertTrue(File(r.projectDir, "res/values/strings.xml").isFile)
            assertFalse(File(r.projectDir, "src/main").exists())
        } finally { tmp.deleteRecursively(); out.deleteRecursively() }
    }

    @Test
    fun gradleProjectWithKotlinGetsWarning() {
        val tmp = tempDir("zip"); val out = tempDir("out")
        try {
            val app = File(tmp, "Proj/app")
            write(File(app, "src/main/AndroidManifest.xml"), gradleManifest)
            write(File(app, "build.gradle"), "android { namespace 'com.demo.kt' }")
            write(File(app, "src/main/java/com/demo/kt/Main.kt"), "package com.demo.kt\nclass Main")
            val r = ProjectManager.importFromExtracted(tmp, "Fallback", out)
            assertTrue(r.hasKotlinFiles)
            assertTrue(r.notes.any { it.contains("Kotlin") })
        } finally { tmp.deleteRecursively(); out.deleteRecursively() }
    }

    @Test
    fun missingManifestGivesClearError() {
        val tmp = tempDir("zip"); val out = tempDir("out")
        try {
            write(File(tmp, "readme.txt"), "merhaba")
            val e = assertThrows(IOException::class.java) {
                ProjectManager.importFromExtracted(tmp, "Fallback", out)
            }
            assertTrue(e.message!!.contains("AndroidManifest.xml bulunamadı"))
        } finally { tmp.deleteRecursively(); out.deleteRecursively() }
    }

    @Test
    fun existingProjectNameGetsNumericSuffix() {
        val tmp = tempDir("zip"); val out = tempDir("out")
        try {
            write(File(tmp, "Dup/AndroidManifest.xml"), manifestWithPackage)
            File(out, "Dup").mkdirs()
            val r = ProjectManager.importFromExtracted(tmp, "Fallback", out)
            assertEquals("Dup-2", r.projectDir.name)
        } finally { tmp.deleteRecursively(); out.deleteRecursively() }
    }
}
