package com.miniide.app.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedBuildExtensionsTest {

    @Test
    fun testIncrementalHashChangeDetection() {
        val h1 = BuildExtensions.IncrementalBuildCache.sha256("class A {}".toByteArray())
        val h2 = BuildExtensions.IncrementalBuildCache.sha256("class A { int x = 1; }".toByteArray())

        val prev = mapOf(
            "AndroidManifest.xml" to "m1",
            "src/com/app/A.java" to h1,
            "src/com/app/Old.java" to "oldHash",
            "res/values/strings.xml" to "s1"
        )
        val curr = mapOf(
            "AndroidManifest.xml" to "m1",
            "src/com/app/A.java" to h2,
            "src/com/app/New.java" to "newHash",
            "res/values/strings.xml" to "s1"
        )

        val report = BuildExtensions.IncrementalBuildCache.detectChanges(prev, curr)
        assertTrue(report.hasAnyChange)
        assertFalse(report.manifestChanged)
        assertFalse(report.resourcesChanged)
        assertEquals(listOf("src/com/app/A.java", "src/com/app/New.java"), report.changedJavaFiles)
        assertEquals(listOf("src/com/app/Old.java"), report.deletedJavaFiles)
    }

    @Test
    fun testDependenciesTxtParserAndMavenUrlResolver() {
        val txt = """
            # Yorum satırı
            com.google.code.gson:gson:2.10.1
            androidx.recyclerview:recyclerview:1.3.2
            com.squareup.okhttp3:okhttp:4.12.0@jar
            gecersiz_satir
        """.trimIndent()

        val coords = BuildExtensions.MavenDependencyResolver.parseDependenciesTxt(txt)
        assertEquals(3, coords.size)

        assertEquals("com.google.code.gson", coords[0].groupId)
        assertEquals("gson", coords[0].artifactId)
        assertEquals("2.10.1", coords[0].version)
        assertEquals("jar", coords[0].packaging)

        assertEquals("aar", coords[1].packaging)

        val urls = BuildExtensions.MavenDependencyResolver.resolveArtifactUrls(coords[0])
        assertEquals(
            "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar",
            urls.mavenCentralArtifact
        )
        assertEquals(
            "https://dl.google.com/dl/android/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.pom",
            urls.googleMavenPom
        )

        val pom = """
            <project><dependencies>
                <dependency><groupId>com.okio</groupId><artifactId>okio</artifactId><version>3.6.0</version></dependency>
                <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
            </dependencies></project>
        """.trimIndent()
        val direct = BuildExtensions.MavenDependencyResolver.parseDirectDependenciesFromPom(pom)
        assertEquals(1, direct.size)
        assertEquals("okio", direct[0].artifactId)
    }

    @Test
    fun testQuickFixImportAndPermission() {
        val err = BuildError("ECJ", true, "src/Main.java", 10, "Toast cannot be resolved to a type", "Toast bulunamadı")
        val fix = BuildExtensions.QuickFixEngine.suggestFix(err)
        assertNotNull(fix)
        assertTrue(fix is BuildExtensions.QuickFix.AddJavaImport)
        val fixedJava = BuildExtensions.QuickFixEngine.applyJavaImport(
            "package com.demo;\n\npublic class Main {}",
            (fix as BuildExtensions.QuickFix.AddJavaImport).importFqn
        )
        assertTrue(fixedJava.contains("import android.widget.Toast;"))

        val permErr = BuildError("Sistem", true, null, 0, "Missing android.permission.INTERNET", "İzin eksik")
        val permFix = BuildExtensions.QuickFixEngine.suggestFix(permErr) as BuildExtensions.QuickFix.AddManifestPermission
        val fixedManifest = BuildExtensions.QuickFixEngine.applyManifestPermission(
            "<manifest><application></application></manifest>",
            permFix.permission
        )
        assertTrue(fixedManifest.contains("android.permission.INTERNET"))
    }
}
