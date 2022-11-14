/*
 * Copyright (C) 2022 Romain Guy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.romainguy.text.combobreaker

import android.graphics.RectF
import android.graphics.text.LineBreaker
import android.graphics.text.MeasuredText
import android.text.TextPaint
import androidx.compose.ui.unit.IntSize

internal class TextLine(
    val text: String,
    val start: Int,
    val end: Int,
    val startHyphen: Int,
    val endHyphen: Int,
    val justifyWidth: Float,
    val x: Float,
    val y: Float,
    val paint: TextPaint
)

internal fun layoutTextFlow(
    text: String,
    size: IntSize,
    paint: TextPaint,
    lines: MutableList<TextLine>,
    flowShapes: ArrayList<FlowShape>
) {
    val lineBreaker = LineBreaker.Builder()
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_FULL)
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE)
        .build()
    val constraints = LineBreaker.ParagraphConstraints()

    // TODO: Do this per span/paragraph
    val lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent

    var y = 0.0f

    // Here be dragons... This is extremely inefficient since we do (terrible) paragraph
    // breaking ourselves, and rely on TextMeasurer which forces us to creates multiple
    // new strings. TextMeasurer will also measure all the lines even though we only care
    // about the first one, leading to a complexity of O(n^2) for each paragraph, where n
    // is the number of lines. Yikes.
    val paragraphs = text.split('\n')
    for (paragraph in paragraphs) {
        if (paragraph.isEmpty()) {
            y += lineHeight
            continue
        }

        var breakOffset = 0
        var ascent = 0.0f
        var descent = 0.0f
        var first = true

        while (breakOffset < paragraph.length && y < size.height) {
            val slots = findFlowSlots(
                RectF(0.0f, y, size.width.toFloat(), y + lineHeight),
                flowShapes
            )

            y += ascent

            for (slot in slots) {
                val x1 = slot.left
                val x2 = slot.right
                constraints.width = x2 - x1

                val subtext = paragraph.substring(breakOffset)
                // We could use toCharArray() with a pre-built array and offset, but
                // MeasuredText.Build wants styles to cover the entire array, and
                // LineBreaker therefore expects to compute breaks over the entire array
                val measuredText = MeasuredText.Builder(subtext.toCharArray())
                    .appendStyleRun(paint, subtext.length, false)
                    .setComputeHyphenation(MeasuredText.Builder.HYPHENATION_MODE_NORMAL)
                    .build()

                val result = lineBreaker.computeLineBreaks(measuredText, constraints, 0)

                val startHyphen = result.getStartLineHyphenEdit(0)
                val endHyphen = result.getEndLineHyphenEdit(0)
                val lineOffset = result.getLineBreakOffset(0)
                val lineWidth = result.getLineWidth(0)
                val lineTooWide = lineWidth > constraints.width

                ascent = -result.getLineAscent(0)
                descent = result.getLineDescent(0)

                var justifyWidth = 0.0f
                if (lineOffset < subtext.length || lineTooWide) {
                    val hasEndHyphen = endHyphen != 0
                    val endOffset = if (hasEndHyphen) lineOffset else trimEndSpace(subtext, lineOffset)
                    val stretchableSpaces = countStretchableSpaces(subtext, 0, endOffset)

                    if (stretchableSpaces != 0) {
                        var width = lineWidth
                        if (!lineTooWide && !hasEndHyphen) {
                            width = measuredText.getWidth(0, endOffset)
                        }
                        justifyWidth = (constraints.width - width) / stretchableSpaces
                    } else if (lineTooWide) {
                        continue
                    }
                }

                if (first) {
                    y += ascent
                    first = false
                }

                lines.add(
                    TextLine(
                        paragraph,
                        breakOffset,
                        breakOffset + lineOffset,
                        startHyphen,
                        endHyphen,
                        justifyWidth,
                        x1,
                        y,
                        paint
                    )
                )

                breakOffset += lineOffset

                if (breakOffset >= paragraph.length || y >= size.height) break
            }

            y += descent
        }

        if (y >= size.height) break
    }
}

private fun isLineEndSpace(c: Char) =
    c == ' ' || c == '\t' || c == Char(0x1680) ||
    (Char(0x2000) <= c && c <= Char(0x200A) && c != Char(0x2007)) ||
    c == Char(0x205F) || c == Char(0x3000)

@Suppress("SameParameterValue")
private fun countStretchableSpaces(text: String, start: Int, end: Int): Int {
    var count = 0
    for (i in start until end) {
        if (text[i + start] == Char(0x0020)) {
            count++
        }
    }
    return count
}

private fun trimEndSpace(text: String, lineEndOffset: Int): Int {
    var endOffset = lineEndOffset
    while (endOffset > 0 && isLineEndSpace(text[endOffset - 1])) {
        endOffset--
    }
    return endOffset
}
