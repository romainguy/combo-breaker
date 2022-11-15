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
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.max

enum class FlowType(private val bits: Int) {
    OutsideLeft(1),
    OutsideRight(2),
    Outside(3),
    OutsideStart(-1),
    OutsideEnd(-2),
    None(0);

    internal val isLeftFlow: Boolean
        get() { return (bits and 0x1) != 0 }

    internal val isRightFlow: Boolean
        get() { return (bits and 0x2) != 0 }

    internal fun resolve(direction: LayoutDirection) = when (direction) {
        LayoutDirection.Ltr -> this
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

@Composable
fun TextFlow(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    debugOverlay: Boolean = false,
    content: @Composable TextFlowScope.() -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val fontFamilyResolver = remember { createFontFamilyResolver(context) }
    val textPaint = rememberTextPaint(fontFamilyResolver, style, density)
    val textLines = remember { mutableListOf<TextLine>() }
    val flowShapes = remember { ArrayList<FlowShape>() }

    val debugLinePosition = remember { mutableStateOf(Float.NaN) }

    // TODO: We should remember this. Figure out the keys
    val measurePolicy = MeasurePolicy { measurables, constraints ->
        val contentConstraints = if (propagateMinConstraints) {
            constraints
        } else {
            constraints.copy(minWidth = 0, minHeight = 0)
        }

        val placeables = arrayOfNulls<Placeable>(measurables.size)

        flowShapes.clear()
        flowShapes.ensureCapacity(measurables.size)

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
                    flowShapes,
                    density,
                    layoutDirection
                )
            }

            textLines.clear()
            layoutTextFlow(text, selfSize, textPaint, textLines, flowShapes)

            // We don't need to keep all this data when the overlay isn't present
            if (!debugOverlay) flowShapes.clear()
        }
    }

    Layout(
        content = { TextFlowScopeInstance.content() },
        measurePolicy = measurePolicy,
        modifier = modifier
            .drawBehind {
                drawIntoCanvas {
                    val c = it.nativeCanvas
                    for (line in textLines) {
                        line.paint.startHyphenEdit = line.startHyphen
                        line.paint.endHyphenEdit = line.endHyphen
                        line.paint.wordSpacing = line.justifyWidth
                        c.drawText(line.text, line.start, line.end, line.x, line.y, line.paint)
                    }
                }
            }
            .thenIf(debugOverlay) {
                drawWithCache {
                    val stripeFill = stripeFill()

                    val lineHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
                    val y = debugLinePosition.value
                    val y1 = y - lineHeight / 2.0f
                    val y2 = y + lineHeight / 2.0f

                    val results = mutableListOf<Interval<PathSegment>>()
                    val slots = findFlowSlots(
                        RectF(0.0f, y1, size.width, y2),
                        flowShapes,
                        results
                    )

                    onDrawWithContent {
                        drawContent()

                        if (debugLinePosition.value.isFinite()) {
                            drawDebugInfo(y1, y2, flowShapes, results, slots, stripeFill)
                        }
                    }
                }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            debugLinePosition.value = change.position.y
                        }
                    }
            }
    )
}

@Composable
private fun rememberTextPaint(
    fontFamilyResolver: FontFamily.Resolver,
    style: TextStyle,
    density: Density
) = remember(fontFamilyResolver, style, density) {
    TextPaint().apply {
        val resolvedTypefaces: MutableList<TypefaceDirtyTracker> = mutableListOf()
        val resolveTypeface: (FontFamily?, FontWeight, FontStyle, FontSynthesis) -> Typeface =
            { fontFamily, fontWeight, fontStyle, fontSynthesis ->
                val result = fontFamilyResolver.resolve(
                    fontFamily,
                    fontWeight,
                    fontStyle,
                    fontSynthesis
                )
                val holder = TypefaceDirtyTracker(result)
                resolvedTypefaces.add(holder)
                holder.typeface
            }
        applySpanStyle(style.toSpanStyle(), resolveTypeface, density)
    }
}

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
    if (textFlowData.flowType == FlowType.None) {
        return
    }

    val position = if (textFlowData.position.isInvalid) elementPosition else textFlowData.position

    val path = Path()
    val sourcePath = textFlowData.flowShape(size, boxSize)
    if (sourcePath == null) {
        path.addRect(Rect(position, size.toSize()))
    } else {
        path.addPath(sourcePath, position)
    }

    val margin = with (density) { textFlowData.margin.toPx() }
    if (margin > 0.0f) {
        val androidPath = path.asAndroidPath()
        Paint().apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = margin * 2.0f
        }.getFillPath(androidPath, androidPath)
    }

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
        drawRect(
            color = DebugColors.SecondaryLineFill,
            topLeft = slot.toOffset(),
            size = slot.toSize()
        )
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

typealias FlowShapeProvider = (size: IntSize, textFlowSize: IntSize) -> Path?

/**
 * A TextFlowScope provides a scope for the children of [TextFlow].
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
     */
    @Stable
    fun Modifier.flowShape(
        flowType: FlowType = FlowType.Outside,
        margin: Dp = 0.dp,
        flowShape: Path? = null
    ): Modifier

    /**
     * Sets the shape used to flow text around this element.
     */
    @Stable
    fun Modifier.flowShape(
        margin: Dp = 0.dp,
        flowType: FlowType = FlowType.Outside,
        flowShape: FlowShapeProvider
    ): Modifier
}

internal object TextFlowScopeInstance : TextFlowScope {
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
        FlowShapeModifier(margin, flowType) { _, _ -> flowShape }
    )

    @Stable
    override fun Modifier.flowShape(
        margin: Dp,
        flowType: FlowType,
        flowShape: FlowShapeProvider
    ) = this.then(
        FlowShapeModifier(margin, flowType, flowShape)
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
    val margin: Dp,
    val flowType: FlowType,
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

internal data class TextFlowParentData(
    var alignment: Alignment = Alignment.TopStart,
    var matchParentSize: Boolean = false,
    var margin: Dp = 0.dp,
    var flowType: FlowType = FlowType.Outside,
    var flowShape: FlowShapeProvider = { _, _ -> null },
    var position: Offset = Offset(Float.NaN, Float.NaN)
)

internal val DefaultTextFlowParentData = TextFlowParentData()

private class TypefaceDirtyTracker(resolveResult: State<Any>) {
    val initial = resolveResult.value
    val typeface: Typeface
        get() = initial as Typeface
}

// Check for NaNs
internal inline val Offset.isInvalid get() = x != x || y != y

private object DebugColors {
    val SegmentColor = Color(0.941f, 0.384f, 0.573f, 1.0f)

    val LineBorder = Color(0.412f, 0.863f, 1.0f, 1.0f)
    val LineFill = Color(0.412f, 0.863f, 1.0f, 0.3f)

    val SecondaryLineFill = Color(1.0f, 0.945f, 0.463f, 0.5f)

    val StripeBackground = Color(0.98f, 0.98f, 0.98f)
    val StripeForeground = Color(0.94f, 0.94f, 0.94f)
}

private fun Density.stripeFill() = Brush.linearGradient(
    0.00f to DebugColors.StripeBackground, 0.25f to DebugColors.StripeBackground,
    0.25f to DebugColors.StripeForeground, 0.75f to DebugColors.StripeForeground,
    0.75f to DebugColors.StripeBackground, 1.00f to DebugColors.StripeBackground,
    start = Offset.Zero,
    end = Offset(8.dp.toPx(), 8.dp.toPx()),
    tileMode = TileMode.Repeated
)
