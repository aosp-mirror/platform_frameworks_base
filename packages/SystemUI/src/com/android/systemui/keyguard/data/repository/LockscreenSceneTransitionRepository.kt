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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class LockscreenSceneTransitionRepository @Inject constructor() {

    /**
     * This [KeyguardState] will indicate which sub state within KTF should be navigated to when the
     * next transition into the Lockscreen scene is started. It will be consumed exactly once and
     * after that the state will be set back to [DEFAULT_STATE].
     */
    val nextLockscreenTargetState: MutableStateFlow<KeyguardState> = MutableStateFlow(DEFAULT_STATE)

    companion object {
        val DEFAULT_STATE = KeyguardState.LOCKSCREEN
    }
}
