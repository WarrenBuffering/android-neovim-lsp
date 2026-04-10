package dev.codex.kotlinls.workspace

import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range

class LineIndex private constructor(
    private val text: String,
    private val lineStarts: IntArray,
) {
    fun offset(position: Position): Int {
        val line = position.line.coerceIn(0, lineStarts.lastIndex)
        val lineStart = lineStarts[line]
        val nextLineStart = if (line == lineStarts.lastIndex) text.length else lineStarts[line + 1]
        return (lineStart + position.character).coerceIn(lineStart, nextLineStart)
    }

    fun position(offset: Int): Position {
        val safeOffset = offset.coerceIn(0, text.length)
        var low = 0
        var high = lineStarts.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = lineStarts[mid]
            val next = if (mid == lineStarts.lastIndex) text.length + 1 else lineStarts[mid + 1]
            when {
                safeOffset < start -> high = mid - 1
                safeOffset >= next -> low = mid + 1
                else -> return Position(mid, safeOffset - start)
            }
        }
        return Position(0, safeOffset)
    }

    fun range(startOffset: Int, endOffset: Int): Range = Range(position(startOffset), position(endOffset))

    companion object {
        fun build(text: String): LineIndex {
            val starts = ArrayList<Int>()
            starts += 0
            text.forEachIndexed { index, char ->
                if (char == '\n') {
                    starts += index + 1
                }
            }
            return LineIndex(text, starts.toIntArray())
        }
    }
}

