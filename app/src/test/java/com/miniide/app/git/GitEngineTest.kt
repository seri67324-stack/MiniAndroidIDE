package com.miniide.app.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitEngineTest {

    @Test
    fun testGitIgnoreMatching() {
        val rules = """
            # Yorum satırı
            build/
            gen/
            *.apk
            !release-keep.apk
            **/*.class
            /local.properties
        """.trimIndent()

        val matcher = GitDiffAndIgnore.GitIgnoreMatcher(rules)

        assertTrue(matcher.isIgnored("build", isDirectory = true))
        assertTrue(matcher.isIgnored("build/outputs/app.dex"))
        assertTrue(matcher.isIgnored("app/gen/R.java"))
        assertTrue(matcher.isIgnored("app-debug.apk"))
        assertTrue(matcher.isIgnored("bin/test/Main.class"))
        assertTrue(matcher.isIgnored("local.properties"))

        // İstisna (!) ve eşleşmeyen dosyalar
        assertFalse(matcher.isIgnored("release-keep.apk"))
        assertFalse(matcher.isIgnored("src/com/example/MainActivity.java"))
        assertFalse(matcher.isIgnored("sub/local.properties"))
    }

    @Test
    fun testLineDiffAndGutterMarkers() {
        val oldText = """
            line1
            line2
            line3
            line4
        """.trimIndent()

        val newText = """
            line1
            line2_modified
            line3
            line4
            line5_added
        """.trimIndent()

        val markers = GitDiffAndIgnore.LineDiffCalculator.computeGutterMarkers(oldText, newText)
        assertEquals(GitDiffAndIgnore.LineDiffCalculator.MARKER_MODIFIED, markers[2])
        assertEquals(GitDiffAndIgnore.LineDiffCalculator.MARKER_ADDED, markers[5])
        assertFalse(markers.containsKey(1))
        assertFalse(markers.containsKey(3))

        val inline = GitDiffAndIgnore.LineDiffCalculator.formatInlineDiff(oldText, newText)
        assertTrue(inline.contains("- line2"))
        assertTrue(inline.contains("+ line2_modified"))
        assertTrue(inline.contains("+ line5_added"))

        // Silinen satır testi
        val deletedMarkers = GitDiffAndIgnore.LineDiffCalculator.computeGutterMarkers("a\nb\nc", "a\nc")
        assertTrue(deletedMarkers.values.contains(GitDiffAndIgnore.LineDiffCalculator.MARKER_DELETED))
    }
}
