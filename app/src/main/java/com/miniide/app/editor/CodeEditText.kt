package com.miniide.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatEditText

/**
 * 20.000+ satırlık büyük dosyalar için optimize edilmiş kod editörü:
 * - LineIndex ile O(log N) satır/sütun bulma ve artımlı (incremental) satır indeksi güncelleme
 * - Yalnızca görünen satırlar için gutter (satır numarası) ve aktif/hata satırı çizimi
 * - Kaydırma sırasında görünür satır aralığı değişim bildirimi
 */
class CodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    // DİKKAT: TextView yapıcısı onTextChanged ve onSelectionChanged'i bu alanlar başlamadan çağırır; ready bayrağı bunu korur.
    private var ready = false
    private var bulkLoading = false

    val lineIndex = LineIndex()

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeNumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val dividerPaint = Paint()
    private val activeLinePaint = Paint()
    private val errorLinePaint = Paint()
    private val indentGuidePaint = Paint()
    private val bracketMatchPaint = Paint()
    private val squigglyErrorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val squigglyWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterDotErrorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterDotWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gitDiffPaint = Paint()

    private var diagnosticsByLine: Map<Int, CodeIntelligenceEngine.LineDiagnostic> = emptyMap()
    private var matchingBracketPair: Pair<Int, Int>? = null
    var gitDiffMarkers: Map<Int, Int> = emptyMap() // 1-based line -> 1=added(green), 2=modified(blue), 3=deleted(red)
        set(value) {
            field = value
            invalidate()
        }
    var showIndentGuides: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var gutterWidth = 0
    private var lastDigits = 0
    private var currentFontSp = 13.5f
    private val dp = resources.displayMetrics.density

    var errorHighlightLine: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var onCursorChanged: ((line: Int, col: Int) -> Unit)? = null
    var onVisibleLinesChanged: ((firstVisibleLine: Int, lastVisibleLine: Int) -> Unit)? = null

    init {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSp)
        setHorizontallyScrolling(true)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSaveEnabled = false // büyük metinde durum kaydı TransactionTooLargeException üretebilir

        val tv = TypedValue()
        val bg = if (context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) tv.data else 0xFFFFFFFF.toInt()
        bgPaint.color = bg
        numPaint.color = 0xFF8A94A6.toInt()
        numPaint.typeface = Typeface.MONOSPACE

        activeNumPaint.color = 0xFF3B82F6.toInt()
        activeNumPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        dividerPaint.color = 0x33888888
        dividerPaint.strokeWidth = dp

        activeLinePaint.color = 0x163B82F6
        errorLinePaint.color = 0x2AEF4444
        indentGuidePaint.color = 0x24888888
        indentGuidePaint.strokeWidth = dp
        bracketMatchPaint.color = 0x4438BDF8
        squigglyErrorPaint.color = 0xFFEF4444.toInt()
        squigglyErrorPaint.strokeWidth = 1.4f * dp
        squigglyErrorPaint.style = Paint.Style.STROKE
        squigglyWarnPaint.color = 0xFFF59E0B.toInt()
        squigglyWarnPaint.strokeWidth = 1.4f * dp
        squigglyWarnPaint.style = Paint.Style.STROKE
        gutterDotErrorPaint.color = 0xFFEF4444.toInt()
        gutterDotWarnPaint.color = 0xFFF59E0B.toInt()

        ready = true
        updateGutter(force = true)
    }

    fun setDiagnostics(diagnostics: List<CodeIntelligenceEngine.LineDiagnostic>) {
        diagnosticsByLine = diagnostics.associateBy { it.lineOneBased }
        invalidate()
    }

    fun getDiagnosticAtLine(lineOneBased: Int): CodeIntelligenceEngine.LineDiagnostic? =
        diagnosticsByLine[lineOneBased]

    /** Büyük metinleri tek seferde yüklerken LineIndex'i tek geçişte kurar. */
    fun setCodeText(content: String) {
        bulkLoading = true
        try {
            lineIndex.rebuild(content)
            setText(content)
        } finally {
            bulkLoading = false
        }
        updateGutter(force = true)
    }

    fun getFontSizeSp(): Float = currentFontSp

    fun setFontSizeSp(sp: Float) {
        currentFontSp = sp.coerceIn(10f, 24f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSp)
        updateGutter(force = true)
        invalidate()
    }

    /** Ekranda o an görünen 0-tabanlı (ilk, son) satır aralığını döndürür. */
    fun getVisibleLineBounds(): Pair<Int, Int> {
        val l = layout ?: return 0 to 0
        val maxIdx = (l.lineCount - 1).coerceAtLeast(0)
        val first = l.getLineForVertical(scrollY.coerceAtLeast(0)).coerceIn(0, maxIdx)
        val bottomY = (scrollY + height).coerceAtLeast(0)
        val last = l.getLineForVertical(bottomY).coerceIn(first, maxIdx)
        return first to last
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (!ready) return
        if (!bulkLoading && text != null) {
            lineIndex.onTextChanged(text, start, lengthBefore, lengthAfter)
        }
        if (errorHighlightLine > 0 && lengthBefore != lengthAfter) {
            errorHighlightLine = 0
        }
        updateGutter(force = false)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready) return
        val t = text
        val len = t?.length ?: 0
        val safeStart = selStart.coerceIn(0, len)
        matchingBracketPair = if (t != null && len <= 500_000) {
            CodeIntelligenceEngine.findMatchingBracket(t, safeStart)
        } else {
            null
        }
        val pos = lineIndex.lineAndColumn(safeStart)
        onCursorChanged?.invoke(pos.line, pos.column)
        invalidate()
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (!ready || vert == oldVert) return
        val (first, last) = getVisibleLineBounds()
        onVisibleLinesChanged?.invoke(first, last)
    }

    private fun updateGutter(force: Boolean) {
        val lines = maxOf(1, lineIndex.lineCount)
        val digits = maxOf(2, lines.toString().length)
        if (!force && digits == lastDigits && gutterWidth > 0) return
        lastDigits = digits
        numPaint.textSize = textSize
        activeNumPaint.textSize = textSize
        val charW = numPaint.measureText("0")
        gutterWidth = (charW * (digits + 1) + 12 * dp).toInt()
        setPadding(gutterWidth + (8 * dp).toInt(), paddingTop, paddingRight, paddingBottom)
    }

    private fun currentCursorLine(): Int {
        val len = text?.length ?: 0
        return lineIndex.lineAtOffset(selectionStart.coerceIn(0, len))
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout
        val sx = scrollX.toFloat()
        val sy = scrollY.toFloat()
        val curLine = currentCursorLine()

        val (first, last) = if (l != null) getVisibleLineBounds() else (0 to 0)

        if (l != null) {
            // Yalnızca görünür aralık içindeyse aktif satır arka plan vurgusunu çiz
            if (curLine in first..last && curLine < l.lineCount) {
                val top = (l.getLineTop(curLine) + compoundPaddingTop).toFloat()
                val bottom = (l.getLineBottom(curLine) + compoundPaddingTop).toFloat()
                canvas.drawRect(sx + gutterWidth, top, sx + width, bottom, activeLinePaint)
            }
            // Yalnızca görünür aralık içindeyse hata satırı vurgusunu çiz
            val errIdx = errorHighlightLine - 1
            if (errIdx in first..last && errIdx < l.lineCount) {
                val top = (l.getLineTop(errIdx) + compoundPaddingTop).toFloat()
                val bottom = (l.getLineBottom(errIdx) + compoundPaddingTop).toFloat()
                canvas.drawRect(sx + gutterWidth, top, sx + width, bottom, errorLinePaint)
            }
            // Parantez eşleştirme vurgusu
            val pair = matchingBracketPair
            val charW = numPaint.measureText("0")
            if (pair != null && text != null) {
                val len = text!!.length
                for (offset in listOf(pair.first, pair.second)) {
                    if (offset in 0 until len) {
                        val bLine = l.getLineForOffset(offset)
                        if (bLine in first..last) {
                            val bx = l.getPrimaryHorizontal(offset) + compoundPaddingLeft
                            val bTop = (l.getLineTop(bLine) + compoundPaddingTop).toFloat()
                            val bBottom = (l.getLineBottom(bLine) + compoundPaddingTop).toFloat()
                            canvas.drawRect(bx, bTop, bx + charW, bBottom, bracketMatchPaint)
                        }
                    }
                }
            }
            // Girinti kılavuz çizgileri (yalnızca görünür satırlarda, her 4 boşlukta bir)
            if (showIndentGuides && text != null) {
                val t = text!!
                val stepPx = charW * 4f
                for (i in first..last) {
                    if (i >= l.lineCount) break
                    val lStart = l.getLineStart(i)
                    val lEnd = l.getLineEnd(i).coerceAtMost(t.length)
                    var spaces = 0
                    var k = lStart
                    while (k < lEnd && (t[k] == ' ' || t[k] == '\t')) {
                        spaces += if (t[k] == '\t') 4 else 1
                        k++
                    }
                    val levels = spaces / 4
                    if (levels > 0) {
                        val top = (l.getLineTop(i) + compoundPaddingTop).toFloat()
                        val bottom = (l.getLineBottom(i) + compoundPaddingTop).toFloat()
                        for (lvl in 1..levels) {
                            val gx = compoundPaddingLeft + (lvl * stepPx)
                            canvas.drawLine(gx, top, gx, bottom, indentGuidePaint)
                        }
                    }
                }
            }
        }

        super.onDraw(canvas)
        if (l == null) return

        // Dalgalı alt çizgi (anlık ECJ hata/uyarıları için, yalnızca görünür satırlarda)
        if (diagnosticsByLine.isNotEmpty()) {
            for (i in first..last) {
                val diag = diagnosticsByLine[i + 1] ?: continue
                if (i >= l.lineCount) break
                val startX = compoundPaddingLeft + l.getLineLeft(i)
                val endX = (compoundPaddingLeft + l.getLineRight(i)).coerceAtLeast(startX + 24 * dp)
                val baseY = (l.getLineBaseline(i) + compoundPaddingTop + 2 * dp)
                val paint = if (diag.isError) squigglyErrorPaint else squigglyWarnPaint
                var x = startX
                val wave = 3f * dp
                var up = true
                while (x < endX) {
                    val nextX = (x + wave).coerceAtMost(endX)
                    val y1 = if (up) baseY else baseY + wave * 0.6f
                    val y2 = if (up) baseY + wave * 0.6f else baseY
                    canvas.drawLine(x, y1, nextX, y2, paint)
                    x = nextX
                    up = !up
                }
            }
        }

        // Sol satır numarası şeridi: yatay kaydırmada sabit kalır
        canvas.drawRect(sx, sy, sx + gutterWidth, sy + height, bgPaint)
        canvas.drawLine(sx + gutterWidth, sy, sx + gutterWidth, sy + height, dividerPaint)

        numPaint.textSize = textSize
        numPaint.textAlign = Paint.Align.RIGHT
        activeNumPaint.textSize = textSize
        activeNumPaint.textAlign = Paint.Align.RIGHT

        // Yalnızca görünen satırlar için satır numarası, hata simgesi ve Git diff çubuğu çiz
        for (i in first..last) {
            val baseline = (l.getLineBaseline(i) + compoundPaddingTop).toFloat()
            val paint = if (i == curLine) activeNumPaint else numPaint
            canvas.drawText((i + 1).toString(), sx + gutterWidth - 6 * dp, baseline, paint)

            val diag = diagnosticsByLine[i + 1]
            if (diag != null) {
                val dotPaint = if (diag.isError) gutterDotErrorPaint else gutterDotWarnPaint
                canvas.drawCircle(sx + 5 * dp, baseline - textSize * 0.3f, 3f * dp, dotPaint)
            }

            val diffType = gitDiffMarkers[i + 1]
            if (diffType != null) {
                gitDiffPaint.color = when (diffType) {
                    1 -> 0xFF10B981.toInt() // eklendi (yeşil)
                    2 -> 0xFF3B82F6.toInt() // değişti (mavi)
                    else -> 0xFFEF4444.toInt() // silindi (kırmızı)
                }
                val top = (l.getLineTop(i) + compoundPaddingTop).toFloat()
                val bottom = (l.getLineBottom(i) + compoundPaddingTop).toFloat()
                canvas.drawRect(sx + gutterWidth - 3 * dp, top, sx + gutterWidth, bottom, gitDiffPaint)
            }
        }
    }
}
