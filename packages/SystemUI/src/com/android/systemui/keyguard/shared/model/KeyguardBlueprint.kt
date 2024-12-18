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
    val sections: List<KeyguardSection>

    /**
     * Removes views of old blueprint and add views of new blueprint.
     *
     * Finds sections that no longer exists in the next blueprint and removes those views. Finds
     * sections that did not exist in the previous blueprint and add the corresponding views.
     *
     * @param previousBlueprint: KeyguardBlueprint the blueprint we are transitioning from.
     * @param constraintLayout: The parent view.
     * @param bindData: Whether to bind the data or not.
     */
    fun replaceViews(
        constraintLayout: ConstraintLayout,
        previousBlueprint: KeyguardBlueprint? = null,
        rebuildSections: List<KeyguardSection> = listOf(),
        bindData: Boolean = true
    ) {
        rebuildSections.forEach { it.onRebuildBegin() }
        val prevSections = previousBlueprint?.sections ?: listOf()
        val skipSections = sections.intersect(prevSections).subtract(rebuildSections)
        prevSections.subtract(skipSections).forEach { it.removeViews(constraintLayout) }
        sections.subtract(skipSections).forEach {
            it.addViews(constraintLayout)
            if (bindData) {
                it.bindData(constraintLayout)
            }
        }
        rebuildSections.forEach { it.onRebuildEnd() }
    }

    /** Rebuilds views for the target sections, or all of them if unspecified. */
    fun rebuildViews(
        constraintLayout: ConstraintLayout,
        rebuildSections: List<KeyguardSection> = sections,
        bindData: Boolean = true
    ) {
        if (rebuildSections.isEmpty()) {
            return
        }

        rebuildSections.forEach { it.onRebuildBegin() }
        rebuildSections.forEach { it.removeViews(constraintLayout) }
        rebuildSections.forEach {
            it.addViews(constraintLayout)
            if (bindData) {
                it.bindData(constraintLayout)
            }
        }
        rebuildSections.forEach { it.onRebuildEnd() }
    }

    fun applyConstraints(constraintSet: ConstraintSet) {
        sections.forEach { it.applyConstraints(constraintSet) }
    }
}
