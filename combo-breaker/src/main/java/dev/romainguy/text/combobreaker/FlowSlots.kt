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
import androidx.core.graphics.PathSegment
import kotlin.math.max
import kotlin.math.min

internal fun findFlowSlots(
    box: RectF,
    flowShapes: ArrayList<FlowShape>,
    results: MutableList<Interval<PathSegment>>? = null
): List<RectF> {
    val slots = mutableListOf<RectF>()

    val searchInterval = Interval<PathSegment>(box.top, box.bottom)
    val intervals = mutableListOf<Interval<PathSegment>>()

    val newSlots = mutableListOf<RectF>()

    val p1 = PointF()
    val p2 = PointF()
    val scratch = PointF()

    val flowShapeCount = flowShapes.size
    for (i in 0 until flowShapeCount) {
        val flowShape = flowShapes[i]

        if (quickReject(box.top, box.bottom, flowShape)) continue

        intervals.clear()
        flowShape.intervals.findOverlaps(searchInterval, intervals)

        var areaMin = Float.POSITIVE_INFINITY
        var areaMax = Float.NEGATIVE_INFINITY

        val intervalCount = intervals.size
        for (j in 0 until intervalCount) {
            val interval = intervals[j]

            val segment = interval.data
            checkNotNull(segment)

            p1.set(segment.start)
            p2.set(segment.end)

            if (clipSegment(p1, p2, box, scratch)) {
                areaMin = min(areaMin, min(p1.x, p2.x))
                areaMax = max(areaMax, max(p1.x, p2.x))
            }
        }

        newSlots.clear()

        if (flowShape.flowType.isLeftFlow && areaMin != Float.POSITIVE_INFINITY) {
            addReducedSlots(box.left, box.top, areaMin, box.bottom, slots, newSlots)
        }

        if (flowShape.flowType.isRightFlow && areaMax != Float.NEGATIVE_INFINITY) {
            addReducedSlots(areaMax, box.top, box.right, box.bottom, slots, newSlots)
        }

        slots.addAll(newSlots)
        results?.addAll(intervals)
    }

    if (slots.size == 0) {
        slots.add(box)
    }

    return slots
}

private fun addReducedSlots(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    spaces: MutableList<RectF>,
    newSpaces: MutableList<RectF>
) {
    if (left == right) return

    if (spaces.size == 0) {
        newSpaces.add(RectF(left, top, right, bottom))
        return
    }

    val candidate = RectF()
    val spaceCount = spaces.size
    for (j in 0 until spaceCount) {
        val space = spaces[j]

        candidate.set(left, top, right, bottom)
        candidate.intersect(space)
        space.intersect(left, top, right, bottom)

        if (candidate != space) {
            if (!candidate.isEmpty) newSpaces.add(candidate)
            break
        }
    }
}


@Suppress("NOTHING_TO_INLINE")
private inline fun quickReject(top: Float, bottom: Float, flowShape: FlowShape) =
    flowShape.flowType == FlowType.None ||
    top > flowShape.bounds.bottom ||
    bottom < flowShape.bounds.top
