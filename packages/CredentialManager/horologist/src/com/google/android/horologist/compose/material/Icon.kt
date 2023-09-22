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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.LocalContentAlpha
import androidx.wear.compose.material.LocalContentColor
import com.google.android.horologist.annotations.ExperimentalHorologistApi

/**
 * This component is an alternative to [Icon], providing the following:
 * - a convenient way of setting the icon to be mirrored in RTL mode;
 */
@ExperimentalHorologistApi
@Composable
public fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current.copy(alpha = LocalContentAlpha.current),
    rtlMode: IconRtlMode = IconRtlMode.Default,
) {
    val shouldMirror =
        rtlMode == IconRtlMode.Mirrored && LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        modifier = modifier.scale(
            scaleX = if (shouldMirror) -1f else 1f,
            scaleY = 1f,
        ),
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = tint,
    )
}

/**
 * This component is an alternative to [Icon], providing the following:
 * - a convenient way of setting the icon to be mirrored in RTL mode;
 */
@ExperimentalHorologistApi
@Composable
public fun Icon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current.copy(alpha = LocalContentAlpha.current),
    rtlMode: IconRtlMode = IconRtlMode.Default,
) {
    val shouldMirror =
        rtlMode == IconRtlMode.Mirrored && LocalLayoutDirection.current == LayoutDirection.Rtl

    Icon(
        painter = painterResource(id = id),
        contentDescription = contentDescription,
        modifier = modifier.scale(
            scaleX = if (shouldMirror) -1f else 1f,
            scaleY = 1f,
        ),
        tint = tint,
    )
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
internal fun Icon(
    icon: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current.copy(alpha = LocalContentAlpha.current),
    rtlMode: IconRtlMode = IconRtlMode.Default,
) {
    val shouldMirror =
        rtlMode == IconRtlMode.Mirrored && LocalLayoutDirection.current == LayoutDirection.Rtl

    val iconModifier = modifier.scale(
        scaleX = if (shouldMirror) -1f else 1f,
        scaleY = 1f,
    )
    when (icon) {
        is ImageVector -> {
            Icon(
                imageVector = icon,
                modifier = iconModifier,
                contentDescription = contentDescription,
                tint = tint,
            )
        }

        is Int -> {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = contentDescription,
                modifier = iconModifier,
                tint = tint,
            )
        }

        else -> throw IllegalArgumentException("Type not supported.")
    }
}

@ExperimentalHorologistApi
public enum class IconRtlMode {
    Default,
    Mirrored,
}
