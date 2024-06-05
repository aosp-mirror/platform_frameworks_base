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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.UNDEFINED
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.onStart

/** Models UI state for the alpha of the AOD (always-on display). */
@SysUISingleton
class AodAlphaViewModel
@Inject
constructor(
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    goneToDozingTransitionViewModel: GoneToDozingTransitionViewModel,
    keyguardInteractor: KeyguardInteractor,
    sceneInteractor: SceneInteractor,
) {

    /** The alpha level for the entire lockscreen while in AOD. */
    val alpha: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
            combineTransform(
                keyguardTransitionInteractor.transitions,
                sceneInteractor.transitionState,
                goneToAodTransitionViewModel.enterFromTopAnimationAlpha.onStart { emit(0f) },
                goneToDozingTransitionViewModel.lockscreenAlpha.onStart { emit(0f) },
                keyguardInteractor.keyguardAlpha.onStart { emit(1f) },
            ) { step, sceneTransitionState, goneToAodAlpha, goneToDozingAlpha, keyguardAlpha ->
                if (sceneTransitionState.isIdle(Scenes.Gone)) {
                    emit(0f)
                } else if (
                    step.from == UNDEFINED &&
                        step.to == AOD &&
                        sceneTransitionState.isTransitioning(Scenes.Gone, Scenes.Lockscreen)
                ) {
                    emit(goneToAodAlpha)
                } else if (
                    step.from == UNDEFINED &&
                        step.to == DOZING &&
                        sceneTransitionState.isTransitioning(Scenes.Gone, Scenes.Lockscreen)
                ) {
                    emit(goneToDozingAlpha)
                } else if (!MigrateClocksToBlueprint.isEnabled) {
                    emit(keyguardAlpha)
                }
            }
        } else {
            combineTransform(
                keyguardTransitionInteractor.transitions,
                goneToAodTransitionViewModel.enterFromTopAnimationAlpha.onStart { emit(0f) },
                goneToDozingTransitionViewModel.lockscreenAlpha.onStart { emit(0f) },
                keyguardInteractor.keyguardAlpha.onStart { emit(1f) },
            ) { step, goneToAodAlpha, goneToDozingAlpha, keyguardAlpha ->
                if (step.to == GONE) {
                    // When transitioning to GONE, only emit a value when complete as other
                    // transitions may be controlling the alpha fade
                    if (step.value == 1f) {
                        emit(0f)
                    }
                } else if (step.from == GONE && step.to == AOD) {
                    emit(goneToAodAlpha)
                } else if (step.from == GONE && step.to == DOZING) {
                    emit(goneToDozingAlpha)
                } else if (!MigrateClocksToBlueprint.isEnabled) {
                    emit(keyguardAlpha)
                }
            }
        }
}
