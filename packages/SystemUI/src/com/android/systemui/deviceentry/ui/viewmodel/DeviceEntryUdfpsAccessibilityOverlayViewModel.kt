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

package com.android.systemui.deviceentry.ui.viewmodel

import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Models the UI state for the non-alternate bouncer UDFPS accessibility overlay */
@ExperimentalCoroutinesApi
class DeviceEntryUdfpsAccessibilityOverlayViewModel
@Inject
constructor(
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
    accessibilityInteractor: AccessibilityInteractor,
    private val deviceEntryIconViewModel: DeviceEntryIconViewModel,
    private val deviceEntryFgIconViewModel: DeviceEntryForegroundViewModel,
) :
    UdfpsAccessibilityOverlayViewModel(
        udfpsOverlayInteractor,
        accessibilityInteractor,
    ) {
    /** Overlay is only visible if the UDFPS icon is visible on the keyguard. */
    override fun isVisibleWhenTouchExplorationEnabled(): Flow<Boolean> =
        combine(
            deviceEntryFgIconViewModel.viewModel,
            deviceEntryIconViewModel.deviceEntryViewAlpha,
        ) { iconViewModel, alpha ->
            iconViewModel.type == DeviceEntryIconView.IconType.FINGERPRINT &&
                !iconViewModel.useAodVariant &&
                alpha == 1f
        }
}
