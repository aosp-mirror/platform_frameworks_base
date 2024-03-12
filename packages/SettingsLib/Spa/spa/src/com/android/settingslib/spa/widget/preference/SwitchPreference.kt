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

package com.android.settingslib.spa.widget.preference

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.EntryHighlight
import com.android.settingslib.spa.framework.util.wrapOnSwitchWithLog
import com.android.settingslib.spa.widget.ui.SettingsIcon
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
    val summary: () -> String
        get() = { "" }

    /**
     * The icon of this [Preference].
     *
     * Default is `null` which means no icon.
     */
    val icon: (@Composable () -> Unit)?
        get() = null

    /**
     * Indicates whether this [SwitchPreference] is checked.
     *
     * This can be `null` during the data loading before the data is available.
     */
    val checked: () -> Boolean?

    /**
     * Indicates whether this [SwitchPreference] is changeable.
     *
     * Not changeable [SwitchPreference] will be displayed in disabled style.
     */
    val changeable: () -> Boolean
        get() = { true }

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
    EntryHighlight {
        InternalSwitchPreference(
            title = model.title,
            summary = model.summary,
            icon = model.icon,
            checked = model.checked(),
            changeable = model.changeable(),
            onCheckedChange = model.onCheckedChange,
        )
    }
}

@Composable
internal fun InternalSwitchPreference(
    title: String,
    summary: () -> String = { "" },
    icon: @Composable (() -> Unit)? = null,
    checked: Boolean?,
    changeable: Boolean = true,
    paddingStart: Dp = SettingsDimension.itemPaddingStart,
    paddingEnd: Dp = SettingsDimension.itemPaddingEnd,
    paddingVertical: Dp = SettingsDimension.itemPaddingVertical,
    onCheckedChange: ((newChecked: Boolean) -> Unit)?,
) {
    val indication = LocalIndication.current
    val onChangeWithLog = wrapOnSwitchWithLog(onCheckedChange)
    val interactionSource = remember { MutableInteractionSource() }
    val modifier = remember(checked, changeable) {
        if (checked != null && onChangeWithLog != null) {
            Modifier.toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = indication,
                enabled = changeable,
                role = Role.Switch,
                onValueChange = onChangeWithLog,
            )
        } else Modifier
    }
    BasePreference(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = { changeable },
        paddingStart = paddingStart,
        paddingEnd = paddingEnd,
        paddingVertical = paddingVertical,
        icon = icon,
    ) {
        Spacer(Modifier.width(SettingsDimension.itemPaddingEnd))
        SettingsSwitch(
            checked = checked,
            changeable = { changeable },
            // The onCheckedChange is handled on the whole SwitchPreference.
            // DO NOT set it on SettingsSwitch.
            onCheckedChange = null,
            interactionSource = interactionSource,
        )
    }
}

@Preview
@Composable
private fun SwitchPreferencePreview() {
    SettingsTheme {
        Column {
            InternalSwitchPreference(
                title = "Use Dark theme",
                checked = true,
                onCheckedChange = {},
            )
            InternalSwitchPreference(
                title = "Use Dark theme",
                summary = { "Summary" },
                checked = false,
                onCheckedChange = {},
            )
            InternalSwitchPreference(
                title = "Use Dark theme",
                summary = { "Summary" },
                checked = true,
                onCheckedChange = {},
                icon = @Composable {
                    SettingsIcon(imageVector = Icons.Outlined.AirplanemodeActive)
                },
            )
        }
    }
}
