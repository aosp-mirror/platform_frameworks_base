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

import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementContentPicker
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.StaticElementContentPicker
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shared.flag.DualShade

/** [ElementContentPicker] implementation for the media carousel object. */
object MediaContentPicker : StaticElementContentPicker {

    override val contents =
        setOf(
            Overlays.NotificationsShade,
            Overlays.QuickSettingsShade,
            Scenes.Lockscreen,
            Scenes.Shade,
            Scenes.QuickSettings,
            Scenes.Communal,
        )

    override fun contentDuringTransition(
        element: ElementKey,
        transition: TransitionState.Transition,
        fromContentZIndex: Float,
        toContentZIndex: Float,
    ): ContentKey {
        return when {
            shouldElevateMedia(transition) -> {
                if (DualShade.isEnabled) Overlays.NotificationsShade else Scenes.Shade
            }
            transition.isTransitioningBetween(Scenes.Lockscreen, Scenes.Communal) -> {
                Scenes.Lockscreen
            }
            transition.isTransitioningBetween(Scenes.QuickSettings, Scenes.Shade) -> {
                Scenes.QuickSettings
            }
            transition.isTransitioningBetween(
                Overlays.QuickSettingsShade,
                Overlays.NotificationsShade,
            ) -> {
                Overlays.QuickSettingsShade
            }
            transition.toContent in contents -> transition.toContent
            else -> {
                check(transition.fromContent in contents) {
                    "Media player should not be composed for the transition from " +
                        "${transition.fromContent} to ${transition.toContent}"
                }
                transition.fromContent
            }
        }
    }

    /** Returns true when the media should be laid on top of the rest for the given [transition]. */
    fun shouldElevateMedia(transition: TransitionState.Transition): Boolean {
        return transition.isTransitioningBetween(Scenes.Lockscreen, Scenes.Shade) ||
            transition.isTransitioningBetween(Scenes.Lockscreen, Overlays.NotificationsShade)
    }
}

fun MediaContentPicker.shouldElevateMedia(layoutState: SceneTransitionLayoutState): Boolean {
    return layoutState.currentTransition?.let { shouldElevateMedia(it) } ?: false
}
