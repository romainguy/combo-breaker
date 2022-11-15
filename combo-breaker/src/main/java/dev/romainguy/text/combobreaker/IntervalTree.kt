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

import kotlin.math.max
import kotlin.math.min

/**
 * Interval in an [IntervalTree]. The interval is defined between a [start] and an [end]
 * coordinates, whose meanings are defined by the caller. An interval can also hold
 * arbitrary [data] to be used to looking at the result of queries with
 * [IntervalTree.findOverlaps].
 */
internal class Interval<T>(val start: Float, val end: Float, val data: T? = null) {
    /**
     * Returns trues if this interval overlaps with another interval.
     */
    fun overlaps(other: Interval<T>) = start <= other.end && end >= other.start
}

/**
 * An interval tree holds a list of intervals and allows for fast queries of intervals
 * that overlap any given interval. This can be used for instance to perform fast spatial
 * queries like finding all the segments in a path that overlap with a given vertical
 * interval.
 */
internal class IntervalTree<T> {
    // Note: this interval tree is implemented as a binary red/black tree that gets
    // re-balanced on updates. There's nothing notable about this particular data
    // structure beyond what can be found in various descriptions of binary search
    // trees and red/black trees

    private val terminator = Node(
        Interval(Float.MAX_VALUE, Float.MIN_VALUE, null),
        TreeColor.Black
    )
    private var root = terminator

    /**
     * Clears this tree and prepares it for reuse. After calling [clear], any call to
     * [findOverlaps] returns false.
     */
    internal fun clear() {
        root = terminator
    }

    /**
     * Finds all the intervals that overlap with the specified [interval]. If [results]
     * is specified, [results] is returned, otherwise a new [MutableList] is returned.
     */
    internal fun findOverlaps(
        interval: Interval<T>,
        results: MutableList<Interval<T>> = mutableListOf()
    ): MutableList<Interval<T>> {
        if (root !== terminator) {
            findOverlaps(root, interval, results)
        }
        return results
    }

    private fun findOverlaps(
        node: Node,
        interval: Interval<T>,
        results: MutableList<Interval<T>>
    ) {
        if (node.interval.overlaps(interval)) results.add(node.interval)
        if (node.left !== terminator && node.left.max >= interval.start) {
            findOverlaps(node.left, interval, results)
        }
        if (node.right !== terminator && node.right.min <= interval.end) {
            findOverlaps(node.right, interval, results)
        }
    }

    /**
     * Adds the specified [Interval] to the interval tree.
     */
    operator fun plusAssign(interval: Interval<T>) {
        val node = Node(interval)

        // Update the tree without doing any balancing
        var current = root
        var parent = terminator

        while (current !== terminator) {
            parent = current
            current = if (node.interval.start <= current.interval.start) {
                current.left
            } else {
                current.right
            }
        }

        node.parent = parent

        if (parent === terminator) {
            root = node
        } else {
            if (node.interval.start <= parent.interval.start) {
                parent.left = node
            } else {
                parent.right = node
            }
        }

        updateNodeData(node)

        rebalance(node)
    }

    private fun rebalance(target: Node) {
        var node = target

        while (node !== root && node.parent.color == TreeColor.Red) {
            val ancestor = node.parent.parent
            if (node.parent === ancestor.left) {
                val right = ancestor.right
                if (right.color == TreeColor.Red) {
                    right.color = TreeColor.Black
                    node.parent.color = TreeColor.Black
                    ancestor.color = TreeColor.Red
                    node = ancestor
                } else {
                    if (node === node.parent.right) {
                        node = node.parent
                        rotateLeft(node)
                    }
                    node.parent.color = TreeColor.Black
                    ancestor.color = TreeColor.Red
                    rotateRight(ancestor)
                }
            } else {
                val left = ancestor.left
                if (left.color == TreeColor.Red) {
                    left.color = TreeColor.Black
                    node.parent.color = TreeColor.Black
                    ancestor.color = TreeColor.Red
                    node = ancestor
                } else {
                    if (node === node.parent.left) {
                        node = node.parent
                        rotateRight(node)
                    }
                    node.parent.color = TreeColor.Black
                    ancestor.color = TreeColor.Red
                    rotateLeft(ancestor)
                }
            }
        }

        root.color = TreeColor.Black
    }

    private fun rotateLeft(node: Node) {
        val right = node.right
        node.right = right.left

        if (right.left !== terminator) {
            right.left.parent = node
        }

        right.parent = node.parent

        if (node.parent === terminator) {
            root = right
        } else {
            if (node.parent.left === node) {
                node.parent.left = right
            } else {
                node.parent.right = right
            }
        }

        right.left = node
        node.parent = right

        updateNodeData(node)
    }

    private fun rotateRight(node: Node) {
        val left = node.left
        node.left = left.right

        if (left.right !== terminator) {
            left.right.parent = node
        }

        left.parent = node.parent

        if (node.parent === terminator) {
            root = left
        } else {
            if (node.parent.right === node) {
                node.parent.right = left
            } else {
                node.parent.left = left
            }
        }

        left.right = node
        node.parent = left

        updateNodeData(node)
    }

    private fun updateNodeData(node: Node) {
        var current = node
        while (current !== terminator) {
            current.min = min(current.interval.start, min(current.left.min, current.right.min))
            current.max = max(current.interval.end, max(current.left.max, current.right.max))
            current = current.parent
        }
    }

    private enum class TreeColor {
        Red, Black
    }

    private inner class Node(val interval: Interval<T>, var color: TreeColor = TreeColor.Red) {
        var min: Float = interval.start
        var max: Float = interval.end

        var left: Node = terminator
        var right: Node = terminator
        var parent: Node = terminator
    }
}
