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
import android.graphics.Paint
import android.graphics.Path

private val HorizontalShift = intArrayOf(
     0,  1,  0,  1,
     0,  0,  0,  0,
    -1,  0,  0,  1,
    -1, -1,  0,  0
)

private val VerticalShift = intArrayOf(
     0,  0,  1,  0,
    -1, -1,  0, -1,
     0,  0,  1,  0,
     0,  0,  1,  0
)

fun Bitmap.toContour(
    margin: Float = 0.0f,
    alphaThreshold: Float = 0.0f
): Path {
    val w = width
    val h = height
    // TODO: Use guard band
    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    // Increase threshold by 1 for direction() to work as expected
    val threshold = (alphaThreshold * 255.0f + 1.0f).toInt().coerceIn(0, 255)

    // TOD: We can get rid of this step once we move to a better implementation of the
    //      marching sqaures algorithm
    var start = -1
    for (i in pixels.indices) {
        // Use >= comparison as we bumped threshold by 1
        if (pixels[i] ushr 24 >= threshold) {
            start = i - w - 1 // TODO: not safe until we have a guard band
            break
        }
    }

    // TODO: Rewrite marching squares algorithm to handle multiple contours
    //       and to be more robust (need to handle diagonal cases)
    fun direction(pixels: IntArray, index: Int): Int {
        // Set bit to 1 when alpha channel is >= threshold, 0 otherwise
        val key =
            ((((pixels[index]         ushr 24) - threshold) ushr 31) xor 1 shl 3) or
            ((((pixels[index + 1]     ushr 24) - threshold) ushr 31) xor 1 shl 2) or
            ((((pixels[index + w]     ushr 24) - threshold) ushr 31) xor 1 shl 1) or
            ((((pixels[index + w + 1] ushr 24) - threshold) ushr 31) xor 1)

        return index + HorizontalShift[key] + VerticalShift[key] * w
    }

    var i = 0
    var index = direction(pixels, start)

    val sx = index % w - 1
    val sy = index / w - 1

    val path = Path()

    var px = sx.toFloat()
    var py = sy.toFloat()
    var pm = Float.NaN

    path.moveTo(px, py)

    while (i++ < pixels.size) {
        index = direction(pixels, index)

        val x = index % w - 1
        val y = index / w - 1

        val m = (y - py) / (x - px)
        px = x.toFloat()
        py = y.toFloat()

        if (m != pm) {
            path.lineTo(px, py)
        }

        pm = m

        if (index == start) break
    }

    // TODO: Simplification step based on a given tolerance

    if (margin > 0.0f) {
        // NOTE: This creates an expensive path for later operations
        Paint().apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = margin * 2.0f
        }.getFillPath(path, path)
    }

    return path
}
