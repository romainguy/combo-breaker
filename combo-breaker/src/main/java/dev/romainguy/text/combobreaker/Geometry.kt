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

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import dev.romainguy.graphics.path.PathSegment.Type.*
import dev.romainguy.graphics.path.iterator
import kotlin.math.max
import kotlin.math.min

/**
 * Divides this path into a list of paths. Each contour inside this path is returned as a separate
 * [Path]. For instance the following code snippet creates two rectangular contours:
 *
 * ```
 * val p = Path()
 * p.addRect(…)
 * p.addRect(…)
 * val paths = p.divide()
 * ```
 * The list returned by calling `p.divide()` will contain two `Path` instances, each representing
 * one of the two rectangles.
 *
 * @param paths An optional mutable list of [Path] that will hold the result of the division.
 *
 * @return A list of [Path] representing all the contours in this path. The returned list is either
 * a newly allocated list if the [paths] parameter was left unspecified, or the [paths] parameter.
 */
fun Path.divide(paths: MutableList<Path> = mutableListOf()): List<Path> {
    var path = Path()

    var first = true

    val iterator = asAndroidPath().iterator()
    val points = FloatArray(8)

    while (iterator.hasNext()) {
        when (iterator.next(points)) {
            Move -> {
                if (!first) {
                    paths.add(path)
                    path = Path()
                }
                first = false
                path.moveTo(points[0], points[1])
            }
            Line -> path.lineTo(points[2], points[3])
            Quadratic -> path.quadraticBezierTo(
                points[2],
                points[3],
                points[4],
                points[5]
            )
            Conic -> continue // We convert conics to quadratics
            Cubic -> path.cubicTo(
                points[2],
                points[3],
                points[4],
                points[5],
                points[6],
                points[7]
            )
            Close -> path.close()
            Done -> continue // Won't happen inside this loop
        }
    }

    if (!first) paths.add(path)

    return paths
}

internal class PathSegment(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

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
    val pathData = asAndroidPath().approximate(1.0f)
    val pointCount = pathData.size / 3

    if (pointCount > 1) {
        for (i in 1 until pointCount) {
            val index = i * 3
            val prevIndex = (i - 1) * 3

            val d = pathData[index]
            val x = pathData[index + 1]
            val y = pathData[index + 2]

            val pd = pathData[prevIndex]
            val px = pathData[prevIndex + 1]
            val py = pathData[prevIndex + 2]

            if (d != pd && (x != px || y != py)) {
                val segment = PathSegment(px, py, x, y)
                val intervalStart = min(segment.y0, segment.y1)
                val intervalEnd = max(segment.y0, segment.y1)

                intervals += Interval(intervalStart, intervalEnd, segment)
            }
        }
    }

    return intervals
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun RectF.toOffset() = Offset(left, top)

@Suppress("NOTHING_TO_INLINE")
internal inline fun RectF.toSize() = Size(width(), height())
