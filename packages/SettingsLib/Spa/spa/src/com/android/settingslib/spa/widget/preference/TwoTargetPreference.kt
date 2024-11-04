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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.divider
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.framework.util.wrapOnClickWithLog

@Composable
internal fun TwoTargetPreference(
    title: String,
    summary: () -> String,
    primaryEnabled: () -> Boolean = { true },
    primaryOnClick: (() -> Unit)?,
    icon: @Composable (() -> Unit)? = null,
    widget: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (isSpaExpressiveEnabled)
                        Modifier.background(MaterialTheme.colorScheme.surfaceBright)
                    else Modifier
                )
                .padding(end = SettingsDimension.itemPaddingEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSpaExpressiveEnabled) {
            val onClickWithLog = wrapOnClickWithLog(primaryOnClick)
            val enabled = primaryEnabled()
            val modifier =
                if (onClickWithLog != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClickWithLog)
                } else Modifier
            Box(modifier = Modifier.weight(1f)) {
                BasePreference(
                    title = title,
                    summary = summary,
                    icon = icon,
                    enabled = primaryEnabled,
                    modifier = modifier,
                    paddingEnd = 0.dp,
                ) {
                    val alphaModifier = Modifier.alphaForEnabled(primaryEnabled())
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = alphaModifier.size(18.dp),
                    )
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                Preference(
                    object : PreferenceModel {
                        override val title = title
                        override val summary = summary
                        override val icon = icon
                        override val enabled = primaryEnabled
                        override val onClick = primaryOnClick
                    }
                )
            }
        }
        PreferenceDivider()
        widget()
    }
}

@Composable
private fun PreferenceDivider() {
    Box(
        Modifier.padding(horizontal = SettingsDimension.itemPaddingEnd)
            .size(width = 1.dp, height = SettingsDimension.itemDividerHeight)
            .background(color = MaterialTheme.colorScheme.divider)
    )
}
