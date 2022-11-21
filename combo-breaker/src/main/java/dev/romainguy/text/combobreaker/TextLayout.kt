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
import android.os.Build
import android.text.TextPaint
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max

/**
 * Internal representation of a line of text computed by the text flow layout algorithm
 * and used to render text.
 *
 * Note the term "line" is used loosely here, as it could be just a chunk of a visual
 * line. Most of the time this will be a full line though.
 *
 * @param text The text buffer to render text from (see [start] and [end].
 * @param start The start offset in the [text] buffer.
 * @param end The end offset in the [text] buffer.
 * @param startHyphen The start hyphen value for this class, as expected by
 * [TextPaint.setStartHyphenEdit].
 * @param endHyphen The start hyphen value for this class, as expected by
 * [TextPaint.setStartHyphenEdit].
 * @param justifyWidth The word spacing required to justify this line of text, as expected by
 * [TextPaint.setWordSpacing].
 * @param x The x coordinate of where to draw the line of text.
 * @param y The y coordinate of where to draw the line of text.
 * @param paint The paint to use to render this line of text.
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
 * @param text The text to layout.
 * @param size The size of the area where layout must occur. The resulting text will not
 * extend beyond those dimensions.
 * @param columns Number of columns of text to use in the given area.
 * @param columnSpacing Empty space between columns.
 * @param layoutDirection The RTL or LTR direction of the layout.
 * @param paint The paint to use to measure and render the text.
 * @param justification Sets the type of text justification.
 * @param hyphenation Sets the type of text hyphenation (only on supported API levels).
 * @param flowShapes The list of shapes to flow text around.
 * @param lines List of lines where the resulting layout will be stored.
 * @return A [TextFlowLayoutResult] giving information about how [text] was laid out.
 */
internal fun layoutTextFlow(
    text: String,
    size: IntSize,
    columns: Int,
    columnSpacing: Float,
    layoutDirection: LayoutDirection,
    paint: TextPaint,
    justification: TextFlowJustification,
    hyphenation: TextFlowHyphenation,
    flowShapes: ArrayList<FlowShape>,
    lines: MutableList<TextLine>
): TextFlowLayoutResult {
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

    val state = TextLayoutState(text, paint)

    for (c in 0 until columnCount) {
        // Cursor to indicate where to draw the next line of text
        var y = 0.0f

        while (state.hasNextParagraph) {
            val paragraph = state.currentParagraph

            // Skip empty paragraphs but advance the cursor to mark empty lines
            if (paragraph.isEmpty()) {
                y += lineHeight
                state.nextParagraph()
                continue
            }

            var ascent = 0.0f
            var descent = 0.0f

            var first = true

            // We want to layout text until we've run out of characters in the current paragraph,
            // or we've run out of space in the layout area
            while (state.isInsideParagraph && y < column.height()) {
                // The first step is to find all the slots in which we could layout text for the
                // current text line. We currently assume a line of text has a fixed height in
                // a given paragraph.
                // The result is a list of rectangles deemed appropriate to place text. These
                // rectangles might however be too small to properly place text from the current
                // line and may be skipped over later.
                val slots = findFlowSlots(
                    RectF(column.left, y, column.right, y + lineHeight),
                    RectF(0.0f, y, size.width.toFloat(), y + lineHeight),
                    flowShapes
                )

                // Position our cursor to the baseline of the next line, the first time this is
                // a no-op since ascent is set to 0. We'll fix this below
                y += ascent

                // Remember our number of "lines" to check later if we added new ones
                val lineCount = lines.size

                // We now need to fit as much text as possible for the current paragraph in the list
                // of slots we just computed
                for (slot in slots) {
                    // Sets the constraints width to that of our current slot
                    val x1 = slot.left
                    val x2 = slot.right
                    constraints.width = x2 - x1

                    // Skip empty slots
                    if (constraints.width <= 0) continue

                    if (constraints.width != state.lastSlotWidth) {
                        state.paragraphOffset = state.breakOffset
                        // We could use toCharArray() with a pre-built array and offset, but
                        // MeasuredText.Build wants styles to cover the entire array, and
                        // LineBreaker therefore expects to compute breaks over the entire array
                        val charArray = paragraph.toCharArray(state.paragraphOffset)
                        state.measuredText = MeasuredText.Builder(charArray)
                            .appendStyleRun(paint, paragraph.length - state.paragraphOffset, false)
                            .hyphenation(hyphenation)
                            .build()

                        state.result = lineBreaker.computeLineBreaks(
                            state.measuredText,
                            constraints,
                            0
                        )

                        state.lastParagraphLine = 0
                        state.lastLineOffset = 0
                    }
                    state.lastSlotWidth = slot.width()

                    val result = state.result
                    checkNotNull(result)

                    val startHyphen = result.getStartLineHyphenEdit(state.lastParagraphLine)
                    val endHyphen = result.getEndLineHyphenEdit(state.lastParagraphLine)
                    val lineOffset = result.getLineBreakOffset(state.lastParagraphLine)
                    val lineWidth = result.getLineWidth(state.lastParagraphLine)

                    // Tells us how far to move the cursor for the next line of text
                    ascent = -result.getLineAscent(state.lastParagraphLine)
                    descent = result.getLineDescent(state.lastParagraphLine)

                    // Correct ascent for the first line in the paragraph
                    if (first) {
                        y += ascent
                        first = false
                    }

                    // Don't enqueue a new line if we'd lay it out out of bounds
                    if (y  > column.height() || (y + descent) > column.height()) break

                    // Start and end offset of the line relative to the paragraph itself
                    val startOffset = state.paragraphOffset + state.lastLineOffset
                    val endOffset = state.paragraphOffset + lineOffset

                    val justifyWidth = justify(
                        state,
                        lineWidth,
                        constraints.width,
                        paragraph,
                        startOffset,
                        endOffset,
                        endHyphen,
                        justification
                    )

                    // Enqueue a new text chunk
                    lines.add(
                        TextLine(
                            paragraph,
                            startOffset,
                            endOffset,
                            startHyphen,
                            endHyphen,
                            justifyWidth,
                            x1,
                            y,
                            paint
                        )
                    )

                    state.textHeight = max(state.textHeight, y + descent)

                    state.breakOffset = state.paragraphOffset + lineOffset
                    state.lastLineOffset = lineOffset

                    state.lastParagraphLine++

                    if (!state.isInsideParagraph) break
                }

                // If we were not able to find a suitable slot and we haven't found
                // our first line yet, move y forward by the default line height
                // so we don't loop forever
                y += if (lineCount == lines.size && ascent == 0.0f && descent == 0.0f) {
                    lineHeight
                } else {
                    // Move the cursor to the next line
                    descent
                }
            }

            // Reached the end of the paragraph, move to the next one
            if (!state.isInsideParagraph) {
                state.nextParagraph()
            }

            // Early exit if we have more text than area
            if (y >= column.height()) break
        }

        column.offset((column.width() + columnSpacing) * if (ltr) 1.0f else -1.0f, 0.0f)
    }

    return TextFlowLayoutResult(state.textHeight, state.totalOffset + state.breakOffset)
}

/**
 * Implement justification. We only justify when we are not laying out the last
 * line of a paragraph (which looks horrible) or if the current line is too wide
 * (which could be because LineBreaker entered desperate mode).
 */
private fun justify(
    state: TextLayoutState,
    lineWidth: Float,
    maxWidth: Float,
    paragraph: String,
    paragraphLastLineOffset: Int,
    paragraphLineOffset: Int,
    endHyphen: Int,
    justification: TextFlowJustification
): Float {
    var justifyWidth = 0.0f

    // If this is true, LineBreaker tried too hard to put text in the current slot
    // We'll first try to shrink the text and, if we can't, we'll skip the slot and
    // move on to the next one
    val lineTooWide = lineWidth > maxWidth

    if (paragraphLineOffset < paragraph.length || lineTooWide) {
        // Trim end spaces as needed and figure out how many stretchable spaces we
        // can work with to justify or fit our text in the slot
        val hasEndHyphen = endHyphen != 0

        val endOffset = if (hasEndHyphen)
            paragraphLineOffset
        else
            trimEndSpace(paragraph, state.paragraphOffset, paragraphLineOffset)

        val stretchableSpaces = countStretchableSpaces(
            paragraph,
            paragraphLastLineOffset,
            endOffset
        )

        // If we found stretchable spaces, we can attempt justification/line
        // shrinking, otherwise, and if the line is too wide, we just bail and hope
        // the next slot will be large enough for us to work with
        if (stretchableSpaces != 0) {
            var width = lineWidth

            // When the line is too wide and we don't have a hyphen, LineBreaker was
            // "desperate" to fit the text, so we can't use its text measurement.
            // Instead we measure the width ourselves so we can shrink the line with
            // negative word spacing
            if (lineTooWide && !hasEndHyphen) {
                width = state.measuredText.getWidth(
                    paragraphLastLineOffset,
                    endOffset
                )
            }

            // Compute the amount of spacing to give to each stretchable space
            // Can be positive (justification) or negative (line too wide)
            justifyWidth = (maxWidth - width) / stretchableSpaces

            // Kill justification if the user asked to, but keep line shrinking
            // for hyphens and desperate placement
            if (justification == TextFlowJustification.None && justifyWidth > 0) {
                justifyWidth = 0.0f
            }
        } else if (lineTooWide) {
            return Float.NaN
        }
    }
    return justifyWidth
}

private class TextLayoutState(text: String, paint: TextPaint) {
    private val paragraphs = text.split('\n')

    private var _currentParagraph = 0
    inline val currentParagraph: String
        get() = paragraphs[_currentParagraph]

    // Width of the last slot we used for fitting, if we attempt to fit inside a slot of the
    // same width as last time in the same paragraph, we can skip re-measuring text and line
    // breaks to speed up layout
    var lastSlotWidth = Float.NaN

    // Last break offset in the current paragraph. This offset is relative to the beginning
    // of [subtext].
    var lastLineOffset = 0

    // Current offset inside the paragraph. Because of how LineBreaker works, we sometimes
    // re-measure the text by using a substring of the paragraph. This tells us where
    // that substring is in the paragraph. This only gets updated when we re-measure part
    // of the paragraph.
    var paragraphOffset = 0

    // Tracks the offset of the last break in the current paragraph, relative to the beginning
    // of the paragraph. This is where we want to start reading text for our next measurement.
    // This offset is updated every time we lay out a chunk of text.
    var breakOffset = 0

    // Last line we laid out with the current measure of the current paragraph.
    var lastParagraphLine = 0

    // Used to measure and break text, initialized here to avoid null checks
    var measuredText = MeasuredText.Builder(CharArray(1))
        .appendStyleRun(paint, 1, false)
        .build()
    var result: LineBreaker.Result? = null

    // For TextFlowLayoutResult
    var textHeight = 0.0f
    var totalOffset = 0

    inline val hasNextParagraph: Boolean
        get() = _currentParagraph < paragraphs.size

    inline val isInsideParagraph: Boolean
        get() = breakOffset < currentParagraph.length

    fun nextParagraph() {
        _currentParagraph++
        totalOffset += breakOffset + 1
        breakOffset = 0
        lastSlotWidth = Float.NaN
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
        if (text[i] == Char(0x0020)) {
            count++
        }
    }
    return count
}

/**
 * Returns the offset of the last non-whitespace in a given string, starting from [lineEndOffset].
 */
private fun trimEndSpace(text: String, startOffset: Int, lineEndOffset: Int): Int {
    var endOffset = lineEndOffset
    while (endOffset > startOffset && isLineEndSpace(text[endOffset - 1])) {
        endOffset--
    }
    return endOffset
}

private fun MeasuredText.Builder.hyphenation(
    hyphenation: TextFlowHyphenation
): MeasuredText.Builder {
    if (Build.VERSION.SDK_INT >= 33) {
        MeasuredTextHelper.hyphenation(this, hyphenation)
    }
    return this
}
