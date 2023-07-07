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
package com.android.systemui.shade

import androidx.constraintlayout.widget.ConstraintSet

typealias ConstraintChange = ConstraintSet.() -> Unit

operator fun ConstraintChange?.plus(other: ConstraintChange?): ConstraintChange? {
    // Prevent wrapping
    if (this == null) return other
    if (other == null) return this
    else return {
        this@plus()
        other()
    }
}

/**
 * Contains all changes that need to be performed to the different [ConstraintSet] in
 * [ShadeHeaderController].
 */
data class ConstraintsChanges(
    val qqsConstraintsChanges: ConstraintChange? = null,
    val qsConstraintsChanges: ConstraintChange? = null,
    val largeScreenConstraintsChanges: ConstraintChange? = null
) {
    operator fun plus(other: ConstraintsChanges) = ConstraintsChanges(
        qqsConstraintsChanges + other.qqsConstraintsChanges,
        qsConstraintsChanges + other.qsConstraintsChanges,
        largeScreenConstraintsChanges + other.largeScreenConstraintsChanges
    )
}

/**
 * Determines [ConstraintChanges] for [ShadeHeaderController] based on configurations.
 *
 * Given that the number of different scenarios is not that large, having specific methods instead
 * of a full map between state and [ConstraintSet] was preferred.
 */
interface CombinedShadeHeadersConstraintManager {
    /**
     * Changes for when the visibility of the privacy chip changes
     */
    fun privacyChipVisibilityConstraints(visible: Boolean): ConstraintsChanges

    /**
     * Changes for situations with no top center cutout (there may be a corner cutout)
     */
    fun emptyCutoutConstraints(): ConstraintsChanges

    /**
     * Changes to incorporate side insets due to rounded corners/corner cutouts
     */
    fun edgesGuidelinesConstraints(
        cutoutStart: Int,
        paddingStart: Int,
        cutoutEnd: Int,
        paddingEnd: Int
    ): ConstraintsChanges

    /**
     * Changes for situations with top center cutout (in this case, there are no corner cutouts).
     */
    fun centerCutoutConstraints(rtl: Boolean, offsetFromEdge: Int): ConstraintsChanges
}
