package com.miniide.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceStateTest {

    @Test
    fun testOpenCloseAndNeighborSelection() {
        val ws = WorkspaceState()
        val t1 = ws.openTab("/proj/src/A.java", cursorOffset = 10)
        val t2 = ws.openTab("/proj/src/B.java", cursorOffset = 20)
        val t3 = ws.openTab("/proj/src/C.java", cursorOffset = 30)

        assertEquals(3, ws.tabs.size)
        assertEquals(t3.path, ws.activeTabPath)

        // Aynı dosyayı tekrar açınca yeni sekme eklenmemeli, mevcut sekme aktif olmalı
        ws.openTab("/proj/src/A.java")
        assertEquals(3, ws.tabs.size)
        assertEquals(t1.path, ws.activeTabPath)

        // Aktif sekmeyi (A.java, index 0) kapatınca komşu (B.java) aktif olmalı
        val next = ws.closeTab(t1.path)
        assertNotNull(next)
        assertEquals(t2.path, next!!.path)
        assertEquals(2, ws.tabs.size)

        // Kalanları kapatınca aktif sekme null olmalı
        ws.closeTab(t2.path)
        assertEquals(t3.path, ws.activeTabPath)
        assertNull(ws.closeTab(t3.path))
        assertTrue(ws.tabs.isEmpty())
    }

    @Test
    fun testCloseOthersCloseSavedAndReorderTabs() {
        val ws = WorkspaceState()
        ws.openTab("/proj/A.java")
        ws.openTab("/proj/B.java")
        ws.openTab("/proj/C.java")
        ws.setDirty("/proj/B.java", true)

        // Sıralama: A(0), B(1), C(2) -> C'yi başa taşı: C(0), A(1), B(2)
        assertTrue(ws.reorderTab(2, 0))
        assertEquals(listOf("C.java", "A.java", "B.java"), ws.tabs.map { it.name })

        // Ctrl+Tab ile sonraki sekmeye geçiş
        ws.openTab("/proj/C.java")
        assertEquals("A.java", ws.selectNextTab()?.name)
        assertEquals("B.java", ws.selectNextTab()?.name)
        assertEquals("C.java", ws.selectNextTab()?.name)

        // Kaydedilenleri kapat: sadece dirty olan B.java kalmalı
        ws.closeSavedTabs()
        assertEquals(listOf("B.java"), ws.tabs.map { it.name })
        assertEquals("/proj/B.java", ws.activeTabPath)

        // Diğerlerini kapat testi
        ws.openTab("/proj/D.java")
        ws.openTab("/proj/E.java")
        ws.closeOtherTabs("/proj/D.java")
        assertEquals(listOf("D.java"), ws.tabs.map { it.name })
        assertEquals("/proj/D.java", ws.activeTabPath)
    }

    @Test
    fun testSessionSerializeAndRestore() {
        val ws = WorkspaceState()
        ws.openTab("/proj/MainActivity.java", cursorOffset = 128)
        ws.updateCursorAndScroll("/proj/MainActivity.java", cursorOffset = 128, scrollY = 450)
        ws.openTab("/proj/activity_main.xml", cursorOffset = 42)
        ws.updateCursorAndScroll("/proj/activity_main.xml", cursorOffset = 42, scrollY = 90)
        ws.openInSecondarySplit("/proj/MainActivity.java")

        val serialized = ws.serializeSession()

        val restored = WorkspaceState()
        restored.restoreSession(serialized) { true }

        assertEquals(2, restored.tabs.size)
        assertEquals("/proj/activity_main.xml", restored.activeTabPath)
        assertTrue(restored.splitViewEnabled)
        assertEquals("/proj/MainActivity.java", restored.secondarySplitPath)
        assertEquals(128, restored.getTab("/proj/MainActivity.java")?.cursorOffset)
        assertEquals(450, restored.getTab("/proj/MainActivity.java")?.scrollY)
        assertEquals(42, restored.getTab("/proj/activity_main.xml")?.cursorOffset)
    }

    @Test
    fun testExternalModificationLineEndingIndentAndBreadcrumbs() {
        val ws = WorkspaceState()
        ws.openTab("/proj/Test.java", lastModifiedOnDisk = 1000L)
        assertFalse(ws.isExternallyModified("/proj/Test.java", 1000L))
        assertTrue(ws.isExternallyModified("/proj/Test.java", 2500L))
        ws.updateDiskTimestamp("/proj/Test.java", 2500L)
        assertFalse(ws.isExternallyModified("/proj/Test.java", 2500L))

        assertEquals("CRLF", WorkspaceState.detectLineEnding("line1\r\nline2\r\n"))
        assertEquals("LF", WorkspaceState.detectLineEnding("line1\nline2\n"))
        assertEquals("Boşluk:4", WorkspaceState.detectIndentation("class A {\n    int x = 1;\n    int y = 2;\n}"))
        assertEquals("Tab:4", WorkspaceState.detectIndentation("class A {\n\tint x = 1;\n\tint y = 2;\n}"))

        val root = File("/proj/MyApp")
        val target = File("/proj/MyApp/src/com/example/MainActivity.java")
        val crumbs = WorkspaceState.buildBreadcrumbs(root, target).map { it.first }
        assertEquals(listOf("MyApp", "src", "com", "example", "MainActivity.java"), crumbs)
    }
}
