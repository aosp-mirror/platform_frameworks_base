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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.shade.domain.interactor

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.SysuiStatusBarStateController
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@SysUISingleton
class PanelExpansionInteractorImpl
@Inject
constructor(
    private val sceneInteractor: SceneInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val shadeAnimationInteractor: ShadeAnimationInteractor,
    private val statusBarStateController: SysuiStatusBarStateController,
) : PanelExpansionInteractor {

    /**
     * The amount by which the "panel" has been expanded (`0` when fully collapsed, `1` when fully
     * expanded).
     *
     * This is a legacy concept from the time when the "panel" included the notification/QS shades
     * as well as the keyguard (lockscreen and bouncer). This value is meant only for
     * backwards-compatibility and should not be consumed by newer code.
     */
    @Deprecated("Use SceneInteractor.currentScene instead.")
    override val legacyPanelExpansion: Flow<Float> =
        sceneInteractor.transitionState.flatMapLatest { state ->
            when (state) {
                is ObservableTransitionState.Idle ->
                    flowOf(
                        if (state.currentScene != Scenes.Gone) {
                            // When resting on a non-Gone scene, the panel is fully expanded.
                            1f
                        } else {
                            // When resting on the Gone scene, the panel is considered fully
                            // collapsed.
                            0f
                        }
                    )
                is ObservableTransitionState.Transition ->
                    when {
                        state.fromScene == Scenes.Gone ->
                            if (state.toScene.isExpandable()) {
                                // Moving from Gone to a scene that can animate-expand has a
                                // panel expansion that tracks with the transition.
                                state.progress
                            } else {
                                // Moving from Gone to a scene that doesn't animate-expand
                                // immediately makes the panel fully expanded.
                                flowOf(1f)
                            }
                        state.toScene == Scenes.Gone ->
                            if (state.fromScene.isExpandable()) {
                                // Moving to Gone from a scene that can animate-expand has a
                                // panel expansion that tracks with the transition.
                                state.progress.map { 1 - it }
                            } else {
                                // Moving to Gone from a scene that doesn't animate-expand
                                // immediately makes the panel fully collapsed.
                                flowOf(0f)
                            }
                        else -> flowOf(1f)
                    }
            }
        }

    @Deprecated(
        "depends on the state you check, use {@link #isShadeFullyExpanded()},\n" +
            "{@link #isOnAod()}, {@link #isOnKeyguard()} instead."
    )
    override val isFullyExpanded
        get() = shadeInteractor.isAnyFullyExpanded.value

    @Deprecated("Use !ShadeInteractor.isAnyExpanded instead")
    override val isFullyCollapsed
        get() = !shadeInteractor.isAnyExpanded.value

    @Deprecated("Use ShadeAnimationInteractor instead")
    override val isCollapsing
        get() =
            shadeAnimationInteractor.isAnyCloseAnimationRunning.value ||
                shadeAnimationInteractor.isLaunchingActivity.value

    @Deprecated("Use sceneInteractor.isTransitionUserInputOngoing instead")
    override val isTracking
        get() = sceneInteractor.isTransitionUserInputOngoing.value

    @Deprecated("Use ShadeInteractor.isAnyExpanded instead.")
    override val isPanelExpanded
        get() = shadeInteractor.isAnyExpanded.value

    @Deprecated("Use SceneInteractor or ShadeInteractor instead")
    override val barState
        get() = statusBarStateController.state

    @Deprecated("No longer supported. Do not add new calls to this.")
    override fun shouldHideStatusBarIconsWhenExpanded(): Boolean {
        if (shadeAnimationInteractor.isLaunchingActivity.value) {
            return false
        }
        // TODO(b/325936094) if a HUN is showing, return false
        return sceneInteractor.currentScene.value == Scenes.Lockscreen
    }

    private fun SceneKey.isExpandable(): Boolean {
        return this == Scenes.Shade || this == Scenes.QuickSettings
    }
}
