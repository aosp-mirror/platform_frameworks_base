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

package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for a view model that knows the display requirements for a single type of status bar
 * popup chip.
 */
interface StatusBarPopupChipViewModel {
    /** A flow modeling the popup chip that should be shown (or not shown). */
    val chip: StateFlow<PopupChipModel>
}
