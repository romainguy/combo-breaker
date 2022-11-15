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
import androidx.compose.ui.unit.LayoutDirection

/**
 * Internal representation of a line of text computed by the text flow layout algorithm
 * and used to render text.
 *
 * Note the term "line" is used loosely here, as it could be just a chunk of a visual
 * line. Most of the time this will be a full line though.
 *
 * @param text The text buffer to render text from (see [start] and [end]
 * @param start The start offset in the [text] buffer
 * @param end The end offset in the [text] buffer
 * @param startHyphen The start hyphen value for this class, as expected by
 * [TextPaint.setStartHyphenEdit]
 * @param endHyphen The start hyphen value for this class, as expected by
 * [TextPaint.setStartHyphenEdit]
 * @param justifyWidth The word spacing required to justify this line of text, as expected by
 * [TextPaint.setWordSpacing]
 * @param x The x coordinate of where to draw the line of text
 * @param y The y coordinate of where to draw the line of text
 * @param paint The paint to use to render this line of text
 */
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

/**
 * Computes a layout to flow the specified text around the series of specified flow shape.
 * Each flow shape indicates on which side of the shape text should flow. The result of the
 * layout is stored in the supplied [lines] structure.
 *
 * The caller is responsible for iterating over the list of lines to render the result
 * (see [TextLine]).
 *
 * @param text The text to layout
 * @param size The size of the area where layout must occur. The resulting text will not
 * extend beyond those dimensions
 * @param columns Number of columns of text to use in the given area
 * @param columnSpacing Empty space between columns
 * @param layoutDirection The RTL or LTR direction of the layout
 * @param paint The paint to use to measure and render the text
 * @param flowShapes The list of shapes to flow text around
 * @param lines List of lines where the resulting layout will be stored
 */
internal fun layoutTextFlow(
    text: String,
    size: IntSize,
    columns: Int,
    columnSpacing: Float,
    layoutDirection: LayoutDirection,
    paint: TextPaint,
    flowShapes: ArrayList<FlowShape>,
    lines: MutableList<TextLine>
) {
    val lineBreaker = LineBreaker.Builder()
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_FULL)
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE)
        .build()
    val constraints = LineBreaker.ParagraphConstraints()

    // TODO: Do this per span/paragraph
    val lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent

    var columnCount = columns.coerceIn(1, Int.MAX_VALUE)
    var columnWidth = (size.width.toFloat() - (columns - 1) * columnSpacing) / columnCount
    while (columnWidth <= 0.0f && columnCount > 0) {
        columnCount--
        columnWidth = (size.width.toFloat() - (columns - 1) * columnSpacing) / columnCount
    }

    val ltr = layoutDirection == LayoutDirection.Ltr
    val column = RectF(
        if (ltr) 0.0f else size.width - columnWidth,
        0.0f,
        if (ltr) columnWidth else size.width.toFloat(),
        size.height.toFloat()
    )

    var currentParagraph = 0
    val paragraphs = text.split('\n')

    // Tracks the offset of the last break in the paragraph, this is where we want
    // to start reading text for our next text line
    var breakOffset = 0

    for (c in 0 until columnCount) {
        // Cursor to indicate where to draw the next line of text
        var y = 0.0f

        while (currentParagraph < paragraphs.size) {
            val paragraph = paragraphs[currentParagraph]

            // Skip empty paragraphs but advance the cursor to mark empty lines
            if (paragraph.isEmpty()) {
                y += lineHeight
                currentParagraph++
                continue
            }

            var ascent = 0.0f
            var descent = 0.0f

            var first = true

            // We want to layout text until we've run out of characters in the current paragraph,
            // or we've run out of space in the layout area
            while (breakOffset < paragraph.length && y < column.height()) {
                // The first step is to find all the slots in which we could layout text for the
                // current text line. We currently assume a line of text has a fixed height in
                // a given paragraph.
                // The result is a list of rectangles deemed appropriate to place text. These
                // rectangles might however be too small to properly place text from the current
                // line and may be skipped over later.
                val slots = findFlowSlots(
                    RectF(column.left, y, column.right, y + lineHeight),
                    flowShapes
                )

                // Position our cursor to the baseline of the next line, the first time this is
                // a no-op since ascent is set to 0. We'll fix this below
                y += ascent

                // We now need to fit as much text as possible for the current paragraph in the list
                // of slots we just computed
                for (slot in slots) {
                    // Sets the constraints width to that of our current slot
                    val x1 = slot.left
                    val x2 = slot.right
                    constraints.width = x2 - x1

                    // Skip empty slots
                    if (constraints.width <= 0) continue

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

                    // If this is true, LineBreaker tried too hard to put text in the current slot
                    // We'll first try to shrink the text and, if we can't, we'll skip the slot and
                    // move on to the next one
                    val lineTooWide = lineWidth > constraints.width

                    // Tells us how far to move the cursor for the next line of text
                    ascent = -result.getLineAscent(0)
                    descent = result.getLineDescent(0)

                    // Implement justification. We only justify when we are not laying out the last
                    // line of a paragraph (which looks horrible) or if the current line is too wide
                    // (which could be because LineBreaker entered desperate mode)
                    var justifyWidth = 0.0f
                    if (lineOffset < subtext.length || lineTooWide) {
                        // Trim end spaces as needed and figure out how many stretchable spaces we
                        // can work with to justify or fit our text in the slot
                        val hasEndHyphen = endHyphen != 0
                        val endOffset =
                            if (hasEndHyphen) lineOffset else trimEndSpace(subtext, lineOffset)
                        val stretchableSpaces = countStretchableSpaces(subtext, 0, endOffset)

                        // If we found stretchable spaces, we can attempt justification/line
                        // shrinking, otherwise, and if the line is too wide, we just bail and hope
                        // the next slot will be large enough for us to work with
                        if (stretchableSpaces != 0) {
                            var width = lineWidth

                            // When the line is too wide and we don't have a hyphen, LineBreaker was
                            // "desperate" to fit the text, so we can't use its text measurement.
                            // Instead we measure the width ourselves so we can shrink the line with
                            // negative word spacing
                            if (!lineTooWide && !hasEndHyphen) {
                                width = measuredText.getWidth(0, endOffset)
                            }

                            // Compute the amount of spacing to give to each stretchable space
                            // Can be positive (justification) or negative (line too wide)
                            justifyWidth = (constraints.width - width) / stretchableSpaces
                        } else if (lineTooWide) {
                            continue
                        }
                    }

                    // Correct ascent for the first line in the paragraph
                    if (first) {
                        y += ascent
                        first = false
                    }

                    // Enqueue a new text chunk
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

                    // Increase our current offset in side the paragraph
                    breakOffset += lineOffset

                    if (breakOffset >= paragraph.length || y >= column.height()) break
                }

                // Move the cursor to the next line
                y += descent
            }

            if (breakOffset >= paragraph.length) {
                currentParagraph++
                breakOffset = 0
            }

            // Early exit if we have more text than area
            if (y >= column.height()) break
        }

        column.offset((column.width() + columnSpacing) * if (ltr) 1.0f else -1.0f, 0.0f)
    }
}

/**
 * Indicates whether the specified character should be considered white space at the end of
 * a  line of text. These characters can be safely ignored for measurement and layout.
 */
private fun isLineEndSpace(c: Char) =
    c == ' ' || c == '\t' || c == Char(0x1680) ||
    (Char(0x2000) <= c && c <= Char(0x200A) && c != Char(0x2007)) ||
    c == Char(0x205F) || c == Char(0x3000)

/**
 * Count the number of stretchable spaces between [start] and [end] in the specified string.
 * Only the Unicode value 0x0020 qualifies, as other spaces must be used as-is (for instance
 * no-break spaces, em spaces, etc.).
 */
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

/**
 * Returns the offset of the last non-whitespace in a given string, starting from [lineEndOffset].
 */
private fun trimEndSpace(text: String, lineEndOffset: Int): Int {
    var endOffset = lineEndOffset
    while (endOffset > 0 && isLineEndSpace(text[endOffset - 1])) {
        endOffset--
    }
    return endOffset
}
