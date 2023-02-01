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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.credentialmanager.ui.theme.EntryShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Entry(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
) {
    SuggestionChip(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = EntryShape.FullSmallRoundedCorner,
        label = label,
        icon = icon,
        border = null,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransparentBackgroundEntry(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
) {
    SuggestionChip(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        label = label,
        icon = icon,
        border = null,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = Color.Transparent,
        ),
    )
}