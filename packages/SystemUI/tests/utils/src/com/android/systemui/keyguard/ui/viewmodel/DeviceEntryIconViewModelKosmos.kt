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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntrySourceInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.burnInInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.scene.shared.flag.sceneContainerFlags
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.fakeDeviceEntryIconViewModelTransition by Fixture { FakeDeviceEntryIconTransition() }

val Kosmos.deviceEntryIconViewModelTransitionsMock by Fixture {
    setOf<DeviceEntryIconTransition>(fakeDeviceEntryIconViewModelTransition)
}

@ExperimentalCoroutinesApi
val Kosmos.deviceEntryIconViewModel by Fixture {
    DeviceEntryIconViewModel(
        transitions = deviceEntryIconViewModelTransitionsMock,
        burnInInteractor = burnInInteractor,
        shadeInteractor = shadeInteractor,
        deviceEntryUdfpsInteractor = deviceEntryUdfpsInteractor,
        transitionInteractor = keyguardTransitionInteractor,
        keyguardInteractor = keyguardInteractor,
        viewModel = aodToLockscreenTransitionViewModel,
        sceneContainerFlags = sceneContainerFlags,
        keyguardViewController = { statusBarKeyguardViewManager },
        deviceEntryInteractor = deviceEntryInteractor,
        deviceEntrySourceInteractor = deviceEntrySourceInteractor,
    )
}
