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

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.text.LineBreaker
import android.graphics.text.MeasuredText
import android.os.Build
import android.text.TextPaint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/**
 * Internal representation of a line of text computed by the text flow layout algorithm
 * and used to render text.
 *
 * Note the term "line" is used loosely here, as it could be just a chunk of a visual
 * line. Most of the time this will be a full line though.
 *
 * @param buffer The text buffer to render text from (see [start] and [end].
 * @param start The start offset in the [buffer] buffer.
 * @param end The end offset in the [buffer] buffer.
 * @param startHyphen The start hyphen value for this class, as expected by
 * [TextPaint.setStartHyphenEdit].
 * @param endHyphen The start hyphen value for this class, as expected by
 * [TextPaint.setEndHyphenEdit].
 * @param justifyWidth The word spacing required to justify this line of text, as expected by
 * [TextPaint.setWordSpacing].
 * @param x The x coordinate of where to draw the line of text.
 * @param y The y coordinate of where to draw the line of text.
 * @param paint The paint to use to render this line of text.
 */
internal class TextSegment(
    val buffer: String,
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
 * layout is stored in the supplied [flowState] structure.
 *
 * The caller is responsible for iterating over the list of txet segments to render the result
 * (see [TextSegment]).
 *
 * @param text The text to layout.
 * @param style The default style for the text.
 * @param size The size of the area where layout must occur. The resulting text will not
 * extend beyond those dimensions.
 * @param columns Number of columns of text to use in the given area.
 * @param columnSpacing Empty space between columns.
 * @param layoutDirection The RTL or LTR direction of the layout.
 * @param justification Sets the type of text justification.
 * @param hyphenation Sets the type of text hyphenation (only on supported API levels).
 * @param flowState State of the calling TextFlow.
 * @return A [TextFlowLayoutResult] giving information about how [text] was laid out.
 */
internal fun layoutTextFlow(
    text: AnnotatedString,
    style: TextStyle,
    size: IntSize,
    columns: Int,
    columnSpacing: Float,
    layoutDirection: LayoutDirection,
    justification: TextFlowJustification,
    hyphenation: TextFlowHyphenation,
    flowState: TextFlowState
): TextFlowLayoutResult {

    val lineBreaker = LineBreaker.Builder()
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_FULL)
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE)
        .build()
    val constraints = LineBreaker.ParagraphConstraints()

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

    val state = TextLayoutState(
        text,
        style,
        flowState.resolver,
        flowState.density
    )

    val slotFinderState = FlowSlotFinderState()

    for (c in 0 until columnCount) {
        // Cursor to indicate where to draw the next line of text
        var y = 0.0f

        while (state.hasNextParagraph) {
            val paragraph = state.currentParagraph

            val firstStyle = state.currentParagraphStyles()[0].data!!
            val lineHeight = state.paintForStyle(firstStyle).lineHeight

            // Skip empty paragraphs but advance the cursor to mark empty lines
            if (paragraph.isEmpty()) {
                y += lineHeight
                state.nextParagraph()
                continue
            }

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
                    flowState.shapes,
                    slotFinderState
                )

                var ascent = 0.0f
                var descent = 0.0f

                // Remember our number of "lines" to check later if we added new ones
                val textSegmentCount = flowState.textSegments.size

                // We now need to fit as much text as possible for the current paragraph in the list
                // of slots we just computed
                for (slot in slots) {
                    // Sets the constraints width to that of our current slot
                    constraints.width = slot.right - slot.left

                    // Skip empty slots
                    if (constraints.width <= 0) continue

                    if (constraints.width != state.lastSlotWidth) {
                        state.paragraphOffset = state.breakOffset

                        // We could use toCharArray() with a pre-built array and offset, but
                        // MeasuredText.Build wants styles to cover the entire array, and
                        // LineBreaker therefore expects to compute breaks over the entire array
                        val charArray = paragraph.toCharArray(state.paragraphOffset)
                        state.measuredText = MeasuredText.Builder(charArray)
                            .appendStyleRuns(state)
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
                    val lineAscent = -result.getLineAscent(state.lastParagraphLine)
                    val lineDescent = result.getLineDescent(state.lastParagraphLine)

                    // Don't enqueue a new line if we'd lay it out out of bounds
                    if (y > column.height() || (y + lineAscent + lineDescent) > column.height()) {
                        break
                    }

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

                    // We couldn't fit our text in the available slot, try the next one
                    if (justifyWidth.isNaN()) continue

                    // Find the first merged style that intersects our text
                    var cursor = startOffset
                    var styleIndex = state.mergedStyles.indexOfFirst { cursor < it.end }

                    var x = slot.left
                    while (cursor < endOffset) {
                        val interval = state.mergedStyles[styleIndex]
                        val start = max(startOffset, interval.start.toInt())
                        val end = min(endOffset, interval.end.toInt())
                        val segmentStyle = interval.data!!

                        val paint = state.paintForStyle(segmentStyle)

                        val localStartHyphen = if (start == startOffset) {
                            startHyphen
                        } else {
                            Paint.START_HYPHEN_EDIT_NO_EDIT
                        }

                        val localEndHyphen = if (end == endOffset) {
                            endHyphen
                        } else {
                            Paint.START_HYPHEN_EDIT_NO_EDIT
                        }

                        flowState.textSegments.add(
                            TextSegment(
                                paragraph,
                                start,
                                end,
                                localStartHyphen,
                                localEndHyphen,
                                justifyWidth,
                                x,
                                y + lineAscent,
                                paint
                            )
                        )

                        cursor = end
                        styleIndex++

                        if (cursor < endOffset) {
                            if (justifyWidth != 0.0f) {
                                with(paint) {
                                    startHyphenEdit = localStartHyphen
                                    endHyphenEdit = localEndHyphen
                                    wordSpacing = justifyWidth
                                }
                                x += paint.measureText(paragraph, start, end)
                            } else {
                                x += state.measuredText.getWidth(
                                    start - state.paragraphOffset,
                                    end - state.paragraphOffset
                                )
                            }
                        }
                    }

                    ascent = max(ascent, lineAscent)
                    descent = max(descent, lineDescent)

                    state.textHeight = max(state.textHeight, y + descent)

                    state.breakOffset = state.paragraphOffset + lineOffset
                    state.lastLineOffset = lineOffset

                    state.lastParagraphLine++

                    if (!state.isInsideParagraph) break
                }

                // If we were not able to find a suitable slot and we haven't found
                // our first line yet, move y forward by the default line height
                // so we don't loop forever
                y += if (
                    textSegmentCount == flowState.textSegments.size &&
                    ascent == 0.0f &&
                    descent == 0.0f
                ) {
                    lineHeight
                } else {
                    descent + ascent
                }
            }

            // Reached the end of the paragraph, move to the next one
            if (!state.isInsideParagraph) {
                state.nextParagraph()
            }

            // Early exit if we have more text than area
            if (y >= column.height()) break
        }

        // Move to the next column
        column.offset((column.width() + columnSpacing) * if (ltr) 1.0f else -1.0f, 0.0f)
    }

    return TextFlowLayoutResult(state.textHeight, state.totalOffset + state.breakOffset)
}

/**
 * Append style runs to this [MeasuredText.Builder]. The style runs are provided by the
 * [TextLayoutState] when invoking [TextLayoutState.currentParagraphStyles].
 */
private fun MeasuredText.Builder.appendStyleRuns(
    state: TextLayoutState
): MeasuredText.Builder {

    state.currentParagraphStyles().forEach { interval ->
        if (interval.end > state.paragraphOffset) {
            val start = max(0, (interval.start - state.paragraphOffset).toInt())
            val end = (interval.end - state.paragraphOffset).toInt()
            val style = interval.data!!
            appendStyleRun(state.paintForStyle(style), end - start, false)
        }
    }

    return this
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
                width = state.measuredText.getWidth(paragraphLastLineOffset, endOffset)
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

// Comparator used to sort the results of an interval query against the styleIntervals
// tree. The tree doesn't preserve ordering and this comparator puts the intervals/ranges
// back in the order provided by AnnotatedString
private val StyleComparator = Comparator { s1: Interval<SpanStyle>, s2: Interval<SpanStyle> ->
        val start = (s1.start - s2.start).toInt()
        if (start < 0.0f) -1
        else if (start == 0) (s2.end - s1.end).toInt()
        else 1
    }

private class TextLayoutState(
    val text: AnnotatedString,
    val textStyle: TextStyle,
    private val resolver: FontFamily.Resolver,
    private val density: Density
) {
    // List of all the paragraphs in our original text
    private val paragraphs = text.split('\n')

    // Summed list of offsets: each entry corresponds to the starting offset of the corresponding
    // paragraph in the original text
    private var _paragraphStartOffsets = paragraphs.scan(0) { accumulator, element ->
        accumulator + element.length + 1
    }

    // Start offset in the original text of the current paragraph
    private inline val currentParagraphStartOffset: Int
        get() = _paragraphStartOffsets[_currentParagraph]

    // Index of the current paragraph in our list of paragraphs
    private var _currentParagraph = 0
    // Current paragraph as a String
    inline val currentParagraph: String
        get() = paragraphs[_currentParagraph]

    // Returns true if we have more paragraphs to process, false otherwise
    inline val hasNextParagraph: Boolean
        get() = _currentParagraph < paragraphs.size

    // Returns true if we can still find break points inside the current paragraph
    inline val isInsideParagraph: Boolean
        get() = breakOffset < currentParagraph.length

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

    // Interval tree of all the styles found in the source annotated string
    // This tree allows us to quickly lookup the styles for a given paragraph
    val styleIntervals = IntervalTree<SpanStyle>().apply {
        text.spanStyles.forEach {
            this += Interval(it.start.toFloat(), it.end.toFloat(), it.item)
        }
    }

    // List of merged styles for the current paragraph. Styles can overlap in the source
    // annotated string. To make lookups easier, we merge the styles ahead of time when
    // consuming a new paragraph
    val mergedStyles = ArrayList<Interval<TextStyle>>(16)
    // Temporary list used to query paragraph styles
    val stylesQuery = ArrayList<Interval<SpanStyle>>(16)

    // Cache of paints used to measurement and drawing
    val paints = mutableMapOf<TextStyle, TextPaint>()

    // Used to measure and break text, initialized here to avoid null checks
    var measuredText = MeasuredText.Builder(CharArray(1))
        .appendStyleRun(Paint(), 1, false)
        .build()
    var result: LineBreaker.Result? = null

    // For TextFlowLayoutResult
    var textHeight = 0.0f
    var totalOffset = 0

    // Moves the internal state to the next paragraph in the list
    fun nextParagraph() {
        _currentParagraph++
        mergedStyles.clear()
        totalOffset += breakOffset + 1
        breakOffset = 0
        lastSlotWidth = Float.NaN
    }

    // Returns the list of ranged styles for the current paragraph. Invoking this function
    // after calling nextParagraph() triggers a complete computation of all the merged
    // styles for the paragraph
    fun currentParagraphStyles(): ArrayList<Interval<TextStyle>> {
        // The code below guarantees that the list of merged styles always contains at least
        // 1 style. When the list is empty it means we haven't built it yet
        if (mergedStyles.isNotEmpty()) {
            return mergedStyles
        }

        val paragraph = currentParagraph
        val offset = currentParagraphStartOffset
        val fullParagraph = paragraph.isNotEmpty()

        val searchInternal = Interval<SpanStyle>(
            offset.toFloat(),
            (offset + paragraph.length).toFloat()
        )

        stylesQuery.clear()
        styleIntervals.findOverlaps(searchInternal, stylesQuery).sortedWith(StyleComparator)

        mergedStyles.add(Interval(0.0f, paragraph.length.toFloat(), textStyle))

        // This loop takes all the "spans" (ranged styles) from the annotated string and merges
        // and splits them so we are left with a continuous list of styled ranges. The resulting
        // list gives the exact style for all the offsets in a given annotated string (at least
        // for our current paragraph). This allows to trivially fetch the style at any given
        // index or range.
        val styleCount = stylesQuery.size
        for (j in 0 until styleCount) {
            val style = stylesQuery[j]
            val start = max(style.start - offset, 0.0f)
            val end = min(style.end - offset, paragraph.length.toFloat())

            if (start == end && fullParagraph) continue

            for (i in 0 until mergedStyles.size) {
                val merged = mergedStyles[i]

                if (merged.start == merged.end && fullParagraph) continue

                val styleData = style.data!!
                val mergedData = merged.data!!

                if (start <= merged.start && end >= merged.end) {
                    // The merged style is contained withing the current style
                    mergedStyles[i] = Interval(merged.start, merged.end, mergedData.merge(styleData))
                } else if (start >= merged.start && end <= merged.end) {
                    // The current style is contained withing the merged style
                    if (end != merged.end) {
                        mergedStyles.add(i + 1, Interval(end, merged.end, mergedData))
                    }
                    mergedStyles.add(i + 1, Interval(start, end, mergedData.merge(styleData)))
                    if (merged.start != start) {
                        mergedStyles[i] = Interval(merged.start, start, mergedData)
                    } else {
                        mergedStyles.removeAt(i)
                    }
                } else if (start < merged.start && end > merged.start) {
                    // The current style right-intersects with the merged style
                    mergedStyles[i] = Interval(merged.start, end, mergedData.merge(styleData))
                    mergedStyles.add(
                        i + 1,
                        Interval(start, merged.start, mergedData)
                    )
                } else if (start < merged.end && end > merged.end) {
                    // The current style left-intersects with the merged style
                    mergedStyles[i] = Interval(merged.start, start, mergedData)
                    mergedStyles.add(
                        i + 1,
                        Interval(start, merged.end, mergedData.merge(styleData))
                    )
                }
            }
        }

        return mergedStyles
    }

    // Returns a paint for the specified style. The paint is cached as needed
    fun paintForStyle(style: TextStyle) = paints.computeIfAbsent(style) {
        createTextPaint(resolver, style, density)
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
