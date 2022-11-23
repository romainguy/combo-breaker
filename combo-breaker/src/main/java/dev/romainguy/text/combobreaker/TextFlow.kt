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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.OnPlacedModifier
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.PathSegment
import dev.romainguy.text.combobreaker.FlowType.None
import dev.romainguy.text.combobreaker.FlowType.Outside
import dev.romainguy.text.combobreaker.FlowType.OutsideEnd
import dev.romainguy.text.combobreaker.FlowType.OutsideLeft
import dev.romainguy.text.combobreaker.FlowType.OutsideRight
import dev.romainguy.text.combobreaker.FlowType.OutsideStart
import kotlin.math.max

/**
 * A [FlowType] is associated to a shape (defined by a [Path]) and describes how the text
 * should behave with respect to that shape. The following behaviors are supported:
 *
 * - [OutsideLeft]: text will flow only to the left of the shape. The shape will act as
 *   a barrier on the right, preventing any flow on that side (even if another shape
 *   permitting flow in that region is present).
 * - [OutsideRight]: text will flow only to the right of the shape. The shape will act as
 *   a barrier on the left, preventing any flow on that side (even if another shape
 *   permitting flow in that region is present).
 * - [Outside]: text will flow on both sides of the shape.
 * - [OutsideStart]: text will flow either to the left or right of the shape, depending
 *   on the layout direction. If the direction is set to [LayoutDirection.Ltr], text will
 *   flow left, right otherwise.
 * - [OutsideEnd]: text will flow either to the left or right of the shape, depending
 *   on the layout direction. If the direction is set to [LayoutDirection.Ltr], text will
 *   flow right, left otherwise.
 * - [None]: the associate shape is entirely ignored for text flow. This is useful to
 *   keep an overlay above the text for instance.
 */
enum class FlowType(private val bits: Int) {
    OutsideLeft(1),
    OutsideRight(2),
    Outside(3),
    OutsideStart(-1),
    OutsideEnd(-2),
    None(0);

    /**
     * Returns true if this type permits a left flow (including with [Outside]).
     */
    internal val isLeftFlow: Boolean
        get() { return (bits and 0x1) != 0 }

    /**
     * Returns true if this type permits a right flow (including with [Outside]).
     */
    internal val isRightFlow: Boolean
        get() { return (bits and 0x2) != 0 }

    /**
     * Returns a new [FlowType] set to either [OutsideLeft], [OutsideRight], [Outside],
     * or [None], according to this type and the specified [LayoutDirection]. The
     * return value is never one of [OutsideStart] or [OutsideEnd].
     */
    internal fun resolve(direction: LayoutDirection) = when (direction) {
        LayoutDirection.Ltr -> when (this) {
            OutsideStart -> OutsideLeft
            OutsideEnd -> OutsideRight
            else -> this
        }
        LayoutDirection.Rtl -> when (this) {
            OutsideLeft -> OutsideLeft
            OutsideRight -> OutsideRight
            Outside -> Outside
            OutsideStart -> OutsideRight
            OutsideEnd -> OutsideLeft
            None -> None
        }
    }
}

/**
 * Controls the behavior of text justification in a [TextFlow].
 */
enum class TextFlowJustification {
    /** Turns off text justification. */
    None,
    /** Turns on text justification. */
    Auto
}

/**
 * Controls the behavior of text hyphenation in a [TextFlow]. Hyphenation will only work on
 * API level 33 and above.
 */
enum class TextFlowHyphenation {
    /** No text hyphenation. */
    None,
    /** Automatic text hyphenation. */
    Auto
}

/**
 * Holds the result of a layout pass performed by [layoutTextFlow].
 *
 * @param height The total height of the text after layout.
 * @param lastOffset Offset inside the source text marking the point where the layout stopped.
 * Any text after that offset was not laid out.
 */
data class TextFlowLayoutResult(val height: Float, val lastOffset: Int)

/**
 * A layout composable with [content] that can flow [text] around the shapes defined by the
 * elements of [content].
 *
 * The specified text will be laid out inside a number of columns defined by the [columns]
 * parameter, each separated by white space defined by [columnSpacing]. All the children
 * from [content] define *flow shapes* that text will flow around. How text flows around any
 * given shape is defined by the [FlowType] of that element/shape. The flow shape and its
 * flow type can be defined for a given element by using the [TextFlowScope.flowShape] modifier.
 *
 * The default flow shape of each element is a rectangle of the same dimensions as the element
 * itself, with a flow type set to [FlowType.Outside].
 *
 * The [TextFlow] will size itself to fit the [content], subject to the incoming constraints.
 * When children are smaller than the parent, by default they will be positioned inside
 * the [TextFlow] according to the [contentAlignment]. For individually specifying the alignments
 * of the children layouts, use the [TextFlowScope.align] modifier.
 *
 * Text justification can be controlled with the [justification] parameter, but it is strongly
 * recommended to leave it on to provide balanced flow around non-rectangular shapes with a flow
 * type set to [FlowType.Outside].
 *
 * Text hyphenation can be controlled with the [hyphenation] parameter, but will only work on
 * API level that support hyphenation control (API 33+). It is also recommended to keep hyphenation
 * turned on to provide more balanced results.
 *
 * By default, the content will be measured without the [TextFlow]'s incoming min constraints,
 * unless [propagateMinConstraints] is `true`. As an example, setting [propagateMinConstraints] to
 * `true` can be useful when the [TextFlow] has content on which modifiers cannot be specified
 * directly and setting a min size on the content of the [TextFlow] is needed. If
 * [propagateMinConstraints] is set to `true`, the min size set on the [TextFlow] will also be
 * applied to the content, whereas otherwise the min size will only apply to the [TextFlow].
 *
 * When the content has more than one layout child the layout children will be stacked one
 * on top of the other (positioned as explained above) in the composition order.
 *
 * @param text The text to layout around the shapes defined by the content's elements.
 * @param modifier The modifier to be applied to the layout.
 * @param style The default text style to apply to [text].
 * @param justification Sets the type of text justification.
 * @param hyphenation Sets the type of text hyphenation (only on supported API levels).
 * @param columns The desired number of columns to layout [text] with.
 * @param columnSpacing The amount of space between two adjacent columns.
 * @param onTextFlowLayoutResult Will be invoked with information about the text layout.
 * @param contentAlignment The default alignment inside the layout.
 * @param propagateMinConstraints Whether the incoming min constraints should be passed to content.
 * @param debugOverlay Used for debugging only.
 * @param content The content of the [TextFlow]. Each element in the content defines a flow shape
 * that is taken into account to layout [text].
 */
@Composable
fun TextFlow(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    justification: TextFlowJustification = TextFlowJustification.None,
    hyphenation: TextFlowHyphenation = TextFlowHyphenation.Auto,
    columns: Int = 1,
    columnSpacing: Dp = 16.dp,
    onTextFlowLayoutResult: (result: TextFlowLayoutResult) -> Unit = { },
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    debugOverlay: Boolean = false,
    content: @Composable TextFlowScope.() -> Unit
) {
    val annotatedText by remember(text, style) {
        derivedStateOf { AnnotatedString(text, style.toSpanStyle()) }
    }

    TextFlow(
        annotatedText,
        modifier,
        style,
        justification,
        hyphenation,
        columns,
        columnSpacing,
        onTextFlowLayoutResult,
        contentAlignment,
        propagateMinConstraints,
        debugOverlay,
        content
    )
}

/**
 * A layout composable with [content] that can flow [text] around the shapes defined by the
 * elements of [content].
 *
 * The specified text will be laid out inside a number of columns defined by the [columns]
 * parameter, each separated by white space defined by [columnSpacing]. All the children
 * from [content] define *flow shapes* that text will flow around. How text flows around any
 * given shape is defined by the [FlowType] of that element/shape. The flow shape and its
 * flow type can be defined for a given element by using the [TextFlowScope.flowShape] modifier.
 *
 * The default flow shape of each element is a rectangle of the same dimensions as the element
 * itself, with a flow type set to [FlowType.Outside].
 *
 * The [TextFlow] will size itself to fit the [content], subject to the incoming constraints.
 * When children are smaller than the parent, by default they will be positioned inside
 * the [TextFlow] according to the [contentAlignment]. For individually specifying the alignments
 * of the children layouts, use the [TextFlowScope.align] modifier.
 *
 * Text justification can be controlled with the [justification] parameter, but it is strongly
 * recommended to leave it on to provide balanced flow around non-rectangular shapes with a flow
 * type set to [FlowType.Outside].
 *
 * Text hyphenation can be controlled with the [hyphenation] parameter, but will only work on
 * API level that support hyphenation control (API 33+). It is also recommended to keep hyphenation
 * turned on to provide more balanced results.
 *
 * By default, the content will be measured without the [TextFlow]'s incoming min constraints,
 * unless [propagateMinConstraints] is `true`. As an example, setting [propagateMinConstraints] to
 * `true` can be useful when the [TextFlow] has content on which modifiers cannot be specified
 * directly and setting a min size on the content of the [TextFlow] is needed. If
 * [propagateMinConstraints] is set to `true`, the min size set on the [TextFlow] will also be
 * applied to the content, whereas otherwise the min size will only apply to the [TextFlow].
 *
 * When the content has more than one layout child the layout children will be stacked one
 * on top of the other (positioned as explained above) in the composition order.
 *
 * @param text The text to layout around the shapes defined by the content's elements.
 * @param modifier The modifier to be applied to the layout.
 * @param style The default text style to apply to [text].
 * @param justification Sets the type of text justification.
 * @param hyphenation Sets the type of text hyphenation (only on supported API levels).
 * @param columns The desired number of columns to layout [text] with.
 * @param columnSpacing The amount of space between two adjacent columns.
 * @param onTextFlowLayoutResult Will be invoked with information about the text layout.
 * @param contentAlignment The default alignment inside the layout.
 * @param propagateMinConstraints Whether the incoming min constraints should be passed to content.
 * @param debugOverlay Used for debugging only.
 * @param content The content of the [TextFlow]. Each element in the content defines a flow shape
 * that is taken into account to layout [text].
 */
@Composable
fun TextFlow(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    justification: TextFlowJustification = TextFlowJustification.None,
    hyphenation: TextFlowHyphenation = TextFlowHyphenation.Auto,
    columns: Int = 1,
    columnSpacing: Dp = 16.dp,
    onTextFlowLayoutResult: (result: TextFlowLayoutResult) -> Unit = { },
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    debugOverlay: Boolean = false,
    content: @Composable TextFlowScope.() -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val state = remember {
        TextFlowSate(
            resolver = createFontFamilyResolver(context),
            density = density,
            lines = mutableListOf(),
            shapes = ArrayList()
        )
    }

    var debugLinePosition by remember { mutableStateOf(Float.NaN) }

    val measurePolicy = MeasurePolicy { measurables, constraints ->
        val contentConstraints = if (propagateMinConstraints) {
            constraints
        } else {
            constraints.copy(minWidth = 0, minHeight = 0)
        }

        val placeables = arrayOfNulls<Placeable>(measurables.size)

        state.shapes.clear()
        state.shapes.ensureCapacity(measurables.size)

        var hasMatchParentSizeChildren = false
        var selfWidth = constraints.minWidth
        var selfHeight = constraints.minHeight

        measurables.fastForEachIndexed { index, measurable ->
            if (!measurable.matchesParentSize) {
                val placeable = measurable.measure(contentConstraints)
                placeables[index] = placeable
                selfWidth = max(selfWidth, placeable.width)
                selfHeight = max(selfHeight, placeable.height)
            } else {
                hasMatchParentSizeChildren = true
            }
        }

        if (hasMatchParentSizeChildren) {
            val matchParentSizeConstraints = Constraints(
                minWidth = if (selfWidth != Constraints.Infinity) selfWidth else 0,
                minHeight = if (selfHeight != Constraints.Infinity) selfHeight else 0,
                maxWidth = selfWidth,
                maxHeight = selfHeight
            )
            measurables.fastForEachIndexed { index, measurable ->
                if (measurable.matchesParentSize) {
                    placeables[index] = measurable.measure(matchParentSizeConstraints)
                }
            }
        }

        val selfSize = IntSize(selfWidth, selfHeight)

        layout(selfWidth, selfHeight) {
            val clip = Path().apply {
                addRect(Rect(0.0f, 0.0f, selfWidth.toFloat(), selfHeight.toFloat()))
            }

            placeables.forEachIndexed { index, placeable ->
                placeable as Placeable

                val measurable = measurables[index]
                val size = IntSize(placeable.width, placeable.height)

                val position = placeElement(
                    placeable,
                    measurable,
                    size,
                    layoutDirection,
                    contentAlignment,
                    selfSize
                )

                buildFlowShape(
                    measurable,
                    position.toOffset(),
                    size,
                    selfSize,
                    clip,
                    state.shapes,
                    state.density,
                    layoutDirection
                )
            }

            state.lines.clear()

            val result = layoutTextFlow(
                text,
                style,
                selfSize,
                columns,
                columnSpacing.toPx(),
                layoutDirection,
                justification,
                hyphenation,
                state
            )

            onTextFlowLayoutResult(result)

            // We don't need to keep all this data when the overlay isn't present
            if (!debugOverlay) state.shapes.clear()
        }
    }

    Layout(
        content = { TextFlowScopeInstance.content() },
        measurePolicy = measurePolicy,
        modifier = modifier
            .drawBehind {
                drawIntoCanvas {
                    val c = it.nativeCanvas
                    for (line in state.lines) {
                        with(line) {
                            paint.startHyphenEdit = startHyphen
                            paint.endHyphenEdit = endHyphen
                            paint.wordSpacing = justifyWidth
                        }
                        c.drawText(line.text, line.start, line.end, line.x, line.y, line.paint)
                    }
                }
            }
            // Debug code
            .thenIf(debugOverlay) {
                drawWithCache {
                    val stripeFill = debugStripeFill()
                    val paint = createTextPaint(state.resolver, style, state.density)

                    val lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
                    val y = debugLinePosition
                    val y1 = y - lineHeight / 2.0f
                    val y2 = y + lineHeight / 2.0f

                    val spacing = columnSpacing.toPx()
                    var columnCount = columns.coerceIn(1, Int.MAX_VALUE)
                    var columnWidth = (size.width - (columns - 1) * spacing) / columnCount
                    while (columnWidth <= 0.0f && columnCount > 0) {
                        columnCount--
                        columnWidth = (size.width - (columns - 1) * spacing) / columnCount
                    }

                    val column = RectF(0.0f, y1, columnWidth, y2)
                    val container = RectF(0.0f, y1, size.width, y2)

                    val slots = mutableListOf<RectF>()
                    val results = mutableListOf<Interval<PathSegment>>()

                    for (c in 0 until columnCount) {
                        val columnSlots = findFlowSlots(
                            column,
                            container,
                            state.shapes,
                            results
                        )
                        slots.addAll(columnSlots)
                        column.offset(columnWidth + spacing, 0.0f)
                    }

                    onDrawWithContent {
                        drawContent()

                        if (debugLinePosition.isFinite() && y2 >= 0.0f && y1 <= size.height) {
                            drawDebugInfo(y1, y2, state.shapes, results, slots, stripeFill)
                        }
                    }
                }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            debugLinePosition = change.position.y
                        }
                    }
            }
    )
}

@Stable
internal class TextFlowSate(
    val resolver: FontFamily.Resolver,
    val density: Density,
    val lines: MutableList<TextLine>,
    val shapes: ArrayList<FlowShape>
)

private fun buildFlowShape(
    measurable: Measurable,
    elementPosition: Offset,
    size: IntSize,
    boxSize: IntSize,
    clip: Path,
    flowShapes: ArrayList<FlowShape>,
    density: Density,
    layoutDirection: LayoutDirection
) {
    val textFlowData = measurable.textFlowData ?: DefaultTextFlowParentData

    // We ignore flow shapes marked "None". We could run all the code below and it
    // would work just fine since findFlowSlots() will do the right thing, but
    // it would be expensive and wasteful so let's not do that
    if (textFlowData.flowType == None) {
        return
    }

    val position = if (textFlowData.position == Offset.Unspecified) {
        elementPosition
    } else {
        textFlowData.position
    }

    val path = Path()
    val sourcePath = textFlowData.flowShape(size, boxSize)
    if (sourcePath == null) {
        path.addRect(Rect(position, size.toSize()))
    } else {
        path.addPath(sourcePath, position)
    }

    val margin = with (density) { textFlowData.margin.toPx() }
    if (margin > 0.0f) {
        // Note: see comment below
        val androidPath = path.asAndroidPath()
        Paint().apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = margin * 2.0f
        }.getFillPath(androidPath, androidPath)
    }

    // TODO: Our layout algorithm does not need to intersect the path with the larger
    //       containment area, but this has the nice side effect of cleaning up paths tremendously
    //       when they've been expanded via getFillPath() above. This dramatically reduces the
    //       number of segments and cleans up self overlaps. It is however quite expensive so
    //       we should try to get rid of it by getting rid of getFillPath() and finding another
    //       way to create margins
    path
        .asAndroidPath()
        .op(clip.asAndroidPath(), android.graphics.Path.Op.INTERSECT)

    flowShapes += FlowShape(path, textFlowData.flowType.resolve(layoutDirection))
}

private fun Placeable.PlacementScope.placeElement(
    placeable: Placeable,
    measurable: Measurable,
    size: IntSize,
    layoutDirection: LayoutDirection,
    alignment: Alignment,
    boxSize: IntSize
): IntOffset {
    val childAlignment = measurable.textFlowData?.alignment ?: alignment
    val position = childAlignment.align(
        size,
        boxSize,
        layoutDirection
    )
    placeable.place(position)
    return position
}

private fun ContentDrawScope.drawDebugInfo(
    y1: Float,
    y2: Float,
    flowShapes: ArrayList<FlowShape>,
    intervals: MutableList<Interval<PathSegment>>,
    slots: List<RectF>,
    stripeFill: Brush
) {
    for (flowShape in flowShapes) {
        drawPath(flowShape.path, stripeFill)
    }

    intervals.forEach { interval ->
        val segment = interval.data
        if (segment != null) {
            drawLine(
                color = DebugColors.SegmentColor,
                start = segment.start.toOffset(),
                end = segment.end.toOffset(),
                strokeWidth = 3.0f
            )
        }
    }

    drawRect(
        color = DebugColors.LineFill,
        topLeft = Offset(0.0f, y1),
        size = Size(size.width, y2 - y1)
    )

    for (slot in slots) {
        if (!slot.isEmpty) {
            drawRect(
                color = DebugColors.SecondaryLineFill,
                topLeft = slot.toOffset(),
                size = slot.toSize()
            )
        }
    }

    drawLine(
        color = DebugColors.LineBorder,
        start = Offset(0.0f, y1),
        end = Offset(size.width, y1),
        strokeWidth = 3.0f
    )

    drawLine(
        color = DebugColors.LineBorder,
        start = Offset(0.0f, y2),
        end = Offset(size.width, y2),
        strokeWidth = 3.0f
    )
}

/**
 * Lambda type used by [TextFlowScope.flowShape] to compute a flow shape defined as a [Path].
 * The two parameters are:
 * - `size` The size of the element the flowShape modifier is applied to.
 * - `textFlowSize` The size of the parent [TextFlow] container.
 */
typealias FlowShapeProvider = (size: IntSize, textFlowSize: IntSize) -> Path?

/**
 * A [TextFlowScope] provides a scope for the children of [TextFlow].
 */
@LayoutScopeMarker
@Immutable
interface TextFlowScope {
    /**
     * Pull the content element to a specific [Alignment] within the [TextFlow]. This alignment will
     * have priority over the [TextFlow]'s `alignment` parameter.
     */
    @Stable
    fun Modifier.align(alignment: Alignment): Modifier

    /**
     * Size the element to match the size of the [TextFlow] after all other content elements have
     * been measured.
     *
     * The element using this modifier does not take part in defining the size of the [TextFlow].
     * Instead, it matches the size of the [TextFlow] after all other children (not using
     * matchParentSize() modifier) have been measured to obtain the [TextFlow]'s size.
     */
    @Stable
    fun Modifier.matchParentSize(): Modifier

    /**
     * Sets the shape used to flow text around this element.
     *
     * @param flowType Defines how text flows around this element, see [FlowType].
     * @param margin The extra margin to add around this element for text flow.
     * @param flowShape A [Path] defining the shape used to flow text around this element. If
     * set to null, a rectangle of the dimensions of this element will be used by default.
     */
    @Stable
    fun Modifier.flowShape(
        flowType: FlowType = Outside,
        margin: Dp = 0.dp,
        flowShape: Path? = null
    ): Modifier

    /**
     * Sets the shape used to flow text around this element. This variant of the [flowShape]
     * modifier accepts a lambda to define the shape used to flow text. That lambda receives
     * as parameters the size of this element and the size of the parent [TextFlow] to
     * facilitate the computation of an appropriate [Path].
     *
     * @param flowType Defines how text flows around this element, see [FlowType].
     * @param margin The extra margin to add around this element for text flow.
     * @param flowShape A lambda that returns a [Path] defining the shape used to flow text
     * around this element. If the returned value is null, a rectangle of the dimensions of
     * this element will be used instead.
     */
    @Stable
    fun Modifier.flowShape(
        flowType: FlowType = Outside,
        margin: Dp = 0.dp,
        flowShape: FlowShapeProvider
    ): Modifier
}

private object TextFlowScopeInstance : TextFlowScope {
    @Stable
    override fun Modifier.align(alignment: Alignment) = this.then(
        AlignmentAndSizeModifier(
            alignment = alignment,
            matchParentSize = false
        )
    )

    @Stable
    override fun Modifier.matchParentSize() = this.then(
        AlignmentAndSizeModifier(
            alignment = Alignment.Center,
            matchParentSize = true
        )
    )

    @Stable
    override fun Modifier.flowShape(flowType: FlowType, margin: Dp, flowShape: Path?) = this.then(
        FlowShapeModifier(flowType, margin) { _, _ -> flowShape }
    )

    @Stable
    override fun Modifier.flowShape(
        flowType: FlowType,
        margin: Dp,
        flowShape: FlowShapeProvider
    ) = this.then(
        FlowShapeModifier(flowType, margin, flowShape)
    )
}

private val Measurable.textFlowData: TextFlowParentData? get() = parentData as? TextFlowParentData
private val Measurable.matchesParentSize: Boolean get() = textFlowData?.matchParentSize ?: false

private class AlignmentAndSizeModifier(
    val alignment: Alignment,
    val matchParentSize: Boolean = false
) : ParentDataModifier, OnPlacedModifier {
    var localParentData: TextFlowParentData? = null

    override fun Density.modifyParentData(parentData: Any?): TextFlowParentData {
        localParentData = ((parentData as? TextFlowParentData) ?: TextFlowParentData()).also {
            it.alignment = alignment
            it.matchParentSize = matchParentSize
        }
        return localParentData!!
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        localParentData?.position = coordinates.positionInParent()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherModifier = other as? AlignmentAndSizeModifier ?: return false

        return alignment == otherModifier.alignment &&
                matchParentSize == otherModifier.matchParentSize
    }

    override fun hashCode(): Int {
        var result = alignment.hashCode()
        result = 31 * result + matchParentSize.hashCode()
        return result
    }

    override fun toString(): String =
        "AlignmentAndSizeModifier(alignment=$alignment, matchParentSize=$matchParentSize)"
}

private class FlowShapeModifier(
    val flowType: FlowType,
    val margin: Dp,
    val flowShape: FlowShapeProvider
) : ParentDataModifier, OnPlacedModifier {
    var localParentData: TextFlowParentData? = null

    override fun Density.modifyParentData(parentData: Any?): TextFlowParentData {
        localParentData = ((parentData as? TextFlowParentData) ?: TextFlowParentData()).also {
            it.margin = margin
            it.flowType = flowType
            it.flowShape = flowShape
        }
        return localParentData!!
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        localParentData?.position = coordinates.positionInParent()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FlowShapeModifier

        if (margin != other.margin) return false
        if (flowType != other.flowType) return false
        if (flowShape != other.flowShape) return false

        return true
    }

    override fun hashCode(): Int {
        var result = margin.hashCode()
        result = 31 * result + flowType.hashCode()
        result = 31 * result + flowShape.hashCode()
        return result
    }

    override fun toString(): String {
        return "FlowShapeModifier(margin=$margin, flowType=$flowType)"
    }
}

private data class TextFlowParentData(
    var alignment: Alignment = Alignment.TopStart,
    var matchParentSize: Boolean = false,
    var margin: Dp = 0.dp,
    var flowType: FlowType = Outside,
    var flowShape: FlowShapeProvider = { _, _ -> null },
    var position: Offset = Offset.Unspecified
)

private val DefaultTextFlowParentData = TextFlowParentData()

private object DebugColors {
    val SegmentColor = Color(0.941f, 0.384f, 0.573f, 1.0f)

    val LineBorder = Color(0.412f, 0.863f, 1.0f, 1.0f)
    val LineFill = Color(0.412f, 0.863f, 1.0f, 0.3f)

    val SecondaryLineFill = Color(1.0f, 0.945f, 0.463f, 0.5f)

    val StripeBackground = Color(0.98f, 0.98f, 0.98f)
    val StripeForeground = Color(0.94f, 0.94f, 0.94f)
}

private fun Density.debugStripeFill() = Brush.linearGradient(
    0.00f to DebugColors.StripeBackground, 0.25f to DebugColors.StripeBackground,
    0.25f to DebugColors.StripeForeground, 0.75f to DebugColors.StripeForeground,
    0.75f to DebugColors.StripeBackground, 1.00f to DebugColors.StripeBackground,
    start = Offset.Zero,
    end = Offset(8.dp.toPx(), 8.dp.toPx()),
    tileMode = TileMode.Repeated
)
