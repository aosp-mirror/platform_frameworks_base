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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/** The bounds and [CutoutLocation] of the current display. */
val LocalDisplayCutout = staticCompositionLocalOf { DisplayCutout() }

/** The corner radius in px of the current display. */
val LocalScreenCornerRadius = staticCompositionLocalOf { 0.dp }

@Composable
fun ScreenDecorProvider(
    displayCutout: StateFlow<DisplayCutout>,
    screenCornerRadius: Float,
    content: @Composable () -> Unit,
) {
    val cutout by displayCutout.collectAsState()
    val screenCornerRadiusDp = with(LocalDensity.current) { screenCornerRadius.toDp() }
    CompositionLocalProvider(
        LocalScreenCornerRadius provides screenCornerRadiusDp,
        LocalDisplayCutout provides cutout
    ) {
        content()
    }
}
