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
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.controller.MediaLocation
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import kotlin.math.min

object MediaCarouselStateLoader {

    /** Sets current state for media carousel. */
    fun loadCarouselState(carouselController: MediaCarouselController, state: State) {
        if (state is State.Gone) return

        carouselController.setCurrentState(
            state.startLocation,
            state.endLocation,
            state.transitionProgress,
            immediately = true,
        )
    }

    /** Returns the corresponding media location for the given [scene] */
    @MediaLocation
    private fun getMediaLocation(scene: SceneKey, isSplitShade: Boolean): Int {
        return when (scene) {
            Scenes.QuickSettings -> MediaHierarchyManager.LOCATION_QS
            Scenes.Shade -> {
                if (isSplitShade) MediaHierarchyManager.LOCATION_QS
                else MediaHierarchyManager.LOCATION_QQS
            }
            Scenes.Lockscreen -> MediaHierarchyManager.LOCATION_LOCKSCREEN
            Scenes.Communal -> MediaHierarchyManager.LOCATION_COMMUNAL_HUB
            else -> MediaHierarchyManager.LOCATION_UNKNOWN
        }
    }

    /** Returns the corresponding media location for the given [content] */
    @MediaLocation
    private fun getMediaLocation(content: ContentKey): Int {
        return when (content) {
            Overlays.QuickSettingsShade -> MediaHierarchyManager.LOCATION_QS
            Overlays.NotificationsShade -> MediaHierarchyManager.LOCATION_QQS
            else -> MediaHierarchyManager.LOCATION_UNKNOWN
        }
    }

    /** State for media carousel. */
    sealed interface State {
        val transitionProgress: Float
        // TODO b/368368388: implement media squishiness
        val squishFraction: () -> Float
        @MediaLocation val startLocation: Int
        @MediaLocation val endLocation: Int

        /** State when media carousel is not visible on screen. */
        data object Gone : State {
            override val transitionProgress: Float = 1.0F
            override val squishFraction: () -> Float = { 1.0F }
            override val endLocation: Int = MediaHierarchyManager.LOCATION_UNKNOWN
            override val startLocation: Int = MediaHierarchyManager.LOCATION_UNKNOWN
        }

        /** State when media carousel is moving from one media location to another */
        data class InProgress(
            override val transitionProgress: Float,
            override val startLocation: Int,
            override val endLocation: Int,
        ) : State {
            override val squishFraction = { 1.0F }
        }

        /** State when media carousel reached the end location. */
        data class Idle(override val endLocation: Int) : State {
            override val transitionProgress = 1.0F
            override val startLocation = MediaHierarchyManager.LOCATION_UNKNOWN
            override val squishFraction = { 1.0F }
        }
    }

    /** Returns the state of media carousel */
    fun SceneScope.stateForMediaCarouselContent(isInSplitShade: Boolean): State {
        return when (val transitionState = layoutState.transitionState) {
            is TransitionState.Idle -> {
                if (MediaContentPicker.contents.contains(transitionState.currentScene)) {
                    State.Idle(getMediaLocation(transitionState.currentScene, isInSplitShade))
                } else {
                    State.Gone
                }
            }
            is TransitionState.Transition.ChangeScene ->
                with(transitionState) {
                    if (
                        MediaContentPicker.contents.contains(toScene) &&
                            MediaContentPicker.contents.contains(fromScene)
                    ) {
                        State.InProgress(
                            min(progress, 1.0F),
                            getMediaLocation(fromScene, isInSplitShade),
                            getMediaLocation(toScene, isInSplitShade),
                        )
                    } else if (MediaContentPicker.contents.contains(toScene)) {
                        State.InProgress(
                            transitionProgress = 1.0F,
                            startLocation = MediaHierarchyManager.LOCATION_UNKNOWN,
                            getMediaLocation(toScene, isInSplitShade),
                        )
                    } else {
                        State.Gone
                    }
                }
            is TransitionState.Transition.OverlayTransition ->
                with(transitionState) {
                    if (
                        MediaContentPicker.contents.contains(toContent) &&
                            MediaContentPicker.contents.contains(fromContent)
                    ) {
                        State.InProgress(
                            min(progress, 1.0F),
                            getMediaLocation(fromContent),
                            getMediaLocation(toContent),
                        )
                    } else if (MediaContentPicker.contents.contains(toContent)) {
                        State.InProgress(
                            transitionProgress = 1.0F,
                            startLocation = MediaHierarchyManager.LOCATION_UNKNOWN,
                            getMediaLocation(toContent),
                        )
                    } else {
                        State.Gone
                    }
                }
        }
    }
}
