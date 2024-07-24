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

package com.android.systemui.deviceentry.ui.binder

import android.content.packageManager
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.util.sensors.asyncSensorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
val Kosmos.liftToRunFaceAuthBinder by
    Kosmos.Fixture {
        LiftToRunFaceAuthBinder(
            scope = applicationCoroutineScope,
            packageManager = packageManager,
            asyncSensorManager = asyncSensorManager,
            keyguardUpdateMonitor = keyguardUpdateMonitor,
            keyguardInteractor = keyguardInteractor,
            primaryBouncerInteractor = primaryBouncerInteractor,
            alternateBouncerInteractor = alternateBouncerInteractor,
            deviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor,
            powerInteractor = powerInteractor,
        )
    }
