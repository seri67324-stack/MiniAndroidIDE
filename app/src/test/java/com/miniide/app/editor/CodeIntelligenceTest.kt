package com.miniide.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodeIntelligenceTest {

    @Test
    fun testJavaCompletionAndMemberInference() {
        val code = """
            public class Demo {
                void run() {
                    TextView tvTitle = null;
                    tvTitle.set
                }
            }
        """.trimIndent()

        // "tvTitle.set" sonrasında TextView üyeleri (setText, setTextColor, setTextSize...) önerilmeli
        val dotCursor = code.indexOf("tvTitle.set") + "tvTitle.set".length
        val (_, members) = CodeIntelligenceEngine.computeCompletions(
            text = code,
            cursorOffset = dotCursor,
            language = "java"
        )
        assertTrue(members.any { it.label == "setText()" })
        assertTrue(members.any { it.label == "setTextColor()" })

        // 2+ karakter tamamlama: "pub" -> "public"
        val kwCode = "pub"
        val (_, kws) = CodeIntelligenceEngine.computeCompletions(
            text = kwCode,
            cursorOffset = 3,
            language = "java",
            androidClasses = listOf("PublicKey", "Activity")
        )
        assertTrue(kws.any { it.label == "public" })
        assertTrue(kws.any { it.label == "PublicKey" })
    }

    @Test
    fun testXmlResourceReferenceResolverAndCompletion() {
        val tempRes = Files.createTempDirectory("miniide_res_test").toFile()
        try {
            val valuesDir = File(tempRes, "values").apply { mkdirs() }
            val layoutDir = File(tempRes, "layout").apply { mkdirs() }
            File(valuesDir, "strings.xml").writeText(
                """<resources><string name="app_name">Test</string><string name="hello_msg">Selam</string></resources>"""
            )
            File(valuesDir, "colors.xml").writeText(
                """<resources><color name="brand_blue">#2563EB</color></resources>"""
            )
            File(layoutDir, "activity_main.xml").writeText(
                """<LinearLayout><TextView android:id="@+id/tvMainTitle" android:text="@string/he" /></LinearLayout>"""
            )

            val resources = CodeIntelligenceEngine.collectXmlResources(tempRes)
            assertTrue(resources["string"]!!.containsAll(listOf("app_name", "hello_msg")))
            assertTrue(resources["color"]!!.contains("brand_blue"))
            assertTrue(resources["id"]!!.contains("tvMainTitle"))

            val xmlText = """<TextView android:text="@string/he"""
            val (_, items) = CodeIntelligenceEngine.computeCompletions(
                text = xmlText,
                cursorOffset = xmlText.length,
                language = "xml",
                resDir = tempRes
            )
            assertEquals(1, items.size)
            assertEquals("@string/hello_msg", items[0].insertText)
        } finally {
            tempRes.deleteRecursively()
        }
    }

    @Test
    fun testFoldRangesCalculation() {
        val javaCode = """
            public class A {
                void m() {
                    if (true) {
                        return;
                    }
                }
            }
        """.trimIndent()
        val javaFolds = CodeIntelligenceEngine.computeFoldRanges(javaCode, "java")
        assertEquals(3, javaFolds.size)
        assertEquals(0, javaFolds[0].startLineZeroBased)
        assertEquals(6, javaFolds[0].endLineZeroBased)
        assertEquals(1, javaFolds[1].startLineZeroBased)
        assertEquals(5, javaFolds[1].endLineZeroBased)

        val xmlCode = """
            <LinearLayout>
                <FrameLayout>
                    <TextView />
                </FrameLayout>
            </LinearLayout>
        """.trimIndent()
        val xmlFolds = CodeIntelligenceEngine.computeFoldRanges(xmlCode, "xml")
        assertEquals(2, xmlFolds.size)
        assertEquals("<LinearLayout>", xmlFolds[0].label)
        assertEquals(0, xmlFolds[0].startLineZeroBased)
        assertEquals(4, xmlFolds[0].endLineZeroBased)
    }

    @Test
    fun testJavaAndXmlFormattersAndGoToDefinition() {
        val messyJava = "public class A {\nint x = 1;\nvoid hello() {\nx++;\n}\n}"
        val formattedJava = CodeIntelligenceEngine.formatJava(messyJava)
        assertEquals(
            "public class A {\n    int x = 1;\n    void hello() {\n        x++;\n    }\n}",
            formattedJava
        )

        val messyXml = "<LinearLayout>\n<TextView />\n</LinearLayout>"
        val formattedXml = CodeIntelligenceEngine.formatXml(messyXml)
        assertEquals("<LinearLayout>\n    <TextView />\n</LinearLayout>", formattedXml)

        val pair = CodeIntelligenceEngine.findMatchingBracket("void f() { return; }", 9)
        assertNotNull(pair)
        assertEquals(9 to 19, pair)

        val def = CodeIntelligenceEngine.findDefinition(formattedJava, "hello", null)
        assertNotNull(def)
        assertEquals(3, def!!.lineOneBased)
    }
}
