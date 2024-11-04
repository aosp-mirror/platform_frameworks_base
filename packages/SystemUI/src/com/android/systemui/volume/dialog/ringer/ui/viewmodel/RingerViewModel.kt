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

/** Models volume dialog ringer */
data class RingerViewModel(
    /** List of the available buttons according to the available modes */
    val availableButtons: List<RingerButtonViewModel?>,
    /** The index of the currently selected button */
    val currentButtonIndex: Int,
    /** Currently selected button. */
    val selectedButton: RingerButtonViewModel,
    /** For open and close animations */
    val drawerState: RingerDrawerState,
)
