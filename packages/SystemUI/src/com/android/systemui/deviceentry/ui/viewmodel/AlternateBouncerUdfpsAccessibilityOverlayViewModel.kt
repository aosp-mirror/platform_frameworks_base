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
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Models the UI state for the alternate bouncer UDFPS accessibility overlay */
@ExperimentalCoroutinesApi
class AlternateBouncerUdfpsAccessibilityOverlayViewModel
@Inject
constructor(
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
    accessibilityInteractor: AccessibilityInteractor,
) :
    UdfpsAccessibilityOverlayViewModel(
        udfpsOverlayInteractor,
        accessibilityInteractor,
    ) {
    /** Overlay is always visible if touch exploration is enabled on the alternate bouncer. */
    override fun isVisibleWhenTouchExplorationEnabled(): Flow<Boolean> = flowOf(true)
}
