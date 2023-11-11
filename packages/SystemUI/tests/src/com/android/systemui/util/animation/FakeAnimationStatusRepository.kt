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
package com.android.systemui.util.animation

import com.android.systemui.util.animation.data.repository.AnimationStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeAnimationStatusRepository : AnimationStatusRepository {

    // Replay 1 element as real repository always emits current status as a first element
    private val animationsEnabled: MutableSharedFlow<Boolean> = MutableSharedFlow(replay = 1)

    override fun areAnimationsEnabled(): Flow<Boolean> = animationsEnabled

    fun onAnimationStatusChanged(enabled: Boolean) {
        animationsEnabled.tryEmit(enabled)
    }
}
