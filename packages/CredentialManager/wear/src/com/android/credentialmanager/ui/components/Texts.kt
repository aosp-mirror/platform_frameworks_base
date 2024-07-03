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

package com.android.credentialmanager.common.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme as WearMaterialTheme

@Composable
fun WearTitleText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = WearMaterialTheme.colors.onSurface,
        textAlign = TextAlign.Center,
        style = WearMaterialTheme.typography.title3,
    )
}

@Composable
fun WearDisplayNameText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = WearMaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
        style = WearMaterialTheme.typography.title2,
    )
}

@Composable
fun WearUsernameText(
    text: String,
    textAlign: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.padding(start = 8.dp, end = 8.dp).wrapContentSize(),
        text = text,
        color = WearMaterialTheme.colors.onSurfaceVariant,
        style = WearMaterialTheme.typography.caption1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        maxLines = 2,
    )
}

// used for primary label in button
@Composable
fun WearButtonText(
    text: String,
    textAlign: TextAlign,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    color: Color = WearMaterialTheme.colors.onSurface,
) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = color,
        style = WearMaterialTheme.typography.button,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        maxLines = maxLines,
    )
}

@Composable
fun WearSecondaryLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = WearMaterialTheme.colors.onSurfaceVariant,
        style = WearMaterialTheme.typography.caption1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Start,
        maxLines = 1,
    )
}
