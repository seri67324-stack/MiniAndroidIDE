package com.miniide.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SearchAndCommandTest {

    @Test
    fun testFuzzyScoringAndCommandRegistryMru() {
        val exact = SearchAndCommandEngine.fuzzyScore("MainActivity", "MainActivity")
        val prefix = SearchAndCommandEngine.fuzzyScore("Main", "MainActivity.java")
        val camel = SearchAndCommandEngine.fuzzyScore("MA", "MainActivity.java")
        val noMatch = SearchAndCommandEngine.fuzzyScore("xyz", "MainActivity.java")

        assertTrue(exact > prefix)
        assertTrue(prefix > camel)
        assertTrue(camel > 0)
        assertEquals(0, noMatch)

        val reg = SearchAndCommandEngine.CommandRegistry()
        reg.register(SearchAndCommandEngine.IdeCommand("SAVE", "Kaydet", "Ctrl+S"))
        reg.register(SearchAndCommandEngine.IdeCommand("FIND", "Ara", "Ctrl+F"))
        reg.register(SearchAndCommandEngine.IdeCommand("BUILD", "Derle ve Çalıştır", "F5"))

        // Son kullanılan komut boş aramada en üstte gelmeli
        reg.markUsed("BUILD")
        val list = reg.search("")
        assertEquals("BUILD", list.first().id)

        // Bulanık aramada "kydt" -> "Kaydet" komutunu bulmalı
        val fuzzyRes = reg.search("kydt")
        assertEquals("SAVE", fuzzyRes.first().id)
    }

    @Test
    fun testQuickOpenLineParsing() {
        val q1 = SearchAndCommandEngine.parseQuickOpenQuery("MainActivity.java:120")
        assertEquals("MainActivity.java", q1.fileQuery)
        assertEquals(120, q1.targetLine)

        val q2 = SearchAndCommandEngine.parseQuickOpenQuery(":45")
        assertEquals("", q2.fileQuery)
        assertEquals(45, q2.targetLine)

        val q3 = SearchAndCommandEngine.parseQuickOpenQuery("strings.xml")
        assertEquals("strings.xml", q3.fileQuery)
        assertNull(q3.targetLine)
    }

    @Test
    fun testRegexWholeWordAndCaseSensitiveMatching() {
        val code = """
            int count = 1;
            int counter = 2;
            int COUNT = 3;
            int my_count_val = 4;
        """.trimIndent()

        // 1. Düz metin, büyük/küçük duyarsız: 4 eşleşme (count, counter, COUNT, my_count_val)
        val plain = SearchAndCommandEngine.findMatchesInText(
            code,
            SearchAndCommandEngine.SearchOptions(query = "count", caseSensitive = false, wholeWord = false)
        )
        assertEquals(4, plain.size)

        // 2. Tam kelime, büyük/küçük duyarsız: 2 eşleşme (count, COUNT)
        val wholeWord = SearchAndCommandEngine.findMatchesInText(
            code,
            SearchAndCommandEngine.SearchOptions(query = "count", caseSensitive = false, wholeWord = true)
        )
        assertEquals(2, wholeWord.size)

        // 3. Tam kelime + büyük/küçük duyarlı: 1 eşleşme (yalnızca count)
        val exactWord = SearchAndCommandEngine.findMatchesInText(
            code,
            SearchAndCommandEngine.SearchOptions(query = "count", caseSensitive = true, wholeWord = true)
        )
        assertEquals(1, exactWord.size)
        assertEquals(1, exactWord[0].lineOneBased)

        // 4. Regex eşleştirme: "= \d+;" -> 4 eşleşme
        val regexMatches = SearchAndCommandEngine.findMatchesInText(
            code,
            SearchAndCommandEngine.SearchOptions(query = "=\\s*\\d+;", useRegex = true)
        )
        assertEquals(4, regexMatches.size)
    }

    @Test
    fun testProjectSearchReplacePreviewAndRollback() {
        val tempRoot = Files.createTempDirectory("miniide_search_test").toFile()
        try {
            val srcDir = File(tempRoot, "src").apply { mkdirs() }
            val buildDir = File(tempRoot, "build").apply { mkdirs() }
            val genDir = File(tempRoot, "gen").apply { mkdirs() }

            val f1 = File(srcDir, "A.java").apply { writeText("class A { int oldName = 1; int oldName2 = 2; }") }
            val f2 = File(srcDir, "B.java").apply { writeText("class B { int oldName = 3; }") }
            // build/ ve gen/ hariç tutulmalı
            File(buildDir, "Ignored.java").writeText("int oldName = 99;")
            File(genDir, "R.java").writeText("int oldName = 100;")

            val opts = SearchAndCommandEngine.SearchOptions(
                query = "oldName",
                caseSensitive = true,
                wholeWord = true,
                includeExtensions = setOf("java")
            )

            val results = SearchAndCommandEngine.searchInProject(tempRoot, opts)
            assertEquals(2, results.size)
            assertEquals(2, results.sumOf { it.matches.size }) // A.java(1) + B.java(1), oldName2 hariç

            val previews = SearchAndCommandEngine.previewReplaceInProject(tempRoot, opts, "newName")
            assertEquals(2, previews.size)

            val backup = SearchAndCommandEngine.applyReplaceInProject(previews)
            assertTrue(f1.readText().contains("int newName = 1;"))
            assertTrue(f1.readText().contains("int oldName2 = 2;"))
            assertTrue(f2.readText().contains("int newName = 3;"))

            // Geri al (rollback) tüm dosyaları eski haline döndürmeli
            backup.rollback()
            assertTrue(f1.readText().contains("int oldName = 1;"))
            assertTrue(f2.readText().contains("int oldName = 3;"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
