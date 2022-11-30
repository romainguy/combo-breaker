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

import android.graphics.Bitmap
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * Extract the contours of this [Bitmap] as a [Path]. The contours are traced by following opaque
 * pixels, as defined by [alphaThreshold]. Any pixel with an alpha channel value greater than the
 * specified [alphaThreshold] is considered opaque.
 *
 * After the contours are built, a simplification pass is performed to reduce the complexity of the
 * paths. The [minAngle] parameter defines the minimum angle between two segments in the contour
 * before they are collapsed. For instance, passing a [minAngle] of 45 means that the final path
 * will not contain adjacent segments with an angle greater than 45 degrees.
 *
 * @param alphaThreshold Maximum alpha channel a pixel might have before being considered opaque.
 * This value is between 0.0 and 1.0.
 * @param minAngle Minimum angle between two segments in the contour before they are collapsed
 * to simplify the final geometry.
 *
 * @return A [Path] containing all the contours detected in this [Bitmap], separated by `moveTo`
 * commands inside the path.
 */
fun Bitmap.toPath(
    alphaThreshold: Float = 0.0f,
    minAngle: Float = 15.0f,
): Path {
    if (!hasAlpha()) {
        return Path().apply {
            addRect(0.0f, 0.0f, width.toFloat(), height.toFloat(), Path.Direction.CCW)
        }
    }

    val contours = toContourSet(alphaThreshold)

    val path = Path()
    val size = contours.size
    for (i in 0 until size) {
        val contour = if (minAngle < 1.0f) contours[i] else contours[i].simplify(minAngle)
        contour.toPath(path)
    }

    return path
}

/**
 * Extract the contours of this [Bitmap] as a list of [Path]. The contours are traced by following
 * opaque pixels, as defined by [alphaThreshold]. Any pixel with an alpha channel value greater
 * than the specified [alphaThreshold] is considered opaque.
 *
 * After the contours are built, a simplification pass is performed to reduce the complexity of the
 * paths. The [minAngle] parameter defines the minimum angle between two segments in the contour
 * before they are collapsed. For instance, passing a [minAngle] of 45 means that the final path
 * will not contain adjacent segments with an angle greater than 45 degrees.
 *
 * @param alphaThreshold Maximum alpha channel a pixel might have before being considered opaque.
 * This value is between 0.0 and 1.0.
 * @param minAngle Minimum angle between two segments in the contour before they are collapsed
 * to simplify the final geometry.
 *
 * @return A list of [Path] containing all the contours detected in this [Bitmap] as separate
 * paths.
 */
fun Bitmap.toPaths(
    alphaThreshold: Float = 0.0f,
    minAngle: Float = 15.0f,
): List<Path> {
    if (!hasAlpha()) {
        return listOf(
            Path().apply {
                addRect(0.0f, 0.0f, width.toFloat(), height.toFloat(), Path.Direction.CCW)
            }
        )
    }

    val contours = toContourSet(alphaThreshold)
    val paths = mutableListOf<Path>()

    val size = contours.size
    for (i in 0 until size) {
        val path = Path()
        val contour = if (minAngle < 1.0f) contours[i] else contours[i].simplify(minAngle)
        contour.toPath(path)
        paths += path
    }

    return paths
}

private fun Bitmap.toContourSet(alphaThreshold: Float): ContourSet {
    val w = width
    val h = height

    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    // Increase threshold by 1 to make the branchless function sample() work
    val threshold = (alphaThreshold * 255.0f + 1).toInt().coerceIn(0, 255)

    val contours = ContourSet()

    val xmax = (w - 1).toFloat()
    val ymax = (h - 1).toFloat()

    // Pretend we have a guard band to handle opaque pixels at the edges
    for (y in -1 until h) {
        val y0 = max(0.0f, y.toFloat())
        val yM = (y + 0.5f).clamp(0.0f, ymax)
        val y1 = min(ymax, y + 1.0f)

        for (x in -1 until w) {
            val x0 = max(0.0f, x.toFloat())
            val xM = (x + 0.5f).clamp(0.0f, xmax)
            val x1 = min(xmax, x + 1.0f)

            val a = pixels.sample(w, h, x, y, threshold)
            val b = pixels.sample(w, h, x + 1, y, threshold)
            val c = pixels.sample(w, h, x, y + 1, threshold)
            val d = pixels.sample(w, h, x + 1, y + 1, threshold)

            when (quadKey(a, b, c, d)) {
                // 0x0 -> fully transparent, skip
                0x1 -> contours.addLine(x0, yM, xM, y0)
                0x2 -> contours.addLine(xM, y0, x1, yM)
                0x3 -> contours.addLine(x0, yM, x1, yM)
                0x4 -> contours.addLine(xM, y1, x0, yM)
                0x5 -> contours.addLine(xM, y1, xM, y0)
                0x6 -> {
                    contours.addLine(xM, y0, x1, yM)
                    contours.addLine(x0, y1, x0, yM)
                }
                0x7 -> contours.addLine(xM, y1, x1, yM)
                0x8 -> contours.addLine(x1, yM, xM, y1)
                0x9 -> {
                    contours.addLine(x0, yM, xM, y0)
                    contours.addLine(x1, yM, xM, y1)
                }
                0xA -> contours.addLine(xM, y0, xM, y1)
                0xB -> contours.addLine(x0, yM, xM, y1)
                0xC -> contours.addLine(x1, yM, x0, yM)
                0xD -> contours.addLine(x1, yM, xM, y0)
                0xE -> contours.addLine(xM, y0, x0, yM)
                // 0xF -> fully opaque, skip
            }
        }
    }

    return contours
}

@Suppress("NOTHING_TO_INLINE")
private inline fun IntArray.sample(w: Int, h: Int, x: Int, y: Int, threshold: Int) =
    if (x < 0 || x >= w || y < 0 || y >= h)
        0
    else
        (((this[y * w + x] ushr 24) - threshold) ushr 31) xor 1

@Suppress("NOTHING_TO_INLINE")
private inline fun quadKey(a: Int, b: Int, c: Int, d: Int) =
    a or (b shl 1) or (c shl 2) or (d shl 3)

fun Float.clamp(minimumValue: Float, maximumValue: Float): Float {
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}