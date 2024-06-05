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

import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.hideAffordancesRequest
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Default view model for the UdfpsTouchOverlay.
 *
 * By default, don't handle touches if any of the following are true:
 * - shade is fully or partially expanded
 * - any SysUI dialogs are obscuring the display
 */
@ExperimentalCoroutinesApi
class DefaultUdfpsTouchOverlayViewModel
@Inject
constructor(
    shadeInteractor: ShadeInteractor,
    systemUIDialogManager: SystemUIDialogManager,
) : UdfpsTouchOverlayViewModel {
    private val shadeExpandedOrExpanding: Flow<Boolean> = shadeInteractor.isAnyExpanded
    override val shouldHandleTouches: Flow<Boolean> =
        combine(
            shadeExpandedOrExpanding,
            systemUIDialogManager.hideAffordancesRequest,
        ) { shadeExpandedOrExpanding, dialogRequestingHideAffordances ->
            !shadeExpandedOrExpanding && !dialogRequestingHideAffordances
        }
}
