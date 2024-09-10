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

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.android.compose.theme.LocalAndroidColorScheme

/**
 * The headline for a screen. E.g. "Create a passkey for X", "Choose a saved sign-in for X".
 *
 * Centered horizontally; headline-small typography; on-surface color.
 */
@Composable
fun HeadlineText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = LocalAndroidColorScheme.current.onSurface,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineSmall.copy(hyphens = Hyphens.Auto),
    )
}

/**
 * Body-medium typography; on-surface-variant color.
 */
@Composable
fun BodyMediumText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = LocalAndroidColorScheme.current.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium.copy(hyphens = Hyphens.Auto),
    )
}

/**
 * Body-small typography; on-surface-variant color.
 */
@Composable
fun BodySmallText(
    text: String,
    modifier: Modifier = Modifier,
    enforceOneLine: Boolean = false,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = LocalAndroidColorScheme.current.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(hyphens = Hyphens.Auto),
        overflow = TextOverflow.Ellipsis,
        maxLines = if (enforceOneLine) 1 else Int.MAX_VALUE,
        onTextLayout = onTextLayout,
    )
}

/**
 * Title-large typography; on-surface color.
 */
@Composable
fun LargeTitleText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = LocalAndroidColorScheme.current.onSurface,
        style = MaterialTheme.typography.titleLarge.copy(hyphens = Hyphens.Auto),
    )
}

/**
 * Title-small typography; on-surface color.
 */
@Composable
fun SmallTitleText(
    text: String,
    modifier: Modifier = Modifier,
    enforceOneLine: Boolean = false,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = LocalAndroidColorScheme.current.onSurface,
        style = MaterialTheme.typography.titleSmall.copy(hyphens = Hyphens.Auto),
        overflow = TextOverflow.Ellipsis,
        maxLines = if (enforceOneLine) 1 else Int.MAX_VALUE,
        onTextLayout = onTextLayout,
    )
}

/**
 * Title-small typography.
 */
@Composable
fun SectionHeaderText(text: String, modifier: Modifier = Modifier, color: Color) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = color,
        style = MaterialTheme.typography.titleSmall.copy(hyphens = Hyphens.Auto),
    )
}

/**
 * Body-medium typography; inverse-on-surface color.
 */
@Composable
fun SnackbarContentText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        style = MaterialTheme.typography.bodyMedium.copy(hyphens = Hyphens.Auto),
    )
}

/**
 * Label-large typography; inverse-primary color.
 */
@Composable
fun SnackbarActionText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        color = MaterialTheme.colorScheme.inversePrimary,
        style = MaterialTheme.typography.labelLarge.copy(hyphens = Hyphens.Auto),
    )
}

/**
 * Label-large typography; on-surface-variant color; centered.
 */
@Composable
fun LargeLabelTextOnSurfaceVariant(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        textAlign = TextAlign.Center,
        color = LocalAndroidColorScheme.current.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge.copy(hyphens = Hyphens.Auto),
    )
}

/**
 * Label-large typography; color following parent spec; centered.
 */
@Composable
fun LargeLabelText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.wrapContentSize(),
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge.copy(hyphens = Hyphens.Auto),
    )
}