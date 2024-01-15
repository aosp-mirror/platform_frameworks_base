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

package com.android.systemui.bouncer.domain.interactor

import android.content.applicationContext
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.bouncer.data.repository.bouncerRepository
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.powerInteractor

val Kosmos.bouncerInteractor by Fixture {
    BouncerInteractor(
        applicationScope = testScope.backgroundScope,
        applicationContext = applicationContext,
        repository = bouncerRepository,
        authenticationInteractor = authenticationInteractor,
        deviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor,
        falsingInteractor = falsingInteractor,
        powerInteractor = powerInteractor,
        simBouncerInteractor = simBouncerInteractor,
    )
}
