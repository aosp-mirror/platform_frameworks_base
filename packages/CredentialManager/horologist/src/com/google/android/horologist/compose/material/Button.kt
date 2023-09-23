/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.horologist.compose.material

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonColors
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ButtonDefaults.DefaultButtonSize
import androidx.wear.compose.material.ButtonDefaults.DefaultIconSize
import androidx.wear.compose.material.ButtonDefaults.LargeButtonSize
import androidx.wear.compose.material.ButtonDefaults.LargeIconSize
import androidx.wear.compose.material.ButtonDefaults.SmallButtonSize
import androidx.wear.compose.material.ButtonDefaults.SmallIconSize
import com.google.android.horologist.annotations.ExperimentalHorologistApi

/**
 * This component is an alternative to [Button], providing the following:
 * - a convenient way of providing an icon and choosing its size from a range of sizes recommended
 * by the Wear guidelines;
 */
@ExperimentalHorologistApi
@Composable
public fun Button(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.primaryButtonColors(),
    buttonSize: ButtonSize = ButtonSize.Default,
    iconRtlMode: IconRtlMode = IconRtlMode.Default,
    enabled: Boolean = true,
) {
    Button(
        icon = imageVector,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        colors = colors,
        buttonSize = buttonSize,
        iconRtlMode = iconRtlMode,
        enabled = enabled,
    )
}

/**
 * This component is an alternative to [Button], providing the following:
 * - a convenient way of providing an icon and choosing its size from a range of sizes recommended
 * by the Wear guidelines;
 */
@ExperimentalHorologistApi
@Composable
public fun Button(
    @DrawableRes id: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.primaryButtonColors(),
    buttonSize: ButtonSize = ButtonSize.Default,
    iconRtlMode: IconRtlMode = IconRtlMode.Default,
    enabled: Boolean = true,
) {
    Button(
        icon = id,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        colors = colors,
        buttonSize = buttonSize,
        iconRtlMode = iconRtlMode,
        enabled = enabled,
    )
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
internal fun Button(
    icon: Any,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.primaryButtonColors(),
    buttonSize: ButtonSize = ButtonSize.Default,
    iconRtlMode: IconRtlMode = IconRtlMode.Default,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(buttonSize.tapTargetSize),
        enabled = enabled,
        colors = colors,
    ) {
        val iconModifier = Modifier
            .size(buttonSize.iconSize)
            .align(Alignment.Center)

        Icon(
            icon = icon,
            contentDescription = contentDescription,
            modifier = iconModifier,
            rtlMode = iconRtlMode,
        )
    }
}

@ExperimentalHorologistApi
public sealed class ButtonSize(
    public val iconSize: Dp,
    public val tapTargetSize: Dp,
) {
    public object Default :
        ButtonSize(iconSize = DefaultIconSize, tapTargetSize = DefaultButtonSize)

    public object Large : ButtonSize(iconSize = LargeIconSize, tapTargetSize = LargeButtonSize)
    public object Small : ButtonSize(iconSize = SmallIconSize, tapTargetSize = SmallButtonSize)

    /**
     * Custom sizes should follow the [accessibility principles and guidance for touch targets](https://developer.android.com/training/wearables/accessibility#set-minimum).
     */
    public data class Custom(val customIconSize: Dp, val customTapTargetSize: Dp) :
        ButtonSize(iconSize = customIconSize, tapTargetSize = customTapTargetSize)
}

