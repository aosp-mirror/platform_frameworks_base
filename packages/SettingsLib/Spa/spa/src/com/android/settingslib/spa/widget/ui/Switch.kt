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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.android.settingslib.spa.framework.compose.contentDescription
import com.android.settingslib.spa.framework.util.wrapOnSwitchWithLog

@Composable
internal fun SettingsSwitch(
    checked: Boolean?,
    changeable: () -> Boolean,
    contentDescription: String? = null,
    onCheckedChange: ((newChecked: Boolean) -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    if (checked != null) {
        Switch(
            checked = checked,
            onCheckedChange = wrapOnSwitchWithLog(onCheckedChange),
            modifier = Modifier.contentDescription(contentDescription),
            enabled = changeable(),
            interactionSource = interactionSource,
        )
    } else {
        Switch(
            checked = false,
            onCheckedChange = null,
            enabled = false,
            interactionSource = interactionSource,
        )
    }
}
