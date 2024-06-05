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

package com.android.systemui.common.ui.compose.windowinsets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** The bounds and [CutoutLocation] of the current display. */
val LocalDisplayCutout = staticCompositionLocalOf { DisplayCutout() }

/** The corner radius in px of the current display. */
val LocalScreenCornerRadius = staticCompositionLocalOf { 0.dp }

/** The screen height in px without accounting for any screen insets (cutouts, status/nav bars) */
val LocalRawScreenHeight = staticCompositionLocalOf { 0f }

@Composable
fun ScreenDecorProvider(
    displayCutout: StateFlow<DisplayCutout>,
    screenCornerRadius: Float,
    content: @Composable () -> Unit,
) {
    val cutout by displayCutout.collectAsStateWithLifecycle()
    val screenCornerRadiusDp = with(LocalDensity.current) { screenCornerRadius.toDp() }

    val density = LocalDensity.current
    val navBarHeight =
        with(density) { WindowInsets.systemBars.asPaddingValues().calculateBottomPadding().toPx() }
    val statusBarHeight = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val displayCutoutHeight = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val screenHeight =
        with(density) {
            (LocalConfiguration.current.screenHeightDp.dp +
                    maxOf(statusBarHeight, displayCutoutHeight))
                .toPx()
        } + navBarHeight

    CompositionLocalProvider(
        LocalScreenCornerRadius provides screenCornerRadiusDp,
        LocalDisplayCutout provides cutout,
        LocalRawScreenHeight provides screenHeight,
    ) {
        content()
    }
}
