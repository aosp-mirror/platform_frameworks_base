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

package com.android.credentialmanager.common.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import com.android.credentialmanager.R
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ActionButton(text: String, onClick: () -> Unit) {
    TextButton(
        modifier = Modifier.padding(vertical = 4.dp),
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
    ) {
        LargeLabelText(text = text)
    }
}

@Composable
fun ToggleVisibilityButton(modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    // default state is visibility off
    val toggleState: MutableState<Boolean> = remember { mutableStateOf(false) }

    IconButton(
        modifier = modifier,
        onClick = {
            toggleState.value = !toggleState.value
            onToggle(toggleState.value)
        }
    ) {
        Icon(
            imageVector = if (toggleState.value)
                Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            contentDescription = if (toggleState.value)
                stringResource(R.string.content_description_show_password) else
                stringResource(R.string.content_description_hide_password),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}