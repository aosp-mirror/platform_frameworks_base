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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

@Composable
fun TextOnSurface(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    style: TextStyle,
) {
    TextInternal(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
        textAlign = textAlign,
        style = style,
    )
}

@Composable
fun TextSecondary(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    style: TextStyle,
) {
    TextInternal(
        text = text,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
        textAlign = textAlign,
        style = style,
    )
}

@Composable
fun TextOnSurfaceVariant(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    style: TextStyle,
) {
    TextInternal(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        textAlign = textAlign,
        style = style,
    )
}

@Composable
private fun TextInternal(
    text: String,
    color: Color,
    modifier: Modifier,
    textAlign: TextAlign?,
    style: TextStyle,
) {
    Text(text = text, color = color, modifier = modifier, textAlign = textAlign, style = style)
}