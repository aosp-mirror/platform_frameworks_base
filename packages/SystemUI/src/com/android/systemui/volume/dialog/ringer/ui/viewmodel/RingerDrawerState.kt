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

package com.android.systemui.volume.dialog.ringer.ui.viewmodel

import com.android.settingslib.volume.shared.model.RingerMode

/** Models volume dialog ringer drawer state */
sealed interface RingerDrawerState {

    /** When clicked to open drawer */
    data class Open(val mode: RingerMode) : RingerDrawerState

    /** When clicked to close drawer */
    data class Closed(val currentMode: RingerMode, val previousMode: RingerMode) :
        RingerDrawerState

    /** Initial state when volume dialog is shown with a closed drawer. */
    interface Initial : RingerDrawerState {
        companion object : Initial
    }
}
