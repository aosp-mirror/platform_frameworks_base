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

package com.android.systemui.keyguard.ui.binder

import android.content.applicationContext
import android.view.layoutInflater
import android.view.mockedLayoutInflater
import android.view.windowManager
import com.android.systemui.biometrics.domain.interactor.fingerprintPropertyInteractor
import com.android.systemui.biometrics.domain.interactor.udfpsOverlayInteractor
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryUdfpsInteractor
import com.android.systemui.deviceentry.ui.viewmodel.AlternateBouncerUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.ui.SwipeUpAnywhereGestureHandler
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerMessageAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerUdfpsIconViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.alternateBouncerViewModel
import com.android.systemui.keyguard.ui.viewmodel.alternateBouncerWindowViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
val Kosmos.alternateBouncerViewBinder by
    Kosmos.Fixture {
        AlternateBouncerViewBinder(
            applicationScope = applicationCoroutineScope,
            alternateBouncerWindowViewModel = { alternateBouncerWindowViewModel },
            alternateBouncerDependencies = { alternateBouncerDependencies },
            windowManager = { windowManager },
            layoutInflater = { mockedLayoutInflater },
        )
    }

private val Kosmos.alternateBouncerDependencies by
    Kosmos.Fixture {
        AlternateBouncerDependencies(
            viewModel = mock<AlternateBouncerViewModel>(),
            swipeUpAnywhereGestureHandler = mock<SwipeUpAnywhereGestureHandler>(),
            tapGestureDetector = mock<TapGestureDetector>(),
            udfpsIconViewModel = alternateBouncerUdfpsIconViewModel,
            udfpsAccessibilityOverlayViewModel = {
                mock<AlternateBouncerUdfpsAccessibilityOverlayViewModel>()
            },
            messageAreaViewModel = mock<AlternateBouncerMessageAreaViewModel>(),
            powerInteractor = powerInteractor,
        )
    }

private val Kosmos.alternateBouncerUdfpsIconViewModel by
    Kosmos.Fixture {
        AlternateBouncerUdfpsIconViewModel(
            context = applicationContext,
            configurationInteractor = configurationInteractor,
            deviceEntryUdfpsInteractor = deviceEntryUdfpsInteractor,
            deviceEntryBackgroundViewModel = mock<DeviceEntryBackgroundViewModel>(),
            fingerprintPropertyInteractor = fingerprintPropertyInteractor,
            udfpsOverlayInteractor = udfpsOverlayInteractor,
            alternateBouncerViewModel = alternateBouncerViewModel,
        )
    }
