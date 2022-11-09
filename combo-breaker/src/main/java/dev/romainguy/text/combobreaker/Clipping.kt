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
import kotlin.math.abs

private const val ClipInside = 0
private const val ClipTop = 1
private const val ClipBottom = 2
private const val ClipLeft = 4
private const val ClipRight = 8

internal fun clipSegment(p1: PointF, p2: PointF, r: RectF, out: PointF): Boolean {
    var clip1 = clipType(p1, r)
    var clip2 = clipType(p2, r)

    while (true) {
        if ((clip1 or clip2) == ClipInside) return true
        if ((clip1 and clip2) != ClipInside) return false

        // No need to test for the case where both end points are outside of the rectangle
        // since we only test against parts of the path that overlap the text interval

        val clipType = if (clip1 != ClipInside) clip1 else clip2
        intersection(p1, p2, r, clipType, out)

        if (clipType == clip1) {
            p1.set(out)
            clip1 = clipType(p1, r)
        } else {
            p2.set(out)
            clip2 = clipType(p2, r)
        }
    }
}

private fun clipType(p: PointF, r: RectF): Int {
    var clip = ClipInside
    if (p.y < r.top) clip = clip or ClipTop
    if (p.y > r.bottom) clip = clip or ClipBottom
    if (p.x < r.left) clip = clip or ClipLeft
    if (p.x > r.right) clip = clip or ClipRight
    return clip
}

private fun intersection(p1: PointF, p2: PointF, r: RectF, clip: Int, out: PointF) {
    val dx = p1.x - p2.x
    val dy = p1.y - p2.y

    // TODO: The threshold used here should be good enough to deal with colinear segments
    //       since they are expressed in pixels, but we should probably find a better way
    val sx = if (abs(dx) < 1e-3f) 0.0f else dy / dx
    val sy = if (abs(dy) < 1e-3f) 0.0f else dx / dy

    if ((clip and ClipTop) != 0) {
        out.set(p1.x + sy * (r.top - p1.y), r.top)
        return
    }

    if ((clip and ClipBottom) != 0) {
        out.set(p1.x + sy * (r.bottom - p1.y), r.bottom)
        return
    }

    // NOTE: Left and right clipping isn't necessary when we clip the source paths, it will
    //       however be necessary if we want to support "centered" shapes instead of just
    //       left/right floats
    if ((clip and ClipLeft) != 0) {
        out.set(r.left, p1.y + sx * (r.left - p1.x))
        return
    }

    if ((clip and ClipRight) != 0) {
        out.set(r.right, p1.y + sx * (r.right - p1.x))
        return
    }
}
