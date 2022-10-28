/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.decor
import android.content.Context
import android.view.DisplayCutout
import android.view.Surface
import android.view.View
import android.view.ViewGroup

/**
 * An interface for providing view with a specific functionality. Take an example, if privacy dot
 * is enabled, there are 4 DecorProviders which are used to provide privacy dot views on top-left,
 * top-right, bottom-left, bottom-right.
 */
abstract class DecorProvider {

    /** Id for the view which is created through inflateView() */
    abstract val viewId: Int

    /** The number of total aligned bounds */
    val numOfAlignedBound: Int
        get() = alignedBounds.size

    /** The aligned bounds for the view which is created through inflateView() */
    abstract val alignedBounds: List<Int>

    /**
     * Called when res info changed.
     * Child provider needs to implement it if its view needs to be updated.
     */
    abstract fun onReloadResAndMeasure(
        view: View,
        reloadToken: Int,
        @Surface.Rotation rotation: Int,
        tintColor: Int,
        displayUniqueId: String?
    )

    /** Inflate view into parent as current rotation */
    abstract fun inflateView(
        context: Context,
        parent: ViewGroup,
        @Surface.Rotation rotation: Int,
        tintColor: Int
    ): View

    override fun toString() = "${javaClass.simpleName}{alignedBounds=$alignedBounds}"
}

/**
 * A provider for view shown on corner.
 */
abstract class CornerDecorProvider : DecorProvider() {
    /** The first bound which a corner view is aligned based on rotation 0 */
    @DisplayCutout.BoundsPosition protected abstract val alignedBound1: Int
    /** The second bound which a corner view is aligned based on rotation 0 */
    @DisplayCutout.BoundsPosition protected abstract val alignedBound2: Int

    override val alignedBounds: List<Int> by lazy {
        listOf(alignedBound1, alignedBound2)
    }
}

/**
 * A provider for view shown on bound.
 */
abstract class BoundDecorProvider : DecorProvider() {
    /** The bound which a view is aligned based on rotation 0 */
    @DisplayCutout.BoundsPosition protected abstract val alignedBound: Int

    override val alignedBounds: List<Int> by lazy {
        listOf(alignedBound)
    }
}

/**
 * Split list to 2 sub-lists, and return it back as Pair<>. The providers on the first list contains
 * this alignedBound element. The providers on the second list do not contain this alignedBound
 * element.
 */
fun List<DecorProvider>.partitionAlignedBound(
    @DisplayCutout.BoundsPosition alignedBound: Int
): Pair<List<DecorProvider>, List<DecorProvider>> {
    return partition { it.alignedBounds.contains(alignedBound) }
}

/**
 * Get the proper bound from DecorProvider list
 * Time complexity: O(N), N is the number of providers
 *
 * Choose order
 * 1. Return null if list is empty
 * 2. If list contains BoundDecorProvider, return its alignedBound[0] because it is a must-have
 *    bound
 * 3. Return the bound with most DecorProviders
 */
fun List<DecorProvider>.getProperBound(): Int? {
    // Return null if list is empty
    if (isEmpty()) {
        return null
    }

    // Choose alignedBounds[0] of BoundDecorProvider if any
    val singleBoundProvider = firstOrNull { it.numOfAlignedBound == 1 }
    if (singleBoundProvider != null) {
        return singleBoundProvider.alignedBounds[0]
    }

    // Return the bound with most DecorProviders
    val boundCount = intArrayOf(0, 0, 0, 0)
    for (provider in this) {
        for (bound in provider.alignedBounds) {
            boundCount[bound]++
        }
    }
    var maxCount = 0
    var maxCountBound: Int? = null
    val bounds = arrayOf(
        // Put top and bottom at first to get the highest priority to be chosen
        DisplayCutout.BOUNDS_POSITION_TOP,
        DisplayCutout.BOUNDS_POSITION_BOTTOM,
        DisplayCutout.BOUNDS_POSITION_LEFT,
        DisplayCutout.BOUNDS_POSITION_RIGHT
    )
    for (bound in bounds) {
        if (boundCount[bound] > maxCount) {
            maxCountBound = bound
            maxCount = boundCount[bound]
        }
    }
    return maxCountBound
}
