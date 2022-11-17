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

/**
 * Extract the contours of this [Bitmap] as a [Path]. The contours are traced by following opaque
 * pixels, as defined by [alphaThreshold]. Any pixel with an alpha channel value greater than the
 * specified [alphaThreshold] is considered opaque.
 *
 * After the contours are built, a simplification pass is performed to reduce the complexity of the
 * paths. The [minAngle] parameter defines the minimum angle between two angles in the contour
 * before they are collapsed. For instance, passing a [minAngle] of 45 means that the final path
 * will not contain adjacent segments with an angle greater than 45 degrees.
 */
fun Bitmap.toContour(
    alphaThreshold: Float = 0.0f,
    minAngle: Float = 15.0f
): Path {
    val w = width
    val h = height

    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    // Increase threshold by 1 to make the branchless function sample() work
    val threshold = (alphaThreshold * 255.0f + 1).toInt().coerceIn(0, 255)

    val contours = ContourSet()

    for (y in 0 until (h - 1)) {
        val y0 = y.toFloat()
        val y5 = y + 0.5f
        val y1 = y + 1.0f

        val rowIndex = y * w

        for (x in 0 until (w - 1)) {
            val x0 = x.toFloat()
            val x5 = x + 0.5f
            val x1 = x + 1.0f

            val index = rowIndex + x
            val a = pixels.sample(index, threshold)
            val b = pixels.sample(index + 1, threshold)
            val c = pixels.sample(index + w, threshold)
            val d = pixels.sample(index + 1 + w, threshold)

            when (quadKey(a, b, c, d)) {
                0x1 -> contours.addLine(x0, y5, x5, y0)
                0x2 -> contours.addLine(x5, y0, x1, y5)
                0x3 -> contours.addLine(x0, y5, x1, y5)
                0x4 -> contours.addLine(x5, y1, x0, y5)
                0x5 -> contours.addLine(x5, y1, x5, y0)
                0x6 -> {
                    contours.addLine(x5, y0, x1, y5)
                    contours.addLine(x0, y1, x0, y5)
                }
                0x7 -> contours.addLine(x5, y1, x1, y5)
                0x8 -> contours.addLine(x1, y5, x5, y1)
                0x9 -> {
                    contours.addLine(x0, y5, x5, y0)
                    contours.addLine(x1, y5, x5, y1)
                }
                0xA -> contours.addLine(x5, y0, x5, y1)
                0xB -> contours.addLine(x0, y5, x5, y1)
                0xC -> contours.addLine(x1, y5, x0, y5)
                0xD -> contours.addLine(x1, y5, x5, y0)
                0xE -> contours.addLine(x5, y0, x0, y5)
                // else, full transparent or full opaque quads, 0x0 and 0xF
            }
        }
    }

    val path = Path()
    val size = contours.size
    for (i in 0 until size) {
        val contour = if (minAngle < 1.0f) contours[i] else contours[i].simplify(minAngle)
        contour.toPath(path)
    }
    return path
}

@Suppress("NOTHING_TO_INLINE")
private inline fun IntArray.sample(index: Int, threshold: Int) =
    (((this[index] ushr 24) - threshold) ushr 31) xor 1

@Suppress("NOTHING_TO_INLINE")
private inline fun quadKey(a: Int, b: Int, c: Int, d: Int) =
    a or (b shl 1) or (c shl 2) or (d shl 3)
