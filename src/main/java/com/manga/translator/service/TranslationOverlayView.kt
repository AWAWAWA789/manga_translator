package com.manga.translator.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.manga.translator.debug.DebugOverlayConfig
import com.manga.translator.debug.DebugOverlayData
import kotlin.math.max
import kotlin.math.min

class TranslationOverlayView(context: Context) : View(context) {

    companion object {
        private const val MAX_HORIZONTAL_TEXT_SP = 13f
        private const val MAX_VERTICAL_TEXT_SP = 13f
        private const val MIN_TEXT_SP = 6.5f
        private const val VERTICAL_LINE_HEIGHT = 1.12f
        private const val VERTICAL_COLUMN_WIDTH = 1.15f
    }

    data class TranslationItem(
        val originalText: String,
        val translatedText: String,
        val sourceRect: Rect,
        val isVerticalSource: Boolean
    )

    private data class PlacedItem(
        val item: TranslationItem,
        val displayText: String,
        val box: RectF,
        val textSizePx: Float,
        val verticalText: Boolean,
        val layout: StaticLayout?
    )

    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 18, 18)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 248)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 24, 24, 24)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }
    
    private val bubbleDebugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    
    private val ocrDebugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    
    private val mappingDebugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    
    private val orderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 16f * density
    }

    private val items = mutableListOf<TranslationItem>()
    private val placedItems = mutableListOf<PlacedItem>()
    private var layoutDirty = true
    
    private var debugConfig = DebugOverlayConfig()
    private var debugData = DebugOverlayData()
    
    var showMask = true

    fun setTranslations(newItems: List<TranslationItem>) {
        items.clear()
        items.addAll(newItems)
        layoutDirty = true
        invalidate()
    }

    fun clearTranslations() {
        items.clear()
        placedItems.clear()
        layoutDirty = false
        invalidate()
    }
    
    fun setDebugConfig(config: DebugOverlayConfig) {
        debugConfig = config
        invalidate()
    }
    
    fun setDebugData(data: DebugOverlayData) {
        debugData = data
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (layoutDirty) buildLayout()
        
        if (debugConfig.showBubbleRects) {
            drawDebugBubbles(canvas)
        }
        
        if (debugConfig.showOcrRects) {
            drawDebugOcrBlocks(canvas)
        }
        
        if (debugConfig.showMappingLines) {
            drawDebugMappings(canvas)
        }
        
        if (debugConfig.showReadingOrder) {
            drawDebugReadingOrder(canvas)
        }

        for (placed in placedItems) {
            if (showMask) {
                drawMask(canvas, placed)
            }
            drawCard(canvas, placed)
        }
    }
    
    private fun drawMask(canvas: Canvas, placed: PlacedItem) {
        val maskRect = RectF(placed.box)
        val radius = 8f * density
        canvas.drawRoundRect(maskRect, radius, radius, maskPaint)
    }
    
    private fun drawDebugBubbles(canvas: Canvas) {
        for (bubbleRect in debugData.bubbles) {
            canvas.drawRect(bubbleRect, bubbleDebugPaint)
        }
    }
    
    private fun drawDebugOcrBlocks(canvas: Canvas) {
        for (ocrRect in debugData.ocrBlocks) {
            canvas.drawRect(ocrRect, ocrDebugPaint)
        }
    }
    
    private fun drawDebugMappings(canvas: Canvas) {
        for ((ocrRect, bubbleRect) in debugData.mappings) {
            val startX = ocrRect.centerX().toFloat()
            val startY = ocrRect.centerY().toFloat()
            val endX = bubbleRect.centerX().toFloat()
            val endY = bubbleRect.centerY().toFloat()
            canvas.drawLine(startX, startY, endX, endY, mappingDebugPaint)
        }
    }
    
    private fun drawDebugReadingOrder(canvas: Canvas) {
        for (i in debugData.orderedBubbles.indices) {
            val bubbleRect = debugData.orderedBubbles[i]
            val text = "${i + 1}"
            canvas.drawText(text, bubbleRect.left.toFloat(), bubbleRect.top.toFloat() - 5f * density, orderTextPaint)
        }
    }

    private fun buildLayout() {
        placedItems.clear()
        layoutDirty = false
        if (width <= 0 || height <= 0 || items.isEmpty()) return

        val sorted = sortByReadingOrder(items)

        val occupied = mutableListOf<RectF>()

        for (item in sorted) {
            val text = compactTranslation(item.translatedText)
            if (text.isEmpty()) continue

            val bubbleRect = constrainDisplayRect(normalizeSourceRect(item), item.isVerticalSource)
            bubbleRect.intersect(0f, 0f, width.toFloat(), height.toFloat())

            val isVertical = item.isVerticalSource
            val placed = if (isVertical) {
                placeVerticalText(item, text, bubbleRect, occupied)
            } else {
                placeHorizontalText(item, text, bubbleRect, occupied)
            }

            if (placed != null) {
                placedItems.add(placed)
                val expanded = RectF(placed.box)
                expanded.inset(-4f * density, -4f * density)
                occupied.add(expanded)
            }
        }
    }
    
    private fun sortByReadingOrder(items: List<TranslationItem>): List<TranslationItem> {
        val rows = clusterByRows(items)
        val result = mutableListOf<TranslationItem>()
        
        for (row in rows.sortedBy { it.first }) {
            val rowItems = row.second.sortedByDescending { it.sourceRect.centerX() }
            result.addAll(rowItems)
        }
        
        return result
    }
    
    private fun clusterByRows(items: List<TranslationItem>): List<Pair<Int, List<TranslationItem>>> {
        if (items.isEmpty()) return emptyList()
        
        val sorted = items.sortedBy { it.sourceRect.centerY() }
        val rows = mutableListOf<MutableList<TranslationItem>>()
        var currentRow = mutableListOf(sorted[0])
        var currentRowY = sorted[0].sourceRect.centerY()
        
        for (i in 1 until sorted.size) {
            val item = sorted[i]
            val avgHeight = (currentRow[0].sourceRect.height() + item.sourceRect.height()) / 2f
            
            if (Math.abs(item.sourceRect.centerY() - currentRowY) < avgHeight * 0.6f) {
                currentRow.add(item)
            } else {
                rows.add(currentRow)
                currentRow = mutableListOf(item)
                currentRowY = item.sourceRect.centerY()
            }
        }
        rows.add(currentRow)
        
        return rows.map { row ->
            val avgY = row.map { it.sourceRect.centerY() }.average().toInt()
            Pair(avgY, row)
        }
    }

    private fun placeHorizontalText(item: TranslationItem, text: String, bubbleRect: RectF, occupied: List<RectF>): PlacedItem? {
        val paddingX = 5f * density
        val paddingY = 4f * density

        val availableWidth = (bubbleRect.width() - paddingX * 2).toInt()
        if (availableWidth < 30) return null

        val textSize = calculateFontSize(text, availableWidth, (bubbleRect.height() - paddingY * 2).toInt())
        textPaint.textSize = textSize
        val layout = createTextLayout(text, textSize, availableWidth)

        val cardWidth = bubbleRect.width()
        val cardHeight = bubbleRect.height()

        val centerX = bubbleRect.centerX()
        val centerY = bubbleRect.centerY()

        val box = RectF(centerX - cardWidth / 2, centerY - cardHeight / 2, centerX + cardWidth / 2, centerY + cardHeight / 2)

        val clamped = clampToScreen(nudgeAwayFromOccupied(box, occupied, false)) ?: return null
        return PlacedItem(item, text, clamped, textSize, false, layout)
    }

    private fun placeVerticalText(item: TranslationItem, text: String, bubbleRect: RectF, occupied: List<RectF>): PlacedItem? {
        val paddingX = 5f * density
        val paddingY = 4f * density

        val availableWidth = (bubbleRect.width() - paddingX * 2).toInt()
        val availableHeight = (bubbleRect.height() - paddingY * 2).toInt()
        if (availableWidth < 20 || availableHeight < 30) return null

        val textSize = calculateVerticalFontSize(text, availableWidth, availableHeight)
        val lineHeight = textSize * VERTICAL_LINE_HEIGHT
        val maxCharsPerCol = max(1, (availableHeight / lineHeight).toInt())
        val maxColumns = max(1, (availableWidth / (textSize * VERTICAL_COLUMN_WIDTH)).toInt())
        val columns = min(maxColumns, (text.length + maxCharsPerCol - 1) / maxCharsPerCol)

        val cardWidth = bubbleRect.width()
        val cardHeight = bubbleRect.height()

        val centerX = bubbleRect.centerX()
        val centerY = bubbleRect.centerY()

        val box = RectF(centerX - cardWidth / 2, centerY - cardHeight / 2, centerX + cardWidth / 2, centerY + cardHeight / 2)

        val clamped = clampToScreen(nudgeAwayFromOccupied(box, occupied, true)) ?: return null
        return PlacedItem(item, text, clamped, textSize, true, null)
    }

    private fun nudgeAwayFromOccupied(box: RectF, occupied: List<RectF>, verticalSource: Boolean): RectF {
        if (occupied.isEmpty()) return box
        val baseScore = overlapScore(box, occupied)
        if (baseScore <= 0f) return box

        val step = if (verticalSource) box.width() * 0.45f else box.height() * 0.45f
        val candidates = mutableListOf(RectF(box))
        for (i in 1..3) {
            val offset = step * i
            if (verticalSource) {
                candidates.add(RectF(box).apply { offset(-offset, 0f) })
                candidates.add(RectF(box).apply { offset(offset, 0f) })
            } else {
                candidates.add(RectF(box).apply { offset(0f, -offset) })
                candidates.add(RectF(box).apply { offset(0f, offset) })
            }
        }

        return candidates.mapNotNull { candidate -> clampToScreen(candidate) }
            .minWithOrNull(compareBy<RectF> { overlapScore(it, occupied) }.thenBy { distanceSquared(it, box) })
            ?: box
    }

    private fun overlapScore(rect: RectF, others: List<RectF>): Float {
        var score = 0f
        for (other in others) {
            val left = max(rect.left, other.left)
            val top = max(rect.top, other.top)
            val right = min(rect.right, other.right)
            val bottom = min(rect.bottom, other.bottom)
            if (left < right && top < bottom) {
                score += (right - left) * (bottom - top)
            }
        }
        return score
    }

    private fun distanceSquared(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return dx * dx + dy * dy
    }

    private fun calculateFontSize(text: String, maxWidth: Int, maxHeight: Int): Float {
        var textSize = MAX_HORIZONTAL_TEXT_SP * scaledDensity()
        val minSize = MIN_TEXT_SP * scaledDensity()

        while (textSize >= minSize) {
            textPaint.textSize = textSize
            val layout = createTextLayout(text, textSize, maxWidth)
            val totalHeight = layout.height + 12f * density
            if (totalHeight <= maxHeight || textSize <= minSize) return textSize
            textSize -= 0.5f * scaledDensity()
        }

        return minSize
    }

    private fun calculateVerticalFontSize(text: String, maxWidth: Int, maxHeight: Int): Float {
        var textSize = MAX_VERTICAL_TEXT_SP * scaledDensity()
        val minSize = MIN_TEXT_SP * scaledDensity()

        while (textSize >= minSize) {
            val lineHeight = textSize * VERTICAL_LINE_HEIGHT
            val maxCharsPerCol = max(1, (maxHeight / lineHeight).toInt())
            val requiredColumns = (text.length + maxCharsPerCol - 1) / maxCharsPerCol
            val maxColumns = max(1, (maxWidth / (textSize * VERTICAL_COLUMN_WIDTH)).toInt())
            if (requiredColumns <= maxColumns) return textSize
            textSize -= 0.5f * scaledDensity()
        }

        return minSize
    }

    private fun clampToScreen(rect: RectF): RectF? {
        var left = rect.left
        var top = rect.top
        val w = rect.width()
        val h = rect.height()

        if (w > width || h > height) return null

        if (left < 0) left = 0f
        if (top < 0) top = 0f
        if (left + w > width) left = width - w
        if (top + h > height) top = height - h

        return RectF(left, top, left + w, top + h)
    }

    private fun overlapsAny(rect: RectF, others: List<RectF>): Boolean {
        return others.any { RectF.intersects(rect, it) }
    }

    private fun createTextLayout(text: String, textSize: Float, textWidth: Int): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, max(1, textWidth))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()
    }

    private fun drawCard(canvas: Canvas, placed: PlacedItem) {
        val radius = 8f * density
        canvas.drawRoundRect(placed.box, radius, radius, bgPaint)
        canvas.drawRoundRect(placed.box, radius, radius, strokePaint)

        if (placed.verticalText) {
            drawVerticalText(canvas, placed)
        } else {
            drawHorizontalText(canvas, placed)
        }
    }

    private fun drawHorizontalText(canvas: Canvas, placed: PlacedItem) {
        val layout = placed.layout ?: return
        val paddingX = 5f * density
        val paddingY = 4f * density
        val y = placed.box.top + max(paddingY, (placed.box.height() - layout.height) / 2f)
        canvas.save()
        canvas.clipRect(placed.box)
        canvas.translate(placed.box.left + paddingX, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawVerticalText(canvas: Canvas, placed: PlacedItem) {
        val text = placed.displayText
        textPaint.textSize = placed.textSizePx
        textPaint.textAlign = Paint.Align.CENTER
        val lineHeight = placed.textSizePx * VERTICAL_LINE_HEIGHT
        val columnGap = placed.textSizePx * VERTICAL_COLUMN_WIDTH
        val paddingX = 5f * density
        val paddingY = 4f * density
        val usableWidth = placed.box.width() - paddingX * 2
        val usableHeight = placed.box.height() - paddingY * 2
        val maxCharsPerCol = max(1, (usableHeight / lineHeight).toInt())
        val maxColumns = max(1, (usableWidth / columnGap).toInt())
        val columns = min(maxColumns, (text.length + maxCharsPerCol - 1) / maxCharsPerCol)
        val drawableChars = min(text.length, columns * maxCharsPerCol)
        val totalWidth = (columns - 1) * columnGap
        val startX = placed.box.centerX() + totalWidth / 2f
        val usedRows = min(maxCharsPerCol, (drawableChars + columns - 1) / columns)
        val usedHeight = usedRows * lineHeight
        val startY = placed.box.top + max(paddingY + placed.textSizePx, (placed.box.height() - usedHeight) / 2f + placed.textSizePx)

        canvas.save()
        canvas.clipRect(placed.box)
        for (i in 0 until drawableChars) {
            val col = i / maxCharsPerCol
            val row = i % maxCharsPerCol
            val x = startX - col * columnGap
            val y = startY + row * lineHeight
            canvas.drawText(text[i].toString(), x, y, textPaint)
        }
        canvas.restore()
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun compactTranslation(text: String): String {
        return text.trim()
            .replace(Regex("\\s+"), "")
            .replace("......", "……")
            .replace("...", "…")
    }

    private fun normalizeSourceRect(item: TranslationItem): RectF {
        val rect = RectF(item.sourceRect)
        val expandX = max(3f * density, rect.width() * if (item.isVerticalSource) 0.04f else 0.04f)
        val expandY = max(3f * density, rect.height() * if (item.isVerticalSource) 0.03f else 0.05f)
        rect.inset(-expandX, -expandY)
        if (item.isVerticalSource) {
            val minWidth = 42f * density
            val minHeight = 78f * density
            if (rect.width() < minWidth) {
                val cx = rect.centerX()
                rect.left = cx - minWidth / 2f
                rect.right = cx + minWidth / 2f
            }
            if (rect.height() < minHeight) {
                val cy = rect.centerY()
                rect.top = cy - minHeight / 2f
                rect.bottom = cy + minHeight / 2f
            }
        } else {
            val minWidth = 68f * density
            val minHeight = 30f * density
            if (rect.width() < minWidth) {
                val cx = rect.centerX()
                rect.left = cx - minWidth / 2f
                rect.right = cx + minWidth / 2f
            }
            if (rect.height() < minHeight) {
                val cy = rect.centerY()
                rect.top = cy - minHeight / 2f
                rect.bottom = cy + minHeight / 2f
            }
        }
        return rect
    }

    private fun constrainDisplayRect(rect: RectF, isVertical: Boolean): RectF {
        val maxWidth = width * if (isVertical) 0.18f else 0.34f
        val maxHeight = height * if (isVertical) 0.22f else 0.16f
        val newWidth = min(rect.width(), maxWidth)
        val newHeight = min(rect.height(), maxHeight)
        val cx = rect.centerX()
        val cy = rect.centerY()
        return RectF(cx - newWidth / 2f, cy - newHeight / 2f, cx + newWidth / 2f, cy + newHeight / 2f)
    }

    private fun scaledDensity(): Float = resources.displayMetrics.scaledDensity
}


