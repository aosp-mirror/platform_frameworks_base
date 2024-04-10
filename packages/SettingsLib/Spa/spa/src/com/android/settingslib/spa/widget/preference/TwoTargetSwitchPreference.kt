/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.widget.preference

import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.util.EntryHighlight
import com.android.settingslib.spa.widget.ui.SettingsSwitch

@Composable
fun TwoTargetSwitchPreference(
    model: SwitchPreferenceModel,
    primaryEnabled: () -> Boolean = { true },
    primaryOnClick: (() -> Unit)?,
) {
    EntryHighlight {
        TwoTargetPreference(
            title = model.title,
            summary = model.summary,
            primaryEnabled = primaryEnabled,
            primaryOnClick = primaryOnClick,
            icon = model.icon,
        ) {
            SettingsSwitch(
                checked = model.checked(),
                changeable = model.changeable,
                contentDescription = model.title,
                onCheckedChange = model.onCheckedChange,
            )
        }
    }
}
