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

package dev.romainguy.text.combobreaker.demo

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.text.LineBreaker
import android.graphics.text.MeasuredText
import android.os.Bundle
import android.text.TextPaint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathSegment
import dev.romainguy.text.combobreaker.FlowShape
import dev.romainguy.text.combobreaker.FlowType
import dev.romainguy.text.combobreaker.Interval
import dev.romainguy.text.combobreaker.findFlowSlots
import dev.romainguy.text.combobreaker.demo.ui.theme.ComboBreakerTheme
import dev.romainguy.text.combobreaker.toContour

//region Sample text
const val SampleText = """The correctness and coherence of the lighting environment is paramount to achieving plausible visuals. After surveying existing rendering engines (such as Unity or Unreal Engine 4) as well as the traditional real-time rendering literature, it is obvious that coherency is rarely achieved.

The Unreal Engine, for instance, lets artists specify the “brightness” of a point light in lumen, a unit of luminous power. The brightness of directional lights is however expressed using an arbitrary unnamed unit. To match the brightness of a point light with a luminous power of 5,000 lumen, the artist must use a directional light of brightness 10. This kind of mismatch makes it difficult for artists to maintain the visual integrity of a scene when adding, removing or modifying lights. Using solely arbitrary units is a coherent solution but it makes reusing lighting rigs a difficult task. For instance, an outdoor scene will use a directional light of brightness 10 as the sun and all other lights will be defined relative to that value. Moving these lights to an indoor environment would make them too bright.

Our goal is therefore to make all lighting correct by default, while giving artists enough freedom to achieve the desired look. We will support a number of lights, split in two categories, direct and indirect lighting:

Deferred rendering is used by many modern 3D rendering engines to easily support dozens, hundreds or even thousands of light source (amongst other benefits). This method is unfortunately very expensive in terms of bandwidth. With our default PBR material model, our G-buffer would use between 160 and 192 bits per pixel, which would translate directly to rather high bandwidth requirements. Forward rendering methods on the other hand have historically been bad at handling multiple lights. A common implementation is to render the scene multiple times, once per visible light, and to blend (add) the results. Another technique consists in assigning a fixed maximum of lights to each object in the scene. This is however impractical when objects occupy a vast amount of space in the world (building, road, etc.).

Tiled shading can be applied to both forward and deferred rendering methods."""
//endregion

class TextLine(
    val text: String,
    val start: Int,
    val end: Int,
    val startHyphen: Int,
    val endHyphen: Int,
    val justifyWidth: Float,
    val x: Float,
    val y: Float
)

class ComboBreakerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ComboBreakerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ComboBreaker(
                        Modifier.padding(16.dp),
                        SampleText
                    )
                }
            }
        }
    }
}

@Composable
fun ComboBreaker(
    modifier: Modifier,
    text: String
) {
    val resources = LocalContext.current.resources
    val density = LocalDensity.current.density

    val bitmap1 = remember { BitmapFactory.decodeResource(resources, R.drawable.microphone) }
    val bitmap2 = remember { BitmapFactory.decodeResource(resources, R.drawable.badge) }
    val flowShapes = remember {
        listOf(
            FlowShape(
                bitmap1.toContour(alphaThreshold = 0.1f, margin = 10.0f * density).asComposePath(),
                FlowType.Right
            ),
            FlowShape(
                bitmap2.toContour(margin = 8.0f * density).asComposePath(),
                FlowType.Both
            )
        )
    }

    val linePosition = remember { mutableStateOf(Float.NaN) }
    val paint = TextPaint().apply { textSize = 32.0f }
    val lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
    val textLines = mutableListOf<TextLine>()

    Spacer(
        modifier = modifier
            .onSizeChanged { size ->
                // TODO: What follows isn't really compatible with multiple onSizeChanged() invocations
                flowShapes[0].path.apply {
                    translate(Offset(-bitmap1.width / 4.5f, 0.0f))
                }
                flowShapes[1].path.apply {
                    translate(
                        Offset(
                            (size.width - bitmap2.width).toFloat() / 2.0f,
                            size.height / 1.9f
                        )
                    )
                }

                val clip = Path().apply {
                    addRect(Rect(0.0f, 0.0f, size.width.toFloat(), size.height.toFloat()))
                }

                // Clips the source shapes against our work area to speed up later process
                // and to sanitize the paths (fixes complexity introduced by expansion via
                // getFillPath() with FILL_AND_STROKE, but it's an expensive step)
                for (flowShape in flowShapes) {
                    flowShape
                        .path
                        .asAndroidPath()
                        .op(clip.asAndroidPath(), android.graphics.Path.Op.INTERSECT)
                    // TODO: Should be internal inside the combo-breaker module
                    flowShape.computeIntervals()
                }

                layoutText(
                    text,
                    lineHeight,
                    size,
                    paint,
                    textLines,
                    flowShapes
                )
            }
            .drawWithCache {
                val segmentColor = Color(0.941f, 0.384f, 0.573f, 1.0f)

                val lineBorder = Color(0.412f, 0.863f, 1.0f, 1.0f)
                val lineFill = Color(0.412f, 0.863f, 1.0f, 0.3f)

                val secondaryLineFill = Color(1.0f, 0.945f, 0.463f, 0.5f)

                val stripeBackground = Color(0.98f, 0.98f, 0.98f)
                val stripeForeground = Color(0.94f, 0.94f, 0.94f)

                val stripeFill = Brush.linearGradient(
                    0.00f to stripeBackground, 0.25f to stripeBackground,
                    0.25f to stripeForeground, 0.75f to stripeForeground,
                    0.75f to stripeBackground, 1.00f to stripeBackground,
                    start = Offset.Zero,
                    end = Offset(8.dp.toPx(), 8.dp.toPx()),
                    tileMode = TileMode.Repeated
                )

                val y = linePosition.value
                val y1 = y - lineHeight / 2.0f
                val y2 = y + lineHeight / 2.0f

                val results = mutableListOf<Interval<PathSegment>>()
                val slots = findFlowSlots(
                    RectF(0.0f, y1, size.width, y2),
                    flowShapes,
                    results
                )

                onDrawWithContent {
                    drawImage(
                        bitmap1.asImageBitmap(),
                        topLeft = Offset(-bitmap1.width / 4.5f, 0.0f)
                    )
                    drawImage(
                        bitmap2.asImageBitmap(),
                        topLeft = Offset((size.width - bitmap2.width) / 2.0f, size.height / 1.9f)
                    )

                    if (linePosition.value.isFinite()) {
                        for (flowShape in flowShapes) {
                            drawPath(flowShape.path, stripeFill)
                        }

                        fun drawResults(results: List<Interval<PathSegment>>) {
                            results.forEach { interval ->
                                val segment = interval.data
                                if (segment != null) {
                                    drawLine(
                                        color = segmentColor,
                                        start = segment.start.toOffset(),
                                        end = segment.end.toOffset(),
                                        strokeWidth = 3.0f
                                    )
                                }
                            }
                        }

                        drawResults(results)

                        drawRect(
                            color = lineFill,
                            topLeft = Offset(0.0f, y1),
                            size = Size(size.width, lineHeight)
                        )

                        for (slot in slots) {
                            drawRect(
                                color = secondaryLineFill,
                                topLeft = slot.toOffset(),
                                size = slot.toSize()
                            )
                        }

                        drawLine(
                            color = lineBorder,
                            start = Offset(0.0f, y1),
                            end = Offset(size.width, y1),
                            strokeWidth = 3.0f
                        )
                        drawLine(
                            color = lineBorder,
                            start = Offset(0.0f, y2),
                            end = Offset(size.width, y2),
                            strokeWidth = 3.0f
                        )
                    }

                    drawIntoCanvas { canvas ->
                        val c = canvas.nativeCanvas
                        for (line in textLines) {
                            paint.startHyphenEdit = line.startHyphen
                            paint.endHyphenEdit = line.endHyphen
                            paint.wordSpacing = line.justifyWidth
                            c.drawText(line.text, line.start, line.end, line.x, line.y, paint)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    linePosition.value = change.position.y
                }
            }
    )
}

private fun layoutText(
    text: String,
    lineHeight: Float,
    size: IntSize,
    paint: Paint,
    lines: MutableList<TextLine>,
    flowShapes: List<FlowShape>
) {
    lines.clear()

    val lineBreaker = LineBreaker.Builder()
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_FULL)
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE)
        .build()
    val constraints = LineBreaker.ParagraphConstraints()

    val results = mutableListOf<Interval<PathSegment>>()

    var y = 0.0f

    // Here be dragons... This is extremely inefficient since we do (terrible) paragraph
    // breaking ourselves, and rely on TextMeasurer which forces us to creates multiple
    // new strings. TextMeasurer will also measure all the lines even though we only care
    // about the first one, leading to a complexity of O(n^2) for each paragraph, where n
    // is the number of lines. Yikes.
    text.split('\n').forEach { paragraph ->
        var breakOffset = 0

        if (paragraph.isEmpty()) {
            y += lineHeight
            return@forEach
        }

        var ascent = 0.0f
        var descent = 0.0f
        var first = true

        while (breakOffset < paragraph.length && y < size.height) {
            val slots = findFlowSlots(
                RectF(0.0f, y, size.width.toFloat(), y + lineHeight),
                flowShapes,
                results
            )

            y += ascent

            for (slot in slots) {
                val x1 = slot.left
                val x2 = slot.right
                constraints.width = x2 - x1
                constraints.setIndent(x2 - x1, Integer.MAX_VALUE)

                val subtext = paragraph.substring(breakOffset)
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

                ascent = -result.getLineAscent(0)
                descent = result.getLineDescent(0)

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
                        y
                    )
                )

                breakOffset += lineOffset

                if (breakOffset >= paragraph.length || y >= size.height) break
            }

            y += descent
        }
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

@Suppress("NOTHING_TO_INLINE")
internal inline fun PointF.toOffset() = Offset(x, y)

@Suppress("NOTHING_TO_INLINE")
internal inline fun RectF.toOffset() = Offset(left, top)

@Suppress("NOTHING_TO_INLINE")
internal inline fun RectF.toSize() = Size(width(), height())
