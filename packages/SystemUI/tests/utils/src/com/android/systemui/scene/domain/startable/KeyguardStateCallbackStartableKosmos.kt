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

package com.android.systemui.scene.domain.startable

import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.trustInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.user.domain.interactor.selectedUserInteractor

val Kosmos.keyguardStateCallbackStartable by Fixture {
    KeyguardStateCallbackStartable(
        applicationScope = applicationCoroutineScope,
        backgroundDispatcher = testDispatcher,
        sceneInteractor = sceneInteractor,
        selectedUserInteractor = selectedUserInteractor,
        deviceEntryInteractor = deviceEntryInteractor,
        simBouncerInteractor = simBouncerInteractor,
        trustInteractor = trustInteractor,
    )
}
