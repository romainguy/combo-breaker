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

/**
 * Given a layout area [box], finds all the slots (rectangles) that can be used to layout
 * content around a series of given flow shapes. In our case [box] will be a line of text
 * but it could be any other area.
 *
 * The resulting list will honor the [FlowType] of each [FlowShape], allowing content to lay
 * only on one side, both sides, or no side of the shape.
 *
 * @param box Rectangle representing the area in which we want to layout content
 * @param flowShapes List of shapes that content must flow around
 * @param results Optional for debug only: holds the list of [Interval] used to find slots
 *
 * @return A list of rectangles indicating where content can be laid out
 */
internal fun findFlowSlots(
    box: RectF,
    flowShapes: ArrayList<FlowShape>,
    results: MutableList<Interval<PathSegment>>? = null
): List<RectF> {
    // List of all the slots we found
    val slots = mutableListOf<RectF>()

    // List of all the intervals we must consider for a given shape
    val intervals = mutableListOf<Interval<PathSegment>>()
    // List of slots we found for a given shape. We don't put slots
    // directly inside the [slots] list as need to perform intersection work
    // with previously found slots
    val shapeSlots = mutableListOf<RectF>()

    // Temporary variable used to avoid allocations
    val p1 = PointF()
    val p2 = PointF()
    val scratch = PointF()

    var foundIntervals = false
    val searchInterval = Interval<PathSegment>(box.top, box.bottom)

    val flowShapeCount = flowShapes.size
    for (i in 0 until flowShapeCount) {
        val flowShape = flowShapes[i]

        // Ignore shapes outside of the box or with a flow type of "none"
        if (quickReject(flowShape, box.top, box.bottom)) continue

        // We first find all the intervals for the current shape that intersect
        // with the box (layout area/line of text). We'll then go through that
        // list to find what part of the box lies outside the shape
        intervals.clear()
        flowShape.intervals.findOverlaps(searchInterval, intervals)
        foundIntervals = foundIntervals || intervals.size != 0

        // Find the left-most (min) and right-most (max) x coordinates of all
        // the shape segments that intersect the box. This will tell us where
        // we can safely layout content to the left (min) and right (max) of
        // the shape
        var shapeMin = Float.POSITIVE_INFINITY
        var shapeMax = Float.NEGATIVE_INFINITY

        val intervalCount = intervals.size
        for (j in 0 until intervalCount) {
            val interval = intervals[j]

            val segment = interval.data
            checkNotNull(segment)

            // p1 and p2 will be modified by the [clipSegment] function, which is why
            // we don't pass segment.start/end directly
            p1.set(segment.start)
            p2.set(segment.end)

            if (clipSegment(p1, p2, box, scratch)) {
                shapeMin = min(shapeMin, min(p1.x, p2.x))
                shapeMax = max(shapeMax, max(p1.x, p2.x))
            }
        }

        // Reset the local list of slots and create a left slot (from the box's left origin
        // to the shape minimum) and a right slot (from the shape maximum to the box's right).
        // Since multiple shapes can overlap our search box/layout area, we must do more work,
        // otherwise the layout algorithm could reuse overlapping slots. To fix this, we run
        // a reducing/shrinking pass in [addReducedSlots].

        shapeSlots.clear()

        if (flowShape.flowType.isLeftFlow && shapeMin != Float.POSITIVE_INFINITY) {
            addReducedSlots(box.left, box.top, shapeMin, box.bottom, slots, shapeSlots)
        }

        if (flowShape.flowType.isRightFlow && shapeMax != Float.NEGATIVE_INFINITY) {
            addReducedSlots(shapeMax, box.top, box.right, box.bottom, slots, shapeSlots)
        }

        // Add our new slots to the final results
        slots.addAll(shapeSlots)
        results?.addAll(intervals)
    }

    // If we haven't found any new slot because we never even found overlapping shapes,
    // consider the entire layout area as valid
    if (slots.size == 0 && !foundIntervals) {
        slots.add(box)
    }

    return slots
}

/**
 * Adds the specified slot defined by the rectangle [left], [top], [right], [bottom] to the
 * [slots] list. Before adding the slot to the list, it is compared against all the slots in
 * [ancestorSlots]: if the new slot and an ancestor intersect, the ancestor is changed to the
 * result of the intersection, otherwise the new slot is added to [slots].
 */
private fun addReducedSlots(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    ancestorSlots: MutableList<RectF>,
    slots: MutableList<RectF>
) {
    if (left == right) return

    var foundOverlap = false
    val slotCount = ancestorSlots.size
    for (i in 0 until slotCount) {
        if (ancestorSlots[i].intersect(left, top, right, bottom)) {
            foundOverlap = true
        }
    }

    if (!foundOverlap) {
        slots.add(RectF(left, top, right, bottom))
    }
}

/**
 * Quickly decide whether the specified flow shape can be rejected by checking whether
 * it lies outside of the vertical interval defined by [top] and [bottom]. A [FlowShape]
 * is also rejected with its [FlowShape.flowType] is [FlowType.None] since it won't
 * participate in layout.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun quickReject(flowShape: FlowShape, top: Float, bottom: Float) =
    flowShape.flowType == FlowType.None ||
    top > flowShape.bounds.bottom ||
    bottom < flowShape.bounds.top
