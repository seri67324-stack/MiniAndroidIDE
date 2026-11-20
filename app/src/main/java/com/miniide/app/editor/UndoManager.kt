package com.miniide.app.editor

/**
 * Tam metin kopyası (snapshot) yerine yalnızca değişen parçaları (start, oldText, newText) tutan,
 * ardışık yazma/silme işlemlerini birleştiren, adım (500) ve bellek sınırlı Geri Al / Yinele yöneticisi.
 */
class UndoManager(
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val maxMemoryChars: Int = DEFAULT_MAX_MEMORY_CHARS,
    val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS
) {

    data class EditDelta(
        val start: Int,
        val oldText: String,
        val newText: String,
        val timestampMs: Long
    ) {
        val memoryChars: Int
            get() = oldText.length + newText.length
    }

    /**Editable üzerinde uygulanacak tekil geri alma veya yineleme işlemi. */
    data class ApplyOp(
        val start: Int,
        val end: Int,
        val replacement: String,
        val selectionAfter: Int
    )

    private val undoStack = ArrayDeque<EditDelta>()
    private val redoStack = ArrayDeque<EditDelta>()
    private var totalMemoryChars = 0
    private var coalescingAllowed = true

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    val undoCount: Int
        get() = undoStack.size

    val redoCount: Int
        get() = redoStack.size

    val usedMemoryChars: Int
        get() = totalMemoryChars

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        totalMemoryChars = 0
        coalescingAllowed = true
    }

    /** İmleç elle taşındığında veya dosya kaydedildiğinde ardışık birleştirmeyi keser. */
    fun breakCoalescing() {
        coalescingAllowed = false
    }

    fun recordEdit(
        start: Int,
        oldText: String,
        newText: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (oldText == newText) return
        if (start < 0) return

        // Yeni bir düzenleme yapıldığında redo geçmişini temizle
        while (redoStack.isNotEmpty()) {
            totalMemoryChars -= redoStack.removeLast().memoryChars
        }

        val incomingChars = oldText.length + newText.length
        if (incomingChars > maxMemoryChars) {
            // Tek başına bellek sınırını aşan devasa yapıştırma işleminde geçmişi sıfırla
            clear()
            return
        }

        val merged = tryMergeWithLast(start, oldText, newText, timestampMs)
        if (!merged) {
            val delta = EditDelta(start, oldText, newText, timestampMs)
            undoStack.addLast(delta)
            totalMemoryChars += delta.memoryChars
            coalescingAllowed = true
        }

        trimToLimits()
    }

    fun undo(): ApplyOp? {
        if (undoStack.isEmpty()) return null
        coalescingAllowed = false
        val delta = undoStack.removeLast()
        redoStack.addLast(delta)
        return ApplyOp(
            start = delta.start,
            end = delta.start + delta.newText.length,
            replacement = delta.oldText,
            selectionAfter = delta.start + delta.oldText.length
        )
    }

    fun redo(): ApplyOp? {
        if (redoStack.isEmpty()) return null
        coalescingAllowed = false
        val delta = redoStack.removeLast()
        undoStack.addLast(delta)
        return ApplyOp(
            start = delta.start,
            end = delta.start + delta.oldText.length,
            replacement = delta.newText,
            selectionAfter = delta.start + delta.newText.length
        )
    }

    private fun tryMergeWithLast(
        start: Int,
        oldText: String,
        newText: String,
        timestampMs: Long
    ): Boolean {
        if (!coalescingAllowed || undoStack.isEmpty()) return false
        val last = undoStack.last()
        val elapsed = timestampMs - last.timestampMs
        if (elapsed < 0 || elapsed > coalesceWindowMs) return false

        // Satır sonu ('\n') içeren değişiklikler veya çok büyümüş parçalar ayrı adım olarak tutulur
        if (oldText.contains('\n') || newText.contains('\n') ||
            last.oldText.contains('\n') || last.newText.contains('\n')
        ) {
            return false
        }
        if (last.memoryChars + oldText.length + newText.length > MAX_COALESCED_CHARS) {
            return false
        }

        // 1. Ardışık karakter ekleme (yazma)
        if (last.oldText.isEmpty() && oldText.isEmpty() &&
            start == last.start + last.newText.length
        ) {
            undoStack.removeLast()
            totalMemoryChars -= last.memoryChars
            val combined = EditDelta(last.start, "", last.newText + newText, timestampMs)
            undoStack.addLast(combined)
            totalMemoryChars += combined.memoryChars
            return true
        }

        // 2. Ardışık geri silme (Backspace)
        if (last.newText.isEmpty() && newText.isEmpty() &&
            start + oldText.length == last.start
        ) {
            undoStack.removeLast()
            totalMemoryChars -= last.memoryChars
            val combined = EditDelta(start, oldText + last.oldText, "", timestampMs)
            undoStack.addLast(combined)
            totalMemoryChars += combined.memoryChars
            return true
        }

        // 3. Ardışık ileri silme (Delete)
        if (last.newText.isEmpty() && newText.isEmpty() &&
            start == last.start
        ) {
            undoStack.removeLast()
            totalMemoryChars -= last.memoryChars
            val combined = EditDelta(last.start, last.oldText + oldText, "", timestampMs)
            undoStack.addLast(combined)
            totalMemoryChars += combined.memoryChars
            return true
        }

        return false
    }

    private fun trimToLimits() {
        while (undoStack.size > maxSteps || (undoStack.isNotEmpty() && totalMemoryChars > maxMemoryChars)) {
            val dropped = undoStack.removeFirst()
            totalMemoryChars -= dropped.memoryChars
        }
        if (totalMemoryChars < 0) totalMemoryChars = 0
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 500
        const val DEFAULT_MAX_MEMORY_CHARS = 2 * 1024 * 1024 // ~4 MB UTF-16
        const val DEFAULT_COALESCE_WINDOW_MS = 750L
        private const val MAX_COALESCED_CHARS = 256
    }
}
