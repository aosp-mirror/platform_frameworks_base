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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Helper to create a new KeyguardTransitionInteractor in a way that doesn't require modifying 20+
 * tests whenever we add a constructor param.
 */
object KeyguardTransitionInteractorFactory {
    @JvmOverloads
    @JvmStatic
    fun create(
        scope: CoroutineScope,
        repository: KeyguardTransitionRepository = FakeKeyguardTransitionRepository(),
    ): WithDependencies {
        return WithDependencies(
            repository = repository,
            KeyguardTransitionInteractor(
                scope = scope,
                repository = repository,
            )
        )
    }

    data class WithDependencies(
        val repository: KeyguardTransitionRepository,
        val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    )
}
