/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.usecase

import com.android.systemui.keyguard.data.repository.KeyguardRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use-case for observing whether the keyguard is currently being shown.
 *
 * Note: this is also `true` when the lock-screen is occluded with an `Activity` "above" it in the
 * z-order (which is not really above the system UI window, but rather - the lock-screen becomes
 * invisible to reveal the "occluding activity").
 */
class ObserveIsKeyguardShowingUseCase
@Inject
constructor(
    private val repository: KeyguardRepository,
) {
    operator fun invoke(): Flow<Boolean> {
        return repository.isKeyguardShowing
    }
}
