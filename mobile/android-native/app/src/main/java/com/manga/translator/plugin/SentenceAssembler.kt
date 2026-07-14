package com.manga.translator.plugin

import android.graphics.Rect
import com.manga.translator.model.OcrBlock
import kotlin.math.abs

class SentenceAssembler {

    fun assemble(blocks: List<OcrBlock>): List<List<OcrBlock>> {
        val reliable = blocks.filter { isReliableBlock(it) }
        if (reliable.isEmpty()) return emptyList()

        val vertical = reliable.filter { it.isVertical }
        val horizontal = reliable.filter { !it.isVertical }

        return (assembleVertical(vertical) + assembleHorizontal(horizontal))
            .filter { isSentenceLike(it) }
    }

    private fun assembleVertical(blocks: List<OcrBlock>): List<List<OcrBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val columns = mutableListOf<MutableList<OcrBlock>>()
        val sorted = blocks.sortedByDescending { it.rect.centerX() }

        for (block in sorted) {
            val target = columns.minByOrNull { column ->
                abs(columnCenterX(column) - block.rect.centerX())
            }?.takeIf { column ->
                abs(columnCenterX(column) - block.rect.centerX()) < averageWidth(column) * 1.85f
            }
            if (target != null) target.add(block) else columns.add(mutableListOf(block))
        }

        return columns.flatMap { column ->
            splitVerticalColumn(column.sortedBy { it.rect.top })
        }
    }

    private fun assembleHorizontal(blocks: List<OcrBlock>): List<List<OcrBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val rows = mutableListOf<MutableList<OcrBlock>>()
        val sorted = blocks.sortedBy { it.rect.centerY() }

        for (block in sorted) {
            val target = rows.minByOrNull { row ->
                abs(rowCenterY(row) - block.rect.centerY())
            }?.takeIf { row ->
                abs(rowCenterY(row) - block.rect.centerY()) < averageHeight(row) * 0.8f
            }
            if (target != null) target.add(block) else rows.add(mutableListOf(block))
        }

        return rows.flatMap { row ->
            splitHorizontalRow(row.sortedBy { it.rect.left })
        }
    }

    private fun splitVerticalColumn(column: List<OcrBlock>): List<List<OcrBlock>> {
        val sentences = mutableListOf<MutableList<OcrBlock>>()
        var current = mutableListOf<OcrBlock>()
        for (block in column) {
            val previous = current.lastOrNull()
            if (previous == null || canJoinVertical(previous, block, current)) {
                current.add(block)
            } else {
                sentences.add(current)
                current = mutableListOf(block)
            }
        }
        if (current.isNotEmpty()) sentences.add(current)
        return sentences
    }

    private fun splitHorizontalRow(row: List<OcrBlock>): List<List<OcrBlock>> {
        val sentences = mutableListOf<MutableList<OcrBlock>>()
        var current = mutableListOf<OcrBlock>()
        for (block in row) {
            val previous = current.lastOrNull()
            if (previous == null || canJoinHorizontal(previous, block, current)) {
                current.add(block)
            } else {
                sentences.add(current)
                current = mutableListOf(block)
            }
        }
        if (current.isNotEmpty()) sentences.add(current)
        return sentences
    }

    private fun canJoinVertical(previous: OcrBlock, next: OcrBlock, current: List<OcrBlock>): Boolean {
        if (endsSentence(previous.text)) return false
        val groupRect = unionRects(current.map { it.rect })
        val avgHeight = current.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)
        val avgWidth = current.map { it.rect.width() }.average().toFloat().coerceAtLeast(1f)
        val gap = next.rect.top - previous.rect.bottom
        if (gap < -avgHeight * 0.35f || gap > avgHeight * 2.0f) return false
        if (abs(next.rect.centerX() - groupRect.centerX()) > maxOf(avgWidth * 1.0f, groupRect.width() * 0.35f)) return false
        if (groupRect.height() > avgHeight * 8f) return false
        val mergedTextLength = current.sumOf { it.text.length } + next.text.length
        if (mergedTextLength > 46) return false
        return shouldContinueJapanese(previous.text, next.text) || gap < avgHeight * 0.9f
    }

    private fun canJoinHorizontal(previous: OcrBlock, next: OcrBlock, current: List<OcrBlock>): Boolean {
        if (endsSentence(previous.text)) return false
        val groupRect = unionRects(current.map { it.rect })
        val avgHeight = current.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)
        val avgWidth = current.map { it.rect.width() }.average().toFloat().coerceAtLeast(1f)
        val gap = next.rect.left - previous.rect.right
        if (gap < -avgHeight * 0.35f || gap > avgHeight * 2.0f) return false
        if (abs(next.rect.centerY() - groupRect.centerY()) > maxOf(avgHeight * 1.0f, groupRect.height() * 0.35f)) return false
        if (groupRect.width() > avgWidth * 8f) return false
        val mergedTextLength = current.sumOf { it.text.length } + next.text.length
        if (mergedTextLength > 42) return false
        return shouldContinueJapanese(previous.text, next.text) || gap < avgHeight * 0.9f
    }

    private fun isReliableBlock(block: OcrBlock): Boolean {
        val text = block.text.trim()
        if (text.length < 2) return false
        if (block.confidence < 0.40f && text.length <= 4) return false
        if (block.confidence < 0.25f) return false
        if (text.count { it == '|' || it == '*' || it == '#' } >= 2) return false
        if (text.count { it.isDigit() } >= 2 && text.length <= 6) return false
        if (!containsJapaneseLikeText(text)) return false
        return true
    }

    private fun isSentenceLike(blocks: List<OcrBlock>): Boolean {
        val text = blocks.joinToString("") { it.text.trim() }
        if (text.length >= 4) return true
        if (blocks.size >= 2 && text.length >= 3) return true
        if (endsSentence(text) && text.length >= 2) return true
        if (text.length >= 3 && containsOnlyJapaneseLike(text)) return true
        return false
    }

    private fun containsOnlyJapaneseLike(text: String): Boolean {
        return text.all { char ->
            val code = char.code
            code in 0x3040..0x309F || code in 0x30A0..0x30FF || code in 0x4E00..0x9FFF
        }
    }

    private fun containsJapaneseLikeText(text: String): Boolean {
        return text.any { char ->
            val code = char.code
            code in 0x3040..0x309F || code in 0x30A0..0x30FF || code in 0x4E00..0x9FFF
        }
    }

    private fun endsSentence(text: String): Boolean {
        return text.trim().lastOrNull() in setOf('。', '！', '？', '!', '?', '…')
    }

    private fun shouldContinueJapanese(previous: String, next: String): Boolean {
        val prev = previous.trim()
        val n = next.trim()
        if (prev.isEmpty() || n.isEmpty()) return false
        val continuationEndings = listOf("は", "が", "を", "に", "へ", "と", "で", "の", "も", "や", "から", "まで", "より", "て", "で")
        val continuationStarts = listOf("の", "に", "を", "は", "が", "で", "と", "も", "から", "だ", "です", "ます", "ない", "た", "て")
        return continuationEndings.any { prev.endsWith(it) } || continuationStarts.any { n.startsWith(it) }
    }

    private fun columnCenterX(blocks: List<OcrBlock>): Float = blocks.map { it.rect.centerX() }.average().toFloat()
    private fun rowCenterY(blocks: List<OcrBlock>): Float = blocks.map { it.rect.centerY() }.average().toFloat()
    private fun averageWidth(blocks: List<OcrBlock>): Float = blocks.map { it.rect.width() }.average().toFloat().coerceAtLeast(1f)
    private fun averageHeight(blocks: List<OcrBlock>): Float = blocks.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)

    private fun unionRects(rects: List<Rect>): Rect {
        val union = Rect(rects.first())
        for (i in 1 until rects.size) union.union(rects[i])
        return union
    }
}
