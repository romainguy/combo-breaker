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

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.core.graphics.PathSegment
import androidx.core.graphics.flatten
import kotlin.math.max
import kotlin.math.min

/**
 * Returns an [IntervalTree] for this path. Each [Interval] in that tree wraps a path segment
 * generated from flattening the path. The interval itself is the vertical interval of a given
 * segment. The resulting interval tree can be used to quickly query which segments intersect
 * a given interval such as a line of a text.
 */
internal fun Path.toIntervals(
    intervals: IntervalTree<PathSegment> = IntervalTree()
): IntervalTree<PathSegment> {
    intervals.clear()

    // An error of 1 px is enough for our purpose as we don't need to AA the path
    asAndroidPath()
        .flatten(1.0f)
        .forEach { segment ->
            val start = min(segment.start.y, segment.end.y)
            val end = max(segment.start.y, segment.end.y)

            intervals += Interval(start, end, segment)
        }

    return intervals
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun PointF.toOffset() = Offset(x, y)

@Suppress("NOTHING_TO_INLINE")
internal inline fun RectF.toOffset() = Offset(left, top)

@Suppress("NOTHING_TO_INLINE")
internal inline fun RectF.toSize() = Size(width(), height())
