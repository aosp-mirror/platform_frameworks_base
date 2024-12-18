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

package com.android.systemui.scene.domain.interactor

import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardOcclusionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Encapsulates logic regarding the occlusion state of the scene container. */
@SysUISingleton
class SceneContainerOcclusionInteractor
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    sceneInteractor: SceneInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    /**
     * Whether a show-when-locked activity is at the top of the current activity stack.
     *
     * Note: this isn't enough to figure out whether the scene container UI should be invisible as
     * that also depends on the things like the state of AOD and the current scene. If the code
     * needs that, [invisibleDueToOcclusion] should be collected instead.
     */
    val isOccludingActivityShown: StateFlow<Boolean> =
        keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false,
        )

    /**
     * Whether AOD is fully shown (not transitioning) or partially shown during a transition to/from
     * AOD.
     */
    private val isAodFullyOrPartiallyShown: StateFlow<Boolean> =
        keyguardTransitionInteractor
            .transitionValue(KeyguardState.AOD)
            .onStart { emit(0f) }
            .map { it > 0 }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /**
     * Whether the scene container should become invisible due to "occlusion" by an in-foreground
     * "show when locked" activity.
     *
     * Note: this returns `false` when an overlaid scene (like shade or QS) is shown above the
     * occluding activity.
     */
    val invisibleDueToOcclusion: StateFlow<Boolean> =
        combine(
                isOccludingActivityShown,
                sceneInteractor.transitionState,
                isAodFullyOrPartiallyShown,
            ) { isOccludingActivityShown, sceneTransitionState, isAodFullyOrPartiallyShown ->
                invisibleDueToOcclusion(
                    isOccludingActivityShown = isOccludingActivityShown,
                    sceneTransitionState = sceneTransitionState,
                    isAodFullyOrPartiallyShown = isAodFullyOrPartiallyShown,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    invisibleDueToOcclusion(
                        isOccludingActivityShown = isOccludingActivityShown.value,
                        sceneTransitionState = sceneInteractor.transitionState.value,
                        isAodFullyOrPartiallyShown = isAodFullyOrPartiallyShown.value,
                    ),
            )

    private fun invisibleDueToOcclusion(
        isOccludingActivityShown: Boolean,
        sceneTransitionState: ObservableTransitionState,
        isAodFullyOrPartiallyShown: Boolean,
    ): Boolean {
        return isOccludingActivityShown &&
            // Cannot be occluded in AOD.
            !isAodFullyOrPartiallyShown &&
            // Only some scenes can be occluded.
            sceneTransitionState.canBeOccluded
    }

    private val ObservableTransitionState.canBeOccluded: Boolean
        get() =
            when (this) {
                is ObservableTransitionState.Idle ->
                    currentOverlays.all { it.canBeOccluded } && currentScene.canBeOccluded
                is ObservableTransitionState.Transition ->
                    // TODO(b/356596436): Should also verify currentOverlays.isEmpty(), but
                    //  currentOverlays is a Flow and we need a state.
                    fromContent.canBeOccluded && toContent.canBeOccluded
            }

    /**
     * Whether the content can be occluded by a "show when locked" activity. Some content should, on
     * principle not be occlude-able because they render as if they are expanding on top of the
     * occluding activity.
     */
    private val ContentKey.canBeOccluded: Boolean
        get() =
            when (this) {
                Overlays.NotificationsShade -> false
                Overlays.QuickSettingsShade -> false
                Scenes.Bouncer -> false
                Scenes.Communal -> true
                Scenes.Gone -> true
                Scenes.Lockscreen -> true
                Scenes.QuickSettings -> false
                Scenes.Shade -> false
                else -> error("ContentKey \"$this\" doesn't have a mapping for canBeOccluded!")
            }
}
