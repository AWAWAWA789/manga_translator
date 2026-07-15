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
        val isVerticalSource: Boolean,
    )

    private data class PlacedItem(
        val item: TranslationItem,
        val displayText: String,
        val box: RectF,
        val textSizePx: Float,
        val verticalText: Boolean,
        val layout: StaticLayout?,
    )

    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 20)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 252, 252, 255)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 100, 100, 120)
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 100, 149, 237)
        style = Paint.Style.FILL
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 0, 0, 0)
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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    // 文本字号缓存：key 为 "text|maxWidth|maxHeight"，避免每次 buildLayout 都重新二分查找
    // buildLayout 在 onDraw 中触发（主线程），16 个 item × 13 次二分 = 208 次 StaticLayout 创建
    // 命中缓存时只需 1 次校验，未命中才回退到二分
    private val textSizeCache = mutableMapOf<String, Float>()

    private var debugConfig = DebugOverlayConfig()
    private var debugData = DebugOverlayData()

    var showMask = true

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // View 被 remove 时取消所有动画，防止无效绘制
        animate().cancel()
        clearRunnable?.let { handler.removeCallbacks(it) }
        clearRunnable = null
    }

    fun setTranslations(newItems: List<TranslationItem>) {
        animate().cancel()
        clearRunnable?.let { handler.removeCallbacks(it) }
        clearRunnable = null
        val wasEmpty = items.isEmpty()
        items.clear()
        items.addAll(newItems)
        layoutDirty = true
        if (wasEmpty) {
            alpha = 0f
            animate().alpha(1f).setDuration(250).setInterpolator(
                android.view.animation.DecelerateInterpolator(),
            ).start()
        } else {
            // clearTranslations 设置 alpha=0 后又紧接着调用 setTranslations 时，
            // wasEmpty 为 false（items 仍持有旧数据）不会触发淡入动画，
            // 需要确保 alpha 恢复为 1f，否则会卡在 0
            alpha = 1f
        }
        invalidate()
    }

    fun clearTranslations() {
        animate().cancel()
        clearRunnable?.let { handler.removeCallbacks(it) }
        alpha = 0f
        clearRunnable = Runnable {
            items.clear()
            placedItems.clear()
            layoutDirty = false
            invalidate()
        }
        handler.postDelayed(clearRunnable!!, 160)
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
        // 步长从 3 增加到 5，覆盖更拥挤的场景
        for (i in 1..5) {
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
        val maxSize = MAX_HORIZONTAL_TEXT_SP * scaledDensity()
        val minSize = MIN_TEXT_SP * scaledDensity()

        // 缓存命中：先用上次的字号尝试一次，避免重新二分
        val cacheKey = "$text|$maxWidth|$maxHeight"
        textSizeCache[cacheKey]?.let { cached ->
            if (cached in minSize..maxSize) {
                textPaint.textSize = cached
                val cachedLayout = createTextLayout(text, cached, maxWidth)
                val cachedTotalHeight = cachedLayout.height + 12f * density
                if (cachedTotalHeight <= maxHeight) {
                    return cached
                }
            }
        }

        // 二分查找替代线性 0.5sp 步长，将 StaticLayout 构建次数从 ~13 次降到 ~5 次
        var lo = minSize
        var hi = maxSize
        var best = minSize
        while (lo <= hi) {
            val mid = (lo + hi) / 2f
            textPaint.textSize = mid
            val layout = createTextLayout(text, mid, maxWidth)
            val totalHeight = layout.height + 12f * density
            if (totalHeight <= maxHeight) {
                best = mid
                lo = mid + 0.5f * scaledDensity()
            } else {
                hi = mid - 0.5f * scaledDensity()
            }
        }
        textSizeCache[cacheKey] = best
        return best
    }

    private fun calculateVerticalFontSize(text: String, maxWidth: Int, maxHeight: Int): Float {
        val maxSize = MAX_VERTICAL_TEXT_SP * scaledDensity()
        val minSize = MIN_TEXT_SP * scaledDensity()
        // 二分查找：找到最大的能放下的字号
        var lo = minSize
        var hi = maxSize
        var best = minSize
        while (lo <= hi) {
            val mid = (lo + hi) / 2f
            val lineHeight = mid * VERTICAL_LINE_HEIGHT
            val maxCharsPerCol = max(1, (maxHeight / lineHeight).toInt())
            val requiredColumns = (text.length + maxCharsPerCol - 1) / maxCharsPerCol
            val maxColumns = max(1, (maxWidth / (mid * VERTICAL_COLUMN_WIDTH)).toInt())
            if (requiredColumns <= maxColumns) {
                best = mid
                lo = mid + 0.5f * scaledDensity()
            } else {
                hi = mid - 0.5f * scaledDensity()
            }
        }
        return best
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
        // 阴影
        val shadowBox = RectF(placed.box).apply { offset(0f, 1.5f * density) }
        canvas.drawRoundRect(shadowBox, radius, radius, shadowPaint)
        // 背景
        canvas.drawRoundRect(placed.box, radius, radius, bgPaint)
        // 左侧彩色竖条
        val accentW = 3f * density
        val accentRect = RectF(placed.box.left, placed.box.top + radius, placed.box.left + accentW, placed.box.bottom - radius)
        canvas.drawRect(accentRect, accentPaint)
        // 边框
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
            // 仅合并连续多空格为单空格，保留英文与中文之间的分隔
            // 避免把 "OK 好的" 压成 "OK好的" 导致英文混排粘连
            .replace(Regex("\\s{2,}"), " ")
            .replace("......", "……")
            .replace("...", "…")
    }

    private fun normalizeSourceRect(item: TranslationItem): RectF {
        val rect = RectF(item.sourceRect)
        // 横竖排统一使用 0.04f 横向扩展比例，原 if/else 两分支返回相同值
        val expandX = max(3f * density, rect.width() * 0.04f)
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
