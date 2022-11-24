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

package dev.romainguy.text.combobreaker.material3

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.romainguy.text.combobreaker.*

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

    BasicTextFlow(
        annotatedText,
        style,
        modifier,
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
    BasicTextFlow(
        text,
        style,
        modifier,
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