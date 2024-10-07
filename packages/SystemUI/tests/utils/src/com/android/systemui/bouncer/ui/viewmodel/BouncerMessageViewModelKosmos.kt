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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.bouncer.ui.viewmodel

import android.content.applicationContext
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.deviceentry.domain.interactor.biometricMessageInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryBiometricsAllowedInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.user.ui.viewmodel.userSwitcherViewModel
import com.android.systemui.util.time.systemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.bouncerMessageViewModel by Fixture {
    BouncerMessageViewModel(
        applicationContext = applicationContext,
        bouncerInteractor = bouncerInteractor,
        simBouncerInteractor = simBouncerInteractor,
        authenticationInteractor = authenticationInteractor,
        userSwitcherViewModel = userSwitcherViewModel,
        clock = systemClock,
        biometricMessageInteractor = biometricMessageInteractor,
        faceAuthInteractor = deviceEntryFaceAuthInteractor,
        deviceUnlockedInteractor = deviceUnlockedInteractor,
        deviceEntryBiometricsAllowedInteractor = deviceEntryBiometricsAllowedInteractor,
    )
}

val Kosmos.bouncerMessageViewModelFactory by Fixture {
    object : BouncerMessageViewModel.Factory {
        override fun create(): BouncerMessageViewModel {
            return bouncerMessageViewModel
        }
    }
}
