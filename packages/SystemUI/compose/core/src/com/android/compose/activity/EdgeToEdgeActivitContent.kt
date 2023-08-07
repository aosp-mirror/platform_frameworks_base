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

package com.android.compose.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.compose.rememberSystemUiController
import com.android.compose.theme.PlatformTheme

/** Scaffolding for an edge-to-edge activity content. */
@Composable
fun EdgeToEdgeActivityContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Make the status and navigation bars transparent, ensuring that the status bar icons are dark
    // when the theme is light and vice-versa.
    val systemUiController = rememberSystemUiController()
    val isDarkTheme = isSystemInDarkTheme()
    val useDarkIcons = !isDarkTheme
    DisposableEffect(systemUiController, useDarkIcons) {
        systemUiController.setSystemBarsColor(
            color = Color.Transparent,
            darkIcons = useDarkIcons,
        )
        onDispose {}
    }

    PlatformTheme(isDarkTheme) {
        val backgroundColor = MaterialTheme.colorScheme.background
        Box(modifier.fillMaxSize().background(backgroundColor)) {
            CompositionLocalProvider(LocalContentColor provides contentColorFor(backgroundColor)) {
                content()
            }
        }
    }
}
