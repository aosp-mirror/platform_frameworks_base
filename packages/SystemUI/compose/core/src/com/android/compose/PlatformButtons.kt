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
 *
 */

package com.android.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme

@Composable
fun PlatformButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = filledButtonColors(),
    verticalPadding: Dp = DefaultPlatformButtonVerticalPadding,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        modifier = modifier.padding(vertical = verticalPadding).height(36.dp),
        colors = colors,
        contentPadding = ButtonPaddings,
        onClick = onClick,
        enabled = enabled,
    ) {
        content()
    }
}

@Composable
fun PlatformOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = outlineButtonColors(),
    border: BorderStroke? = outlineButtonBorder(),
    verticalPadding: Dp = DefaultPlatformButtonVerticalPadding,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        modifier = modifier.padding(vertical = verticalPadding).height(36.dp),
        enabled = enabled,
        colors = colors,
        border = border,
        contentPadding = ButtonPaddings,
        onClick = onClick,
    ) {
        content()
    }
}

@Composable
fun PlatformTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
        colors = colors,
    )
}

private val DefaultPlatformButtonVerticalPadding = 6.dp
private val ButtonPaddings = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
private fun filledButtonColors(): ButtonColors {
    val colors = LocalAndroidColorScheme.current
    return ButtonDefaults.buttonColors(
        containerColor = colors.primary,
        contentColor = colors.onPrimary,
    )
}

@Composable
private fun outlineButtonColors(): ButtonColors {
    return ButtonDefaults.outlinedButtonColors(
        contentColor = LocalAndroidColorScheme.current.onSurface,
    )
}

@Composable
private fun outlineButtonBorder(): BorderStroke {
    return BorderStroke(
        width = 1.dp,
        color = LocalAndroidColorScheme.current.primary,
    )
}

@Composable
private fun textButtonColors(): ButtonColors {
    return ButtonDefaults.textButtonColors(contentColor = LocalAndroidColorScheme.current.primary)
}
