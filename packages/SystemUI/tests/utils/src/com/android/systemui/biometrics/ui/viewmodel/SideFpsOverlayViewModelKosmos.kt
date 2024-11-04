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

package com.android.systemui.biometrics.ui.viewmodel

import android.content.applicationContext
import com.android.systemui.biometrics.domain.interactor.displayStateInteractor
import com.android.systemui.biometrics.domain.interactor.sideFpsSensorInteractor
import com.android.systemui.keyguard.domain.interactor.deviceEntrySideFpsOverlayInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
val Kosmos.sideFpsOverlayViewModel by Fixture {
    SideFpsOverlayViewModel(
        applicationContext = applicationContext,
        deviceEntrySideFpsOverlayInteractor = deviceEntrySideFpsOverlayInteractor,
        displayStateInteractor = displayStateInteractor,
        sfpsSensorInteractor = sideFpsSensorInteractor,
    )
}
