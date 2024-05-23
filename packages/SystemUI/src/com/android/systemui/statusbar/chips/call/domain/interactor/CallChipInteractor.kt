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

package com.android.systemui.statusbar.chips.call.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.chips.domain.interactor.OngoingActivityChipInteractor
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Interactor for the ongoing phone call chip shown in the status bar. */
@SysUISingleton
open class CallChipInteractor @Inject constructor() : OngoingActivityChipInteractor {
    // TODO(b/332662551): Implement this flow.
    override val chip: StateFlow<OngoingActivityChipModel> =
        MutableStateFlow(OngoingActivityChipModel.Hidden)
}
