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

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.text.LineBreaker
import android.graphics.text.MeasuredText
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.core.graphics.and
import dev.romainguy.text.combobreaker.ui.theme.ComboBreakerTheme
import java.lang.Float.max
import java.lang.Float.min

//region Sample text
const val SampleText = """The correctness and coherence of the lighting environment is paramount to achieving plausible visuals. After surveying existing rendering engines (such as Unity or Unreal Engine 4) as well as the traditional real-time rendering literature, it is obvious that coherency is rarely achieved.

The Unreal Engine, for instance, lets artists specify the “brightness” of a point light in lumen, a unit of luminous power. The brightness of directional lights is however expressed using an arbitrary unnamed unit. To match the brightness of a point light with a luminous power of 5,000 lumen, the artist must use a directional light of brightness 10. This kind of mismatch makes it difficult for artists to maintain the visual integrity of a scene when adding, removing or modifying lights. Using solely arbitrary units is a coherent solution but it makes reusing lighting rigs a difficult task. For instance, an outdoor scene will use a directional light of brightness 10 as the sun and all other lights will be defined relative to that value. Moving these lights to an indoor environment would make them too bright.

Our goal is therefore to make all lighting correct by default, while giving artists enough freedom to achieve the desired look. We will support a number of lights, split in two categories, direct and indirect lighting:

Deferred rendering is used by many modern 3D rendering engines to easily support dozens, hundreds or even thousands of light source (amongst other benefits). This method is unfortunately very expensive in terms of bandwidth. With our default PBR material model, our G-buffer would use between 160 and 192 bits per pixel, which would translate directly to rather high bandwidth requirements.
Forward rendering methods on the other hand have historically been bad at handling multiple lights. A common implementation is to render the scene multiple times, once per visible light, and to blend (add) the results. Another technique consists in assigning a fixed maximum of lights to each object in the scene. This is however impractical when objects occupy a vast amount of space in the world (building, road, etc.).

Tiled shading can be applied to both forward and deferred rendering methods. The idea is to split the screen in a grid of tiles and for each tile, find the list of lights that affect the pixels within that tile. This has the advantage of reducing overdraw (in deferred rendering) and shading computations of large objects (in forward rendering). This technique suffers however from depth discontinuities issues that can lead to large amounts of extraneous work."""
//endregion

data class TextLine(val paragraph: String, val start: Int, val end: Int, val x: Float, val y: Float)

class ComboBreakerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ComboBreakerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ComboBreaker(SampleText)
                }
            }
        }
    }
}

@Composable
fun ComboBreaker(text: String) {
    val resources = LocalContext.current.resources
    val density = LocalDensity.current.density

    val bitmap1 = remember { BitmapFactory.decodeResource(resources, R.drawable.microphone) }
    var floatLeft = remember { bitmap1.toContour(alphaThreshold = 0.1f, margin = 8.0f * density).asComposePath() }

    val bitmap2 = remember { BitmapFactory.decodeResource(resources, R.drawable.badge) }
    var floatRight = remember { bitmap2.toContour(margin = 2.0f * density).asComposePath() }

    val intervalsLeft = remember { IntervalTree<PathSegment>() }
    val intervalsRight = remember { IntervalTree<PathSegment>() }

    val linePosition = remember { mutableStateOf(Float.NaN) }

    val paint = Paint().apply {
        textSize = 32.0f
    }
    val lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent

    val textLines = mutableListOf<TextLine>()

    Spacer(
        modifier = Modifier
            .onSizeChanged { size ->
                // TODO: What follows isn't really compatible with multiple onSizeChanged() invocations
                floatRight.apply {
                    translate(Offset((size.width - bitmap2.width).toFloat(), size.height / 2.0f))
                }

                val clip = Path().apply {
                    addRect(Rect(0.0f, 0.0f, size.width.toFloat(), size.height.toFloat()))
                }

                // Clips the source shapes against our work area to speed up later process
                // and to sanitize the paths (fixes complexity introduced by expansion via
                // getFillPath() with FILL_AND_STROKE, but it's an expensive step)
                floatLeft = (floatLeft.asAndroidPath() and clip.asAndroidPath()).asComposePath()
                floatRight = (floatRight.asAndroidPath() and clip.asAndroidPath()).asComposePath()

                layoutText(
                    text,
                    floatLeft,
                    floatRight,
                    lineHeight,
                    size,
                    paint,
                    intervalsLeft,
                    intervalsRight,
                    textLines
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

                val resultsLeft = mutableListOf<Interval<PathSegment>>()
                val resultsRight = mutableListOf<Interval<PathSegment>>()

                val (x1, x2) = availableSpace(
                    RectF(0.0f, y1, size.width, y2),
                    intervalsLeft,
                    intervalsRight,
                    resultsLeft,
                    resultsRight
                )

                onDrawWithContent {
                    drawImage(bitmap1.asImageBitmap())
                    drawImage(
                        bitmap2.asImageBitmap(),
                        topLeft = Offset(size.width - bitmap2.width, size.height / 2.0f)
                    )

                    if (linePosition.value.isFinite()) {
                        drawPath(floatLeft, stripeFill)
                        drawPath(floatRight, stripeFill)

                        fun drawResults(results: List<Interval<PathSegment>>) {
                            results.forEach { interval ->
                                val segment = interval.data
                                checkNotNull(segment)
                                drawLine(
                                    color = segmentColor,
                                    start = segment.start.toOffset(),
                                    end = segment.end.toOffset(),
                                    strokeWidth = 3.0f
                                )
                            }
                        }

                        drawResults(resultsLeft)
                        drawResults(resultsRight)

                        drawRect(
                            color = lineFill,
                            topLeft = Offset(0.0f, y1),
                            size = Size(x1, lineHeight)
                        )
                        drawRect(
                            color = lineFill,
                            topLeft = Offset(x2, y1),
                            size = Size(size.width - x2, lineHeight)
                        )
                        drawRect(
                            color = secondaryLineFill,
                            topLeft = Offset(x1, y1),
                            size = Size(x2 - x1, lineHeight)
                        )
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
                            c.drawText(line.paragraph, line.start, line.end, line.x, line.y, paint)
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

// Note on the "out" parameters:
// intervalsLeft, intervalsRight are only used by the caller for debugging purposes. Ideally they
// would be allocated inside this function to keep the signature more reasonable (or more likely,
// they could be merged with "lines" in a single data structure to make debugging optional)
private fun layoutText(
    text: String,
    floatLeft: Path,
    floatRight: Path,
    lineHeight: Float,
    size: IntSize,
    paint: Paint,
    intervalsLeft: IntervalTree<PathSegment>,
    intervalsRight: IntervalTree<PathSegment>,
    lines: MutableList<TextLine>
) {
    floatLeft.toIntervals(intervalsLeft)
    floatRight.toIntervals(intervalsRight)

    lines.clear()

    val lineBreaker = LineBreaker.Builder()
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE)
        .build()
    val constraints = LineBreaker.ParagraphConstraints()

    val resultsLeft = mutableListOf<Interval<PathSegment>>()
    val resultsRight = mutableListOf<Interval<PathSegment>>()

    var y = 0.0f

    // Here be dragons... This is extremely inefficient since we do (terrible) paragraph
    // breaking ourselves, and rely on TextMeasurer which forces us to creates multiple
    // new strings. TextMeasurer will also measure all the lines even though we only care
    // about the first one, leading to a complexity of O(n^2) for each paragraph, where n
    // is the number of lines. Yikes.
    text.split('\n').forEach { paragraph ->
        var breakOffset = 0

        if (paragraph.isEmpty()) y += lineHeight

        while (breakOffset < paragraph.length && y < size.height) {
            val (x1, x2) = availableSpace(
                RectF(0.0f, y, size.width.toFloat(), y + lineHeight),
                intervalsLeft,
                intervalsRight,
                resultsLeft,
                resultsRight
            )

            constraints.width = x2 - x1

            val subtext = paragraph.substring(breakOffset)
            val measuredText = MeasuredText.Builder(subtext.toCharArray())
                .appendStyleRun(paint, subtext.length, false)
                .build()
            val result = lineBreaker.computeLineBreaks(measuredText, constraints, 0)
            val lineOffset = result.getLineBreakOffset(0)

            y += -result.getLineAscent(0)
            lines.add(TextLine(paragraph, breakOffset, breakOffset + lineOffset, x1, y))
            y += result.getLineDescent(0)

            breakOffset += lineOffset
        }
    }
}

// Note: clears the results parameters
private fun availableSpace(
    box: RectF,
    intervalsLeft: IntervalTree<PathSegment>,
    intervalsRight: IntervalTree<PathSegment>,
    resultsLeft: MutableList<Interval<PathSegment>>,
    resultsRight: MutableList<Interval<PathSegment>>
): Pair<Float, Float> {
    val searchInterval = Interval<PathSegment>(box.top, box.bottom)

    resultsLeft.clear()
    intervalsLeft.findOverlaps(searchInterval, resultsLeft)

    val p1 = PointF()
    val p2 = PointF()
    val out = PointF()

    var x1 = box.left
    resultsLeft.forEach { interval ->
        val segment = interval.data
        checkNotNull(segment)

        p1.set(segment.start)
        p2.set(segment.end)

        if (clipSegment(p1, p2, box, out)) {
            x1 = max(x1, max(p1.x, p2.x))
        }
    }

    resultsRight.clear()
    intervalsRight.findOverlaps(searchInterval, resultsRight)

    var x2 = box.right
    resultsRight.forEach { interval ->
        val segment = interval.data
        checkNotNull(segment)

        p1.set(segment.start)
        p2.set(segment.end)

        if (clipSegment(p1, p2, box, out)) {
            x2 = min(x2, min(p1.x, p2.x))
        }
    }

    return Pair(x1, x2)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun PointF.toOffset() = Offset(x, y)
