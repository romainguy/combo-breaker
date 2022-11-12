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
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
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
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.max

enum class FlowType(private val bits: Int) {
    Left(1),
    Right(2),
    Both(3),
    None(0);

    internal val isLeftFlow: Boolean
        get() { return (bits and 0x1) != 0 }

    internal val isRightFlow: Boolean
        get() { return (bits and 0x2) != 0 }
}

@Composable
fun TextFlow(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable TextFlowScope.() -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val fontFamilyResolver = remember { createFontFamilyResolver(context) }
    val textPaint = rememberTextPaint(fontFamilyResolver, style, density)
    val textLines = remember { mutableListOf<TextLine>() }

    val measurePolicy = MeasurePolicy { measurables, constraints ->
        val contentConstraints = if (propagateMinConstraints) {
            constraints
        } else {
            constraints.copy(minWidth = 0, minHeight = 0)
        }

        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val flowShapes = ArrayList<FlowShape>(measurables.size)

        var hasMatchParentSizeChildren = false
        var boxWidth = constraints.minWidth
        var boxHeight = constraints.minHeight
        measurables.fastForEachIndexed { index, measurable ->
            if (!measurable.matchesParentSize) {
                val placeable = measurable.measure(contentConstraints)
                placeables[index] = placeable
                boxWidth = max(boxWidth, placeable.width)
                boxHeight = max(boxHeight, placeable.height)
            } else {
                hasMatchParentSizeChildren = true
            }
        }

        if (hasMatchParentSizeChildren) {
            val matchParentSizeConstraints = Constraints(
                minWidth = if (boxWidth != Constraints.Infinity) boxWidth else 0,
                minHeight = if (boxHeight != Constraints.Infinity) boxHeight else 0,
                maxWidth = boxWidth,
                maxHeight = boxHeight
            )
            measurables.fastForEachIndexed { index, measurable ->
                if (measurable.matchesParentSize) {
                    placeables[index] = measurable.measure(matchParentSizeConstraints)
                }
            }
        }

        val boxSize = IntSize(boxWidth, boxHeight)
        layout(boxWidth, boxHeight) {
            val clip = Path().apply {
                addRect(Rect(0.0f, 0.0f, boxWidth.toFloat(), boxHeight.toFloat()))
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
                    boxSize
                )

                buildFlowShape(measurable, position, size, boxSize, clip, flowShapes, density)
            }

            layoutText(text, boxSize, textPaint, textLines, flowShapes)
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
    position: IntOffset,
    size: IntSize,
    boxSize: IntSize,
    clip: Path,
    flowShapes: ArrayList<FlowShape>,
    density: Density
) {
    val textFlowData = measurable.textFlowData ?: DefaultTextFlowParentData

    val path = Path()
    val sourcePath = textFlowData.flowShape(position, size, boxSize)
    if (sourcePath == null) {
        path.addRect(Rect(position.toOffset(), size.toSize()))
    } else {
        path.addPath(sourcePath)
    }

    val margin = with (density) { textFlowData.margin.toPx() }
    if (margin > 0.0f) {
        val androidPath = path.asAndroidPath()
        Paint().apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = margin * 2.0f
        }.getFillPath(androidPath, androidPath)
    }

    path.asAndroidPath().op(clip.asAndroidPath(), android.graphics.Path.Op.INTERSECT)

    val flowShape = FlowShape(path, textFlowData.flowType).apply { computeIntervals() }
    flowShapes += flowShape
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

typealias FlowShapeProvider = (position: IntOffset, size: IntSize, textFlowSize: IntSize) -> Path?

/**
 * A TextFlowScope provides a scope for the children of [TextFlow].
 */
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
        flowType: FlowType = FlowType.Both,
        margin: Dp = 0.dp,
        flowShape: Path? = null
    ): Modifier

    /**
     * Sets the shape used to flow text around this element.
     */
    @Stable
    fun Modifier.flowShape(
        margin: Dp = 0.dp,
        flowType: FlowType = FlowType.Both,
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
        FlowShapeModifier(margin, flowType) { _, _, _ -> flowShape }
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
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): TextFlowParentData {
        return ((parentData as? TextFlowParentData) ?: TextFlowParentData()).also {
            it.alignment = alignment
            it.matchParentSize = matchParentSize
        }
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
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): TextFlowParentData {
        return ((parentData as? TextFlowParentData) ?: TextFlowParentData()).also {
            it.margin = margin
            it.flowType = flowType
            it.flowShape = flowShape
        }
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
    var flowType: FlowType = FlowType.Both,
    var flowShape: FlowShapeProvider = { _, _, _ -> null }
)

internal val DefaultTextFlowParentData = TextFlowParentData()

@OptIn(ExperimentalContracts::class)
internal inline fun <T> List<T>.fastForEachIndexed(action: (Int, T) -> Unit) {
    contract { callsInPlace(action) }
    for (index in indices) {
        val item = get(index)
        action(index, item)
    }
}

private class TypefaceDirtyTracker(resolveResult: State<Any>) {
    val initial = resolveResult.value
    val typeface: Typeface
        get() = initial as Typeface
}
