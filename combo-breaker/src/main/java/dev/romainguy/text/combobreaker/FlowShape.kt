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

import androidx.compose.ui.graphics.Path

/**
 * Internal representation of a "flow shape". A flow shape is a [Path] contour from which
 * an interval tree is extracted. The intervals inside that tree are extracted by first flattening
 * the path as a series of segments. By taking the vertical interval of each segment, we can
 * build an interval tree that allows for fast queries when trying to layout a line of text.
 */
internal class FlowShape(
    val path: Path,
    val flowType: FlowType,
) {
    internal val intervals = path.toIntervals()
    internal val bounds = path.getBounds()

    // These fields are read-write and used during layout to hold temporary information
    internal var min = Float.POSITIVE_INFINITY
    internal var max = Float.NEGATIVE_INFINITY
}
