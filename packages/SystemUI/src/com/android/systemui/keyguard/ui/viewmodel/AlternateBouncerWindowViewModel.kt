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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@ExperimentalCoroutinesApi
class AlternateBouncerWindowViewModel
@Inject
constructor(
    alternateBouncerInteractor: AlternateBouncerInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    val canShowAlternateBouncer: Flow<Boolean> = alternateBouncerInteractor.canShowAlternateBouncer

    private val isTransitioningToOrFromOrShowingAlternateBouncer: Flow<Boolean> =
        keyguardTransitionInteractor
            .transitionValue(KeyguardState.ALTERNATE_BOUNCER)
            .map { it > 0f }
            .distinctUntilChanged()

    val alternateBouncerWindowRequired: Flow<Boolean> =
        canShowAlternateBouncer.flatMapLatest { canShowAlternateBouncer ->
            if (canShowAlternateBouncer) {
                isTransitioningToOrFromOrShowingAlternateBouncer
            } else {
                flowOf(false)
            }
        }
}
