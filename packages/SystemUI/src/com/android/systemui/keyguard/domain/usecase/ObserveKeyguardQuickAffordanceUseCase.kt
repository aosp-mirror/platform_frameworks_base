/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.usecase

import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePosition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Use-case for observing the model of a quick affordance in the keyguard. */
class ObserveKeyguardQuickAffordanceUseCase
@Inject
constructor(
    private val repository: KeyguardQuickAffordanceRepository,
    private val isDozingUseCase: ObserveIsDozingUseCase,
    private val dozeAmountUseCase: ObserveDozeAmountUseCase,
) {
    operator fun invoke(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceModel> {
        return combine(
            repository.affordance(position),
            isDozingUseCase(),
            dozeAmountUseCase(),
        ) { affordance, isDozing, dozeAmount ->
            if (!isDozing && dozeAmount == 0f) {
                affordance
            } else {
                KeyguardQuickAffordanceModel.Hidden
            }
        }
    }
}
