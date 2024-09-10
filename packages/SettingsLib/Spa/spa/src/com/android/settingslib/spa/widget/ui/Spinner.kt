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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled

data class SpinnerOption(
    val id: Int,
    val text: String,
)

@Composable
fun Spinner(options: List<SpinnerOption>, selectedId: Int?, setId: (id: Int) -> Unit) {
    if (options.isEmpty()) {
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .padding(
                start = SettingsDimension.itemPaddingStart,
                top = SettingsDimension.itemPaddingAround,
                end = SettingsDimension.itemPaddingEnd,
                bottom = SettingsDimension.itemPaddingAround,
            )
            .selectableGroup(),
    ) {
        val contentPadding = if (isSpaExpressiveEnabled) PaddingValues(
            horizontal = SettingsDimension.spinnerHorizontalPadding,
            vertical = SettingsDimension.spinnerVerticalPadding
        ) else PaddingValues(horizontal = SettingsDimension.itemPaddingEnd)
        Button(
            modifier = Modifier.semantics { role = Role.DropdownList },
            onClick = { expanded = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            contentPadding = contentPadding,
        ) {
            SpinnerText(options.find { it.id == selectedId })
            ExpandIcon(expanded)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            for (option in options) {
                DropdownMenuItem(
                    text = {
                        SpinnerText(
                            option = option,
                            modifier = Modifier.padding(end = 24.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                    onClick = {
                        expanded = false
                        setId(option.id)
                    },
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@Composable
internal fun ExpandIcon(expanded: Boolean) {
    Icon(
        imageVector = when {
            expanded -> Icons.Outlined.ExpandLess
            else -> Icons.Outlined.ExpandMore
        },
        contentDescription = null,
    )
}

@Composable
private fun SpinnerText(
    option: SpinnerOption?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = option?.text ?: "",
        modifier = modifier
            .padding(end = SettingsDimension.itemPaddingEnd)
            .then(
                if (!isSpaExpressiveEnabled)
                    Modifier.padding(vertical = SettingsDimension.itemPaddingAround)
                else Modifier
            ),
        color = color,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Preview(showBackground = true)
@Composable
private fun SpinnerPreview() {
    SettingsTheme {
        var selectedId by rememberSaveable { mutableIntStateOf(1) }
        Spinner(
            options = (1..3).map { SpinnerOption(id = it, text = "Option $it") },
            selectedId = selectedId,
            setId = { selectedId = it },
        )
    }
}
