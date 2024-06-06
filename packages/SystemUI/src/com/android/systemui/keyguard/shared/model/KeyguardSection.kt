/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.shared.model

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet

/**
 * Lower level modules that determine constraints for a particular section in the lockscreen root
 * view.
 */
abstract class KeyguardSection {
    /** Adds the views to the root view. */
    abstract fun addViews(constraintLayout: ConstraintLayout)
    /** Binds the views to data. */
    abstract fun bindData(constraintLayout: ConstraintLayout)
    /** Applies layout constraints to the view in respect to the root view. */
    abstract fun applyConstraints(constraintSet: ConstraintSet)
    /** Removes views and does any data binding destruction. */
    abstract fun removeViews(constraintLayout: ConstraintLayout)

    /* Notifies the section is being rebuilt */
    open fun onRebuildBegin() {}

    /* Notifies the secion that the rebuild is complete */
    open fun onRebuildEnd() {}

    /**
     * Defines equality as same class.
     *
     * This is to enable set operations to be done as an optimization to blueprint transitions.
     */
    override fun equals(other: Any?): Boolean {
        other?.let { other ->
            return this::class == other::class
        }
        return false
    }

    /**
     * Defines hashcode as class.
     *
     * This is to enable set operations to be done as an optimization to blueprint transitions.
     */
    override fun hashCode(): Int {
        return this::class.hashCode()
    }
}
