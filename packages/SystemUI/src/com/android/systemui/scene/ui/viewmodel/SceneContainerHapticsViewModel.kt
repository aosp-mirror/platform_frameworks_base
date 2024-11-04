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

package com.android.systemui.scene.ui.viewmodel

import android.view.HapticFeedbackConstants
import android.view.View
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.MSDLPlayer
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Models haptics UI state for the scene container.
 *
 * This model gets a [View] to play haptics using the [View.performHapticFeedback] API. This should
 * be the only purpose of this reference.
 */
class SceneContainerHapticsViewModel
@AssistedInject
constructor(
    @Assisted private val view: View,
    sceneInteractor: SceneInteractor,
    shadeInteractor: ShadeInteractor,
    private val msdlPlayer: MSDLPlayer,
) : ExclusiveActivatable() {

    /** Should haptics be played by pulling down the shade */
    private val isShadePullHapticsRequired: Flow<Boolean> =
        combine(shadeInteractor.isUserInteracting, sceneInteractor.transitionState) {
                interacting,
                transitionState ->
                interacting && transitionState.isValidForShadePullHaptics()
            }
            .distinctUntilChanged()

    override suspend fun onActivated(): Nothing {
        isShadePullHapticsRequired.collect { playShadePullHaptics ->
            if (!playShadePullHaptics) return@collect

            if (Flags.msdlFeedback()) {
                msdlPlayer.playToken(MSDLToken.SWIPE_THRESHOLD_INDICATOR)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }
        }
        awaitCancellation()
    }

    private fun ObservableTransitionState.isValidForShadePullHaptics(): Boolean {
        val validOrigin =
            isTransitioning(from = Scenes.Gone) || isTransitioning(from = Scenes.Lockscreen)
        val validDestination =
            isTransitioning(to = Scenes.Shade) ||
                isTransitioning(to = Scenes.QuickSettings) ||
                isTransitioning(to = Overlays.QuickSettingsShade) ||
                isTransitioning(to = Overlays.NotificationsShade)
        return validOrigin && validDestination
    }

    @AssistedFactory
    interface Factory {
        fun create(view: View): SceneContainerHapticsViewModel
    }
}
