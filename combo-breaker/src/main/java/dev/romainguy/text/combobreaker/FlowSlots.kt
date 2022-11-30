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
import kotlin.math.max
import kotlin.math.min

private val RectComparator = Comparator { r1: RectF, r2: RectF -> (r1.left - r2.left).toInt() }

/**
 * Holder for pre-allocated structures that will be used when findFlowSlots() is called
 * repeatedly.
 */
internal class FlowSlotFinderState {
    val slots: ArrayList<RectF> = ArrayList()

    val intervals: ArrayList<Interval<PathSegment>> = ArrayList()
    val flowShapeHits: ArrayList<FlowShape> = ArrayList()

    val p1: PointF = PointF()
    val p2: PointF = PointF()
    val scratch: PointF = PointF()
}

/**
 * Given a layout area [box], finds all the slots (rectangles) that can be used to layout
 * content around a series of given flow shapes. In our case [box] will be a line of text
 * but it could be any other area.
 *
 * The resulting list will honor the [FlowType] of each [FlowShape], allowing content to lay
 * only on one side, both sides, or no side of the shape.
 *
 * @param box Rectangle representing the area in which we want to layout content.
 * @param container Bounds of the [box] container, which will typically match [box] exactly.
 * unless text is laid out over multiple columns/shapes.
 * @param flowShapes List of shapes that content must flow around.
 * @param state Optional [FlowSlotFinderState] structure to avoid allocations across invocations.
 * @param results Optional for debug only: holds the list of [Interval] used to find slots.
 *
 * @return A list of rectangles indicating where content can be laid out.
 */
internal fun findFlowSlots(
    box: RectF,
    container: RectF,
    flowShapes: ArrayList<FlowShape>,
    state: FlowSlotFinderState = FlowSlotFinderState(),
    results: MutableList<Interval<PathSegment>>? = null
): List<RectF> {
    var foundIntervals = false
    val searchInterval = Interval<PathSegment>(box.top, box.bottom)

    val slots = state.slots
    val intervals = state.intervals
    val flowShapeHits = state.flowShapeHits
    val p1 = state.p1
    val p2 = state.p2

    slots.clear()
    flowShapeHits.clear()

    val flowShapeCount = flowShapes.size
    for (i in 0 until flowShapeCount) {
        val flowShape = flowShapes[i]

        // Ignore shapes outside of the box or with a flow type of "none"
        if (quickReject(flowShape, box.top, box.bottom)) continue

        flowShapeHits.add(flowShape)

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
        var shapeMin = box.right
        var shapeMax = box.left

        val intervalCount = intervals.size
        for (j in 0 until intervalCount) {
            val interval = intervals[j]

            val segment = interval.data
            checkNotNull(segment)

            // p1 and p2 will be modified by the [clipSegment] function, which is why
            // we don't pass segment.start/end directly
            p1.set(segment.x0, segment.y0)
            p2.set(segment.x1, segment.y1)

            if (clipSegment(p1, p2, container, state.scratch)) {
                shapeMin = min(shapeMin, min(p1.x, p2.x))
                shapeMax = max(shapeMax, max(p1.x, p2.x))
            }
        }

        flowShape.min = shapeMin
        flowShape.max = shapeMax

        addReducedSlots(flowShape.flowType, box, shapeMin, shapeMax, slots)

        results?.addAll(intervals)
    }

    applyFlowShapeExclusions(flowShapeHits, slots)

    // If we haven't found any new slot because we never even found overlapping shapes,
    // consider the entire layout area as valid
    if (slots.size == 0 && !foundIntervals) {
        slots.add(RectF(box))
    }

    slots.sortWith(RectComparator)

    return slots
}

/**
 * Apply the exclusion zones of the selected flow shapes to the given list of slots.
 * For instance, a flow shape with a type set to [FlowType.OutsideRight] will prevent
 * any text to flow to its own left.
 *
 * @param flowShapes List of flow shapes that need to apply their exclusion zones.
 * @param slots List of slots to modify by intersecting them against flow shape exclusion zones.
 */
private fun applyFlowShapeExclusions(
    flowShapes: MutableList<FlowShape>,
    slots: MutableList<RectF>
) {
    // Fix-up slots by applying exclusion zones from flow shapes
    val flowShapeHitCount = flowShapes.size
    for (i in 0 until flowShapeHitCount) {
        val hit = flowShapes[i]
        // We do NOT want to do what's below for shapes marked as Outside, so we
        // check for the actual types instead of looking at isLeft/RightFlow which
        // work for Outside as well
        val leftFlow = hit.flowType == FlowType.OutsideLeft
        val rightFlow = hit.flowType == FlowType.OutsideRight

        val slotCount = slots.size
        for (j in 0 until slotCount) {
            val slot = slots[j]
            if (leftFlow) {
                slot.right = min(slot.right, hit.min)
            } else if (rightFlow) {
                slot.left = max(slot.left, hit.max)
            } else if (slot.left >= hit.min && slot.right <= hit.max) {
                // The slot is entirely inside another flow shape, so we discard it
                slot.setEmpty()
            }
        }
    }
}

/**
 * Given a layout area defined by [box] and a flow shape with the specified [min] and [max]
 * extents, add 0, 1, or 2 new slots to the [slots] list. The number of slots added depends
 * on the flow [type] of the shape ([FlowType.Outside] for instance tries to add 2 slots,
 * while [FlowType.OutsideLeft] would try to add only 1). While adding new slots, this function
 * may also reduce the existing slots presents in the [slots] list by performing intersection
 * tests between the new slots and the existing ones.
 *
 * For instance if we start with a right flowing shape (marked by X's) and find its slot
 * (drawn as a rectangle), we obtain:
 *
 * XX  __________________________________
 * XX |                                  |
 * XX  ----------------------------------
 *
 * Adding a left flowing slot:
 *
 *  ________________________________  XXX
 * |                                | XXX
 *  --------------------------------  XXX
 *
 * This function will intersect the left flowing slot with our first slot to produce:
 *
 * XX  _____________________________  XXX
 * XX |                             | XXX
 * XX  -----------------------------  XXX
 *
 * If we then add a left-and-right flowing shape defined as thus:
 *
 *  ________________XXX_________________
 * |                XXX                 |
 *  ----------------XXX-----------------
 *
 * The function will intersect the left slots of this shape with the existing slot, then
 * add a new intersect slot to the right to produce:
 *
 * XX  ____________ XXX ____________  XXX
 * XX |            |XXX|            | XXX
 * XX  ------------ XXX ------------  XXX
 *
 * When the new slots to add don't overlap with any existing slots, they are directly added
 * to the list of slots.
 */
private fun addReducedSlots(
    type: FlowType,
    box: RectF,
    min: Float,
    max: Float,
    slots: MutableList<RectF>
) {
    val left = box.left
    val top = box.top
    val right = box.right
    val bottom = box.bottom

    val isLeftFlow = type.isLeftFlow
    val isRightFlow = type.isRightFlow

    var foundLeftOverlap = false
    var foundRightOverlap = false

    val slotCount = slots.size
    for (i in 0 until slotCount) {
        val ancestor = slots[i]

        val leftOverlap = isLeftFlow && ancestor.left < min && left < ancestor.right
        val rightOverlap = isRightFlow && ancestor.left < right && max < ancestor.right

        if (leftOverlap && rightOverlap) {
            // Intersect with left slot, add right slot, intersected also
            val rightSlot = RectF(ancestor)
            ancestor.horizontalIntersect(left, min)
            rightSlot.horizontalIntersect(max, right)
            slots.add(rightSlot)
        } else {
            if (leftOverlap) {
                ancestor.horizontalIntersect(left, min)
            } else if (rightOverlap) {
                ancestor.horizontalIntersect(max, right)
            }
        }

        foundLeftOverlap = foundLeftOverlap || leftOverlap
        foundRightOverlap = foundRightOverlap || rightOverlap
    }

    if (!foundLeftOverlap && isLeftFlow && left < min) {
        slots.add(RectF(left, top, min, bottom))
    }

    if (!foundRightOverlap && isRightFlow && max < right) {
        slots.add(RectF(max, top, right, bottom))
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

/**
 * Behaves like RectF.intersect(float, float, float, float) but only deals with the
 * left and right parameters.
 */
private fun RectF.horizontalIntersect(left: Float, right: Float) {
    if (this.left < right && left < this.right) {
        if (this.left < left) this.left = left
        if (this.right > right) this.right = right
    }
}