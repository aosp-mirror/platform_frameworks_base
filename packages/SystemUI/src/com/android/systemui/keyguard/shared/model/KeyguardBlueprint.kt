/*
 * Copyright (C) 2023 The Android Open Source Project
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

/** Determines the constraints for the ConstraintSet in the lockscreen root view. */
interface KeyguardBlueprint {
    val id: String
    val sections: Set<KeyguardSection>

    /**
     * Add views to new blueprint.
     *
     * Finds sections that did not exist in the previous blueprint and add the corresponding views.
     *
     * @param previousBluePrint: KeyguardBlueprint the blueprint we are transitioning from.
     */
    fun addViews(previousBlueprint: KeyguardBlueprint?, constraintLayout: ConstraintLayout) {
        sections.subtract((previousBlueprint?.sections ?: setOf()).toSet()).forEach {
            it.addViews(constraintLayout)
            it.bindData(constraintLayout)
        }
    }

    /**
     * Remove views of old blueprint.
     *
     * Finds sections that are no longer in the next blueprint and remove the corresponding views.
     *
     * @param nextBluePrint: KeyguardBlueprint the blueprint we will transition to.
     */
    fun removeViews(nextBlueprint: KeyguardBlueprint, constraintLayout: ConstraintLayout) {
        sections.subtract(nextBlueprint.sections).forEach { it.removeViews(constraintLayout) }
    }

    fun applyConstraints(constraintSet: ConstraintSet) {
        sections.forEach { it.applyConstraints(constraintSet) }
    }
}
