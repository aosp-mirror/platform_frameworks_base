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

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.ui.SettingsSwitch

/**
 * The widget model for [SwitchPreference] widget.
 */
interface SwitchPreferenceModel {
    /**
     * The title of this [SwitchPreference].
     */
    val title: String

    /**
     * The summary of this [SwitchPreference].
     */
    val summary: State<String>
        get() = stateOf("")

    /**
     * Indicates whether this [SwitchPreference] is checked.
     *
     * This can be `null` during the data loading before the data is available.
     */
    val checked: State<Boolean?>

    /**
     * Indicates whether this [SwitchPreference] is changeable.
     *
     * Not changeable [SwitchPreference] will be displayed in disabled style.
     */
    val changeable: State<Boolean>
        get() = stateOf(true)

    /**
     * The switch change handler of this [SwitchPreference].
     *
     * If `null`, this [SwitchPreference] is not [toggleable].
     */
    val onCheckedChange: ((newChecked: Boolean) -> Unit)?
}

/**
 * SwitchPreference widget.
 *
 * Data is provided through [SwitchPreferenceModel].
 */
@Composable
fun SwitchPreference(model: SwitchPreferenceModel) {
    InternalSwitchPreference(
        title = model.title,
        summary = model.summary,
        checked = model.checked,
        changeable = model.changeable,
        onCheckedChange = model.onCheckedChange,
    )
}

@Composable
internal fun InternalSwitchPreference(
    title: String,
    summary: State<String> = "".toState(),
    checked: State<Boolean?>,
    changeable: State<Boolean> = true.toState(),
    paddingStart: Dp = SettingsDimension.itemPaddingStart,
    paddingEnd: Dp = SettingsDimension.itemPaddingEnd,
    paddingVertical: Dp = SettingsDimension.itemPaddingVertical,
    onCheckedChange: ((newChecked: Boolean) -> Unit)?,
) {
    val checkedValue = checked.value
    val indication = LocalIndication.current
    val modifier = remember(checkedValue, changeable.value) {
        if (checkedValue != null && onCheckedChange != null) {
            Modifier.toggleable(
                value = checkedValue,
                interactionSource = MutableInteractionSource(),
                indication = indication,
                enabled = changeable.value,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
        } else Modifier
    }
    BasePreference(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = changeable,
        paddingStart = paddingStart,
        paddingEnd = paddingEnd,
        paddingVertical = paddingVertical,
    ) {
        SettingsSwitch(checked = checked, changeable = changeable)
    }
}

@Preview
@Composable
private fun SwitchPreferencePreview() {
    SettingsTheme {
        Column {
            InternalSwitchPreference(
                title = "Use Dark theme",
                checked = true.toState(),
                onCheckedChange = {},
            )
            InternalSwitchPreference(
                title = "Use Dark theme",
                summary = "Summary".toState(),
                checked = false.toState(),
                onCheckedChange = {},
            )
        }
    }
}
