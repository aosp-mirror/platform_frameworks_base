/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.composable

import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementScenePicker
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionState
import com.android.systemui.scene.shared.model.Scenes

/** [ElementScenePicker] implementation for the media carousel object. */
object MediaScenePicker : ElementScenePicker {

    private val shadeLockscreenFraction = 0.65f
    private val scenes =
        setOf(
            Scenes.Lockscreen,
            Scenes.Shade,
            Scenes.QuickSettings,
            Scenes.QuickSettingsShade,
            Scenes.Communal
        )

    override fun sceneDuringTransition(
        element: ElementKey,
        transition: TransitionState.Transition,
        fromSceneZIndex: Float,
        toSceneZIndex: Float
    ): SceneKey? {
        return when {
            // TODO: 352052894 - update with the actual scene picking
            transition.isTransitioning(from = Scenes.Lockscreen, to = Scenes.Shade) -> {
                if (transition.progress < shadeLockscreenFraction) {
                    Scenes.Lockscreen
                } else {
                    Scenes.Shade
                }
            }

            // TODO: 345467290 - update with the actual scene picking
            transition.isTransitioning(from = Scenes.Shade, to = Scenes.Lockscreen) -> {
                if (transition.progress < 1f - shadeLockscreenFraction) {
                    Scenes.Shade
                } else {
                    Scenes.Lockscreen
                }
            }

            // TODO: 345467290 - update with the actual scene picking
            transition.isTransitioningBetween(Scenes.QuickSettings, Scenes.Shade) -> {
                Scenes.QuickSettings
            }

            // TODO: 340216785 - update with the actual scene picking
            else -> pickSingleSceneIn(scenes, transition, element)
        }
    }
}
