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
import android.view.DisplayCutout
import android.view.LayoutInflater
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
    val numOfAlignedEdge: Int
    get() = alignedBounds.size

    /** The aligned bounds for the view which is created through inflateView() */
    abstract val alignedBounds: List<Int>

    /** Inflate view into parent as current rotation */
    abstract fun inflateView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        @Surface.Rotation rotation: Int
    ): View
}

/**
 * Split list to 2 list, and return it back as Pair<>. The providers on the first list contains this
 * alignedBound element. The providers on the second list do not contain this alignedBound element
 */
fun List<DecorProvider>.partitionAlignedBound(
    @DisplayCutout.BoundsPosition alignedBound: Int
): Pair<List<DecorProvider>, List<DecorProvider>> {
    return partition { it.alignedBounds.contains(alignedBound) }
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