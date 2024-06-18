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

package com.android.systemui.deviceentry.data.ui.viewmodel

import com.android.systemui.accessibility.domain.interactor.accessibilityInteractor
import com.android.systemui.biometrics.domain.interactor.udfpsOverlayInteractor
import com.android.systemui.deviceentry.ui.viewmodel.DeviceEntryUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.ui.viewmodel.deviceEntryForegroundIconViewModel
import com.android.systemui.keyguard.ui.viewmodel.deviceEntryIconViewModel
import com.android.systemui.kosmos.Kosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
val Kosmos.deviceEntryUdfpsAccessibilityOverlayViewModel by
    Kosmos.Fixture {
        DeviceEntryUdfpsAccessibilityOverlayViewModel(
            udfpsOverlayInteractor = udfpsOverlayInteractor,
            accessibilityInteractor = accessibilityInteractor,
            deviceEntryIconViewModel = deviceEntryIconViewModel,
            deviceEntryFgIconViewModel = deviceEntryForegroundIconViewModel,
        )
    }
