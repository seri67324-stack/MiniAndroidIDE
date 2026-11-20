package com.miniide.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeybindingAndTransformsTest {

    @Test
    fun testKeybindingRegistryResolutionAndConflicts() {
        val reg = KeybindingRegistry()

        // VS Code uyumlu varsayılan kısayolların çözümlenmesi
        assertEquals(KeyAction.SAVE, reg.resolve(KeyStroke(LogicalKey.S, ctrl = true)))
        assertEquals(KeyAction.UNDO, reg.resolve(KeyStroke(LogicalKey.Z, ctrl = true)))
        assertEquals(KeyAction.REDO, reg.resolve(KeyStroke(LogicalKey.Y, ctrl = true)))
        assertEquals(KeyAction.REDO, reg.resolve(KeyStroke(LogicalKey.Z, ctrl = true, shift = true)))
        assertEquals(KeyAction.FIND, reg.resolve(KeyStroke(LogicalKey.F, ctrl = true)))
        assertEquals(KeyAction.REPLACE, reg.resolve(KeyStroke(LogicalKey.H, ctrl = true)))
        assertEquals(KeyAction.GOTO_LINE, reg.resolve(KeyStroke(LogicalKey.G, ctrl = true)))
        assertEquals(KeyAction.QUICK_OPEN, reg.resolve(KeyStroke(LogicalKey.P, ctrl = true)))
        assertEquals(KeyAction.COMMAND_PALETTE, reg.resolve(KeyStroke(LogicalKey.P, ctrl = true, shift = true)))
        assertEquals(KeyAction.COMMAND_PALETTE, reg.resolve(KeyStroke(LogicalKey.F1)))
        assertEquals(KeyAction.SELECT_NEXT_MATCH, reg.resolve(KeyStroke(LogicalKey.D, ctrl = true)))
        assertEquals(KeyAction.TOGGLE_COMMENT, reg.resolve(KeyStroke(LogicalKey.SLASH, ctrl = true)))
        assertEquals(KeyAction.INDENT_LINE, reg.resolve(KeyStroke(LogicalKey.RIGHT_BRACKET, ctrl = true)))
        assertEquals(KeyAction.OUTDENT_LINE, reg.resolve(KeyStroke(LogicalKey.LEFT_BRACKET, ctrl = true)))
        assertEquals(KeyAction.MOVE_LINE_UP, reg.resolve(KeyStroke(LogicalKey.UP, alt = true)))
        assertEquals(KeyAction.MOVE_LINE_DOWN, reg.resolve(KeyStroke(LogicalKey.DOWN, alt = true)))
        assertEquals(KeyAction.DUPLICATE_LINE_UP, reg.resolve(KeyStroke(LogicalKey.UP, alt = true, shift = true)))
        assertEquals(KeyAction.DUPLICATE_LINE_DOWN, reg.resolve(KeyStroke(LogicalKey.DOWN, alt = true, shift = true)))
        assertEquals(KeyAction.DELETE_LINE, reg.resolve(KeyStroke(LogicalKey.K, ctrl = true, shift = true)))
        assertEquals(KeyAction.WORD_LEFT, reg.resolve(KeyStroke(LogicalKey.LEFT, ctrl = true)))
        assertEquals(KeyAction.WORD_RIGHT, reg.resolve(KeyStroke(LogicalKey.RIGHT, ctrl = true)))
        assertEquals(KeyAction.SELECT_ALL, reg.resolve(KeyStroke(LogicalKey.A, ctrl = true)))
        assertEquals(KeyAction.COPY, reg.resolve(KeyStroke(LogicalKey.C, ctrl = true)))
        assertEquals(KeyAction.CUT, reg.resolve(KeyStroke(LogicalKey.X, ctrl = true)))
        assertEquals(KeyAction.PASTE, reg.resolve(KeyStroke(LogicalKey.V, ctrl = true)))
        assertEquals(KeyAction.TOGGLE_SIDE_PANEL, reg.resolve(KeyStroke(LogicalKey.B, ctrl = true)))
        assertEquals(KeyAction.SWITCH_TAB, reg.resolve(KeyStroke(LogicalKey.TAB, ctrl = true)))
        assertEquals(KeyAction.CLOSE_TAB, reg.resolve(KeyStroke(LogicalKey.W, ctrl = true)))
        assertEquals(KeyAction.BUILD_AND_RUN, reg.resolve(KeyStroke(LogicalKey.F5)))

        // Varsayılan durumda hiç çakışma olmamalı
        assertTrue(reg.findAllConflicts().isEmpty())

        // FIND komutuna Ctrl+S atanmak istendiğinde SAVE ile çakışma tespit edilmeli
        val ctrlS = KeyStroke(LogicalKey.S, ctrl = true)
        val conflicts = reg.findConflicts(KeyAction.FIND, ctrlS)
        assertEquals(listOf(KeyAction.SAVE), conflicts)

        // Atama yapılırsa findAllConflicts içinde görünmeli, sıfırlanınca temizlenmeli
        reg.setStroke(KeyAction.FIND, ctrlS)
        val allConflicts = reg.findAllConflicts()
        assertEquals(1, allConflicts.size)
        assertTrue(allConflicts[ctrlS]!!.containsAll(listOf(KeyAction.SAVE, KeyAction.FIND)))

        reg.resetToDefaults()
        assertTrue(reg.findAllConflicts().isEmpty())
    }

    @Test
    fun testStickyModifierStateMachine() {
        val sticky = StickyModifierManager()
        assertEquals(StickyState.OFF, sticky.ctrl)
        assertEquals(StickyState.OFF, sticky.alt)
        assertEquals(StickyState.OFF, sticky.shift)

        // 1. Tek dokunuş -> ONE_SHOT (sonraki tuşa uygulanır ve otomatik OFF olur)
        assertEquals(StickyState.ONE_SHOT, sticky.onModifierTapped(ModifierKey.CTRL))
        val stroke1 = sticky.consumeForKey(LogicalKey.S)
        assertTrue(stroke1.ctrl)
        assertFalse(stroke1.alt)
        assertFalse(stroke1.shift)
        assertEquals(StickyState.OFF, sticky.ctrl)

        // 2. İki dokunuş -> LOCKED (birden fazla tuş basımında aktif kalır)
        assertEquals(StickyState.ONE_SHOT, sticky.onModifierTapped(ModifierKey.SHIFT))
        assertEquals(StickyState.LOCKED, sticky.onModifierTapped(ModifierKey.SHIFT))

        val sel1 = sticky.consumeForKey(LogicalKey.RIGHT)
        assertTrue(sel1.shift)
        assertEquals(StickyState.LOCKED, sticky.shift)

        val sel2 = sticky.consumeForKey(LogicalKey.RIGHT)
        assertTrue(sel2.shift)
        assertEquals(StickyState.LOCKED, sticky.shift)

        // 3. Üçüncü dokunuş -> OFF
        assertEquals(StickyState.OFF, sticky.onModifierTapped(ModifierKey.SHIFT))
        val sel3 = sticky.consumeForKey(LogicalKey.RIGHT)
        assertFalse(sel3.shift)
    }

    @Test
    fun testLineMoveUpAndDownTransforms() {
        val code = "satir1\nsatir2\nsatir3"
        val idx = LineIndex().apply { rebuild(code) }

        // 2. satırı ("satir2", ofset 7..13) yukarı taşı -> "satir2\nsatir1\nsatir3"
        val upRes = EditorTextTransforms.moveLinesUp(code, idx, 9, 9)
        assertNotNull(upRes)
        val afterUp = upRes!!.applyToString(code)
        assertEquals("satir2\nsatir1\nsatir3", afterUp)
        assertEquals(2, upRes.newSelStart) // imleç 't' harfinde korunmalı

        // 1. satır daha fazla yukarı taşınamaz
        val idxAfterUp = LineIndex().apply { rebuild(afterUp) }
        assertNull(EditorTextTransforms.moveLinesUp(afterUp, idxAfterUp, 0, 0))

        // 1. satırı ("satir2") tekrar aşağı taşı -> "satir1\nsatir2\nsatir3"
        val downRes = EditorTextTransforms.moveLinesDown(afterUp, idxAfterUp, 2, 2)
        assertNotNull(downRes)
        assertEquals("satir1\nsatir2\nsatir3", downRes!!.applyToString(afterUp))
    }

    @Test
    fun testLineDuplicateAndDeleteTransforms() {
        val code = "int a = 1;\nint b = 2;\nint c = 3;"
        val idx = LineIndex().apply { rebuild(code) }

        // 2. satırı ("int b = 2;") aşağı çoğalt
        val dupDown = EditorTextTransforms.duplicateLines(code, idx, 14, 14, up = false)
        val afterDup = dupDown.applyToString(code)
        assertEquals("int a = 1;\nint b = 2;\nint b = 2;\nint c = 3;", afterDup)
        // İmleç alttaki kopyaya geçmiş olmalı
        val idxDup = LineIndex().apply { rebuild(afterDup) }
        assertEquals(2, idxDup.lineAtOffset(dupDown.newSelStart))

        // Çoğaltılan 3. satırı sil (Ctrl+Shift+K)
        val delRes = EditorTextTransforms.deleteLines(afterDup, idxDup, dupDown.newSelStart, dupDown.newSelEnd)
        assertEquals(code, delRes.applyToString(afterDup))
    }

    @Test
    fun testToggleCommentIndentOutdentTransforms() {
        val javaCode = "public class Test {\n    int x = 10;\n    int y = 20;\n}"
        val idx = LineIndex().apply { rebuild(javaCode) }

        // 2. ve 3. satırları yorum satırı yap (Ctrl+/)
        val line2Start = idx.lineStart(1)
        val line3End = idx.lineEnd(2, javaCode.length)
        val commentOn = EditorTextTransforms.toggleLineComment(javaCode, idx, line2Start, line3End, "java")
        val commentedJava = commentOn.applyToString(javaCode)
        assertEquals("public class Test {\n    // int x = 10;\n    // int y = 20;\n}", commentedJava)

        // Tekrar Ctrl+/ ile yorumu kaldır
        val idxCommented = LineIndex().apply { rebuild(commentedJava) }
        val commentOff = EditorTextTransforms.toggleLineComment(
            commentedJava,
            idxCommented,
            commentOn.newSelStart,
            commentOn.newSelEnd,
            "java"
        )
        assertEquals(javaCode, commentOff.applyToString(commentedJava))

        // Girinti artır (Ctrl+]) ve azalt (Ctrl+[)
        val indented = EditorTextTransforms.indentLines(javaCode, idx, line2Start, line2Start).applyToString(javaCode)
        assertTrue(indented.contains("        int x = 10;"))
        val idxIndented = LineIndex().apply { rebuild(indented) }
        val outdented = EditorTextTransforms.outdentLines(indented, idxIndented, line2Start + 4, line2Start + 4).applyToString(indented)
        assertEquals(javaCode, outdented)
    }

    @Test
    fun testSmartHomeWordJumpAndShiftSelection() {
        val code = "    public static void main()"
        val idx = LineIndex().apply { rebuild(code) }

        // İmleç satır sonundayken Home -> ilk boşluk olmayan karaktere (ofset 4: 'p') atlar
        val h1 = EditorTextTransforms.smartHomeOffset(code, idx, 15)
        assertEquals(4, h1)

        // İmleç zaten ofset 4'teyken tekrar Home -> satırın en başına (ofset 0) atlar
        val h2 = EditorTextTransforms.smartHomeOffset(code, idx, 4)
        assertEquals(0, h2)

        // Kelime atlama (Ctrl+→ ve Ctrl+←)
        val w1 = EditorTextTransforms.wordJumpOffset(code, 4, forward = true)
        assertEquals(10, w1) // "public" sonu
        val wBack = EditorTextTransforms.wordJumpOffset(code, w1, forward = false)
        assertEquals(4, wBack)

        // Shift+Ok ile seçim genişletme
        val (selA, selB) = EditorTextTransforms.computeSelectionAfterMove(anchorOffset = 4, targetOffset = 10, extendWithShift = true)
        assertEquals(4, selA)
        assertEquals(10, selB)
    }
}
