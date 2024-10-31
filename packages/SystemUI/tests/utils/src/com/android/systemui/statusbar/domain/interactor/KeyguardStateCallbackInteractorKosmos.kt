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

package com.android.systemui.statusbar.domain.interactor

import com.android.keyguard.trustManager
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.keyguard.domain.interactor.KeyguardStateCallbackInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.trustInteractor
import com.android.systemui.keyguard.domain.interactor.windowManagerLockscreenVisibilityInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.user.domain.interactor.selectedUserInteractor

val Kosmos.keyguardStateCallbackInteractor by
    Kosmos.Fixture {
        KeyguardStateCallbackInteractor(
            applicationScope = testScope.backgroundScope,
            backgroundDispatcher = testDispatcher,
            selectedUserInteractor = selectedUserInteractor,
            keyguardTransitionInteractor = keyguardTransitionInteractor,
            trustInteractor = trustInteractor,
            simBouncerInteractor = simBouncerInteractor,
            dismissCallbackRegistry = dismissCallbackRegistry,
            wmLockscreenVisibilityInteractor = windowManagerLockscreenVisibilityInteractor,
            trustManager = trustManager,
        )
    }
