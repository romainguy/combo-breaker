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
import androidx.core.graphics.PathSegment

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

class FlowShape(val path: Path, val flowType: FlowType = FlowType.Both) {
    internal val intervals = IntervalTree<PathSegment>()

    // TODO: Find a better way; the caller shouldn't have to invoke this
    // TODO: At least, make it internal
    fun computeIntervals() {
        intervals.clear()
        path.toIntervals(intervals)
    }
}
