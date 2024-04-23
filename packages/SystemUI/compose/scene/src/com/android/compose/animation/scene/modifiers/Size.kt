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

package com.android.compose.animation.scene.modifiers

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.Constraints
import com.android.compose.animation.scene.SceneTransitionLayoutState

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.noResizeDuringTransitions(layoutState: SceneTransitionLayoutState): Modifier {
    return approachLayout(isMeasurementApproachInProgress = { layoutState.isTransitioning() }) {
        measurable,
        constraints ->
        if (layoutState.currentTransition == null) {
            return@approachLayout measurable.measure(constraints).run {
                layout(width, height) { place(0, 0) }
            }
        }

        // Make sure that this layout node has the same size than when we are at rest.
        val sizeAtRest = lookaheadSize
        measurable.measure(Constraints.fixed(sizeAtRest.width, sizeAtRest.height)).run {
            layout(width, height) { place(0, 0) }
        }
    }
}
