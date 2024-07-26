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
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.TransitionState
import com.android.systemui.scene.shared.model.Scenes

/** [ElementScenePicker] implementation for the media carousel object. */
object MediaScenePicker : ElementScenePicker {

    const val SHADE_FRACTION = 0.66f
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
            shouldElevateMedia(transition) -> {
                Scenes.Shade
            }
            transition.isTransitioningBetween(Scenes.Lockscreen, Scenes.Communal) -> {
                Scenes.Lockscreen
            }
            transition.isTransitioningBetween(Scenes.QuickSettings, Scenes.Shade) -> {
                Scenes.QuickSettings
            }
            else -> {
                when {
                    scenes.contains(transition.toScene) -> transition.toScene
                    scenes.contains(transition.fromScene) -> transition.fromScene
                    else -> null
                }
            }
        }
    }

    /** Returns true when the media should be laid on top of the rest for the given [transition]. */
    fun shouldElevateMedia(transition: TransitionState.Transition): Boolean {
        return transition.isTransitioningBetween(Scenes.Lockscreen, Scenes.Shade)
    }
}

fun MediaScenePicker.shouldElevateMedia(layoutState: SceneTransitionLayoutState): Boolean {
    return layoutState.currentTransition?.let { shouldElevateMedia(it) } ?: false
}
