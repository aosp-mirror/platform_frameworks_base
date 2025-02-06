/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.scene.ui.composable

import com.android.compose.animation.scene.SceneTransitions
import com.android.compose.animation.scene.reveal.ContainerRevealHaptics
import com.android.compose.animation.scene.transitions

/**
 * Builder of the comprehensive definition of all transitions between scenes and overlays in the
 * scene container.
 */
interface SceneContainerTransitionsBuilder {

    /** Build the [SceneContainer] transitions spec. */
    fun build(revealHaptics: ContainerRevealHaptics): SceneTransitions
}

/**
 * Implementation of [SceneContainerTransitionsBuilder] that returns a constant [SceneTransitions]
 * instance, ignoring any parameters passed to [build].
 */
class ConstantSceneContainerTransitionsBuilder(
    private val transitions: SceneTransitions = transitions { /* No transitions */ }
) : SceneContainerTransitionsBuilder {
    override fun build(revealHaptics: ContainerRevealHaptics): SceneTransitions = transitions
}
