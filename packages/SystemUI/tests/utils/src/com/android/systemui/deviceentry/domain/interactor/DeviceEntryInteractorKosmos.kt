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

package com.android.systemui.deviceentry.domain.interactor

import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.deviceentry.data.repository.deviceEntryRepository
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
val Kosmos.deviceEntryInteractor by
    Kosmos.Fixture {
        DeviceEntryInteractor(
            applicationScope = applicationCoroutineScope,
            repository = deviceEntryRepository,
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
            deviceUnlockedInteractor = deviceUnlockedInteractor,
            alternateBouncerInteractor = alternateBouncerInteractor,
            dismissCallbackRegistry = dismissCallbackRegistry,
            sceneBackInteractor = sceneBackInteractor,
        )
    }
