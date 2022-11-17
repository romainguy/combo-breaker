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

import kotlin.math.cos
import kotlin.math.sqrt

@Suppress("NOTHING_TO_INLINE")
private inline fun invlength(x: Float, y: Float) = 1.0f / sqrt(x * x + y * y)

@Suppress("NOTHING_TO_INLINE")
private inline fun dot(x0: Float, y0: Float, x1: Float, y1: Float) = x0 * x1 + y0 * y1

/**
 * A contour is a series of line segments. It can be seen as a simplified version of a Path.
 * We use a custom data structure instead of a Path to avoid JNI transitions and the use of
 * an extra dependency like [pathway](https://github.com/romainguy/pathway) required to iterate
 * over the content of a Path.
 *
 * The first point in a contour should be treated as a move command in a Path, and
 * subsequent points should be line commands (`lineTo`).
 *
 * @param x The x coordinate of the first point in the contour.
 * @param y The y coordinate of the first point in the contour.
 * @param capacity The default capacity as a number of points.
 */
internal class Contour(x: Float, y: Float, capacity: Int = 64) {
    /**
     * The points making this contour. The size of the array is guaranteed to be greater or
     * equal to [count] * 2. Each point is made of 2 floats in the array, respectively x and y.
     */
    var points = FloatArray(capacity * 2) // 2 floats per point
        private set

    /**
     * Numbers of points in this contour.
     */
    var count = 1
        private set

    init {
        points[0] = x
        points[1] = y
    }

    /**
     * Builds a new contour with 2 points (1 line).
     */
    constructor(x0: Float, y0: Float, x1: Float, y1: Float): this(x0, y0) {
        points[2] = x1
        points[3] = y1
        count++
    }

    /**
     * Returns true if this contour starts with the specified point.
     */
    fun startsWith(x: Float, y: Float) = points[0] == x && points[1] == y

    /**
     * Returns true if this contour ends with the specified point.
     */
    fun endsWith(x: Float, y: Float): Boolean {
        val i = (count - 1) * 2
        return points[i] == x && points[i + 1] == y
    }

    /**
     * Inserts the specified point at the beginning of the contour.
     */
    fun prepend(x: Float, y: Float) {
        val index = ensureCapacity(count + 1) * 2
        points.copyInto(points, 2, 0, index)
        points[0] = x
        points[1] = y
    }

    /**
     * Inserts the specified point at the end of the contour.
     */
    fun append(x: Float, y: Float) {
        val index = ensureCapacity(count + 1) * 2
        points[index] = x
        points[index + 1] = y
    }

    /**
     * Adds all the point of the specified contour into this contour.
     */
    fun add(contour: Contour) {
        val index = ensureCapacity(count + contour.count) * 2
        contour.points.copyInto(points, index, 0, contour.count * 2)
    }

    /**
     * Returns a new contour as a simplified representation of this contour. The simplification
     * step is based on the specified [tolerance], expressed as the minimum angle in degrees
     * allowed between two segments in the contour.
     */
    fun simplify(tolerance: Float): Contour {
        val simplified = Contour(points[0], points[1], points[2], points[3])
        val minTolerance = -cos(Math.toRadians(tolerance.toDouble())).toFloat()

        for (i in 2 until count) {
            var index = i * 2
            val x = points[index]
            val y = points[index + 1]

            index = (simplified.count - 2) * 2
            var x0 = simplified.points[index]
            var y0 = simplified.points[index + 1]

            index += 2
            var x1 = simplified.points[index]
            var y1 = simplified.points[index + 1]

            x0 -= x1
            y0 -= y1
            val l0 = invlength(x0, y0)

            x1 = x - x1
            y1 = y - y1
            val l1 = invlength(x1, y1)

            val cosAngle = dot(x0 * l0, y0 * l0, x1 * l1, y1 * l1)
            if (cosAngle > minTolerance) {
                simplified.append(x, y)
            } else {
                simplified.points[index] = x
                simplified.points[index + 1] = y
            }
        }

        // TODO: We should check the angle between the first and last point when the contour is
        //       a closed contour

        return simplified
    }

    private fun ensureCapacity(newCount: Int): Int {
        if (newCount * 2 > points.size) {
            val newPoints = FloatArray((newCount * 2.0f * 1.5f).toInt())
            points.copyInto(newPoints, 0, 0, count * 2)
            points = newPoints
        }
        val oldCount = count
        count = newCount
        return oldCount
    }
}

/**
 * A [ContourSet] is a list of contours to which new segments can be added. When a new segment
 * is added, either a new [Contour] is created in the list, or the segment is added to existing
 * contours, which can lead to the fusion of pairs of contours.
 */
internal class ContourSet {
    private val contours = mutableListOf<Contour>()

    /**
     * Number of contours in this set.
     */
    val size: Int
        get() = contours.size

    /**
     * Returns the contour at the specified index.
     */
    operator fun get(index: Int) = contours[index]

    /**
     * Iterates over all the contours in the set.
     */
    operator fun iterator() = contours.iterator()

    /**
     * Finds the index of the contour that starts with the specified point.
     */
    private fun startIndexOf(x: Float, y: Float): Int {
        val size = contours.size
        for (i in 0 until size) {
            if (contours[i].startsWith(x, y)) return i
        }
        return -1
    }

    /**
     * Finds the index of the contour that ends with the specified point.
     */
    private fun endIndexOf(x: Float, y: Float): Int {
        val size = contours.size
        for (i in 0 until size) {
            if (contours[i].endsWith(x, y)) return i
        }
        return -1
    }

    /**
     * Adds a segment defined by the specified coordinates to the contour set.
     * This operation can create a new [Contour] or merge existing contours.
     */
    fun addLine(x0: Float, y0: Float, x1: Float, y1: Float) {
        // Find the contour this new line would come from
        val from = endIndexOf(x0, y0)
        // Find the contour this new line would connect to
        val to = startIndexOf(x1, y1)
        if (from >= 0 && to >= 0) {
            if (from != to) {
                // Join the two contours
                contours[from].add(contours[to])
                contours.removeAt(to)
            } else {
                // Loop the contour by appending its first point
                contours[from].append(x1, y1)
            }
        } else if (from >= 0) {
            // We're coming from an existing contour, append x1/y1
            contours[from].append(x1, y1)
        } else if (to >= 0) {
            // We're going to an existing contour head, prepend x0/y0
            contours[to].prepend(x0, y0)
        } else {
            // No contour, let's start a new one
            contours.add(Contour(x0, y0, x1, y1))
        }
    }
}
