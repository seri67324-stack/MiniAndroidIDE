package com.miniide.app.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectTemplatesAndToolsTest {

    @Test
    fun testPackageClassAndResourceNameValidation() {
        assertTrue(ProjectTemplatesAndTools.isValidPackageName("com.ornek.uygulama"))
        assertFalse(ProjectTemplatesAndTools.isValidPackageName("TekParca"))
        assertFalse(ProjectTemplatesAndTools.isValidPackageName("com.123.app"))

        assertTrue(ProjectTemplatesAndTools.isValidJavaClassName("MainActivity"))
        assertFalse(ProjectTemplatesAndTools.isValidJavaClassName("mainActivity"))
        assertFalse(ProjectTemplatesAndTools.isValidJavaClassName("123Class"))

        assertTrue(ProjectTemplatesAndTools.isValidResourceName("btn_submit_1"))
        assertFalse(ProjectTemplatesAndTools.isValidResourceName("BtnSubmit"))
    }

    @Test
    fun testManifestEditorOperations() {
        val baseManifest = """
            <manifest package="com.demo.app">
                <application>
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        // İzin ekleme ve kaldırma
        val withPerm = ProjectTemplatesAndTools.ManifestEditor.addPermission(baseManifest, "android.permission.CAMERA")
        assertEquals(listOf("android.permission.CAMERA"), ProjectTemplatesAndTools.ManifestEditor.listPermissions(withPerm))
        val withoutPerm = ProjectTemplatesAndTools.ManifestEditor.removePermission(withPerm, "android.permission.CAMERA")
        assertTrue(ProjectTemplatesAndTools.ManifestEditor.listPermissions(withoutPerm).isEmpty())

        // Activity ekleme ve launcher değiştirme
        val withSecond = ProjectTemplatesAndTools.ManifestEditor.addActivity(baseManifest, "SecondActivity", exported = true)
        assertEquals(listOf(".MainActivity", ".SecondActivity"), ProjectTemplatesAndTools.ManifestEditor.listActivities(withSecond))

        val switchedLauncher = ProjectTemplatesAndTools.ManifestEditor.setLauncherActivity(withSecond, "SecondActivity")
        assertTrue(switchedLauncher.contains("android:name=\".SecondActivity\""))
        assertTrue(switchedLauncher.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun testAllFiveProjectTemplatesGenerateValidFiles() {
        val tempRoot = Files.createTempDirectory("miniide_templates_test").toFile()
        try {
            for (tpl in ProjectTemplatesAndTools.ProjectTemplate.entries) {
                val dir = ProjectTemplatesAndTools.createProjectFromTemplate(
                    projectsRoot = tempRoot,
                    appName = "App_${tpl.name}",
                    packageName = "com.test.${tpl.name.lowercase()}",
                    template = tpl,
                    minSdk = 24
                )
                assertTrue(File(dir, "AndroidManifest.xml").isFile)
                assertTrue(File(dir, "AndroidManifest.xml").readText().contains("minSdkVersion=\"24\""))
                val hasJava = File(dir, "src").walkTopDown().any { it.isFile && it.extension == "java" }
                assertTrue("Template $tpl must generate Java source", hasJava)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testSuggestPackageName() {
        assertEquals("com.ornek.notdefteri", ProjectTemplatesAndTools.suggestPackageName("Not Defteri"))
        assertEquals("com.ornek.app2048oyunu", ProjectTemplatesAndTools.suggestPackageName("2048 Oyunu"))
        assertEquals("com.ornek.gocbilhatirlatici", ProjectTemplatesAndTools.suggestPackageName("Göçbil Hatırlatıcı"))
        assertEquals("com.ornek.sicaksucuk", ProjectTemplatesAndTools.suggestPackageName("Sıcak ŞÜÇÜK"))
        assertEquals("com.ornek.uygulama", ProjectTemplatesAndTools.suggestPackageName(""))
        assertEquals("com.ornek.uygulama", ProjectTemplatesAndTools.suggestPackageName("   !!! "))
        // Üretilen her paket adı geçerli olmalı
        listOf("Not Defteri", "2048 Oyunu", "", "ÇĞİÖŞÜ").forEach {
            assertTrue(ProjectTemplatesAndTools.isValidPackageName(ProjectTemplatesAndTools.suggestPackageName(it)))
        }
    }
}
