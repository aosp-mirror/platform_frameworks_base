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

package com.android.systemui.compose.gallery

import android.app.UiModeManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import com.android.systemui.compose.rememberSystemUiController

class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

        setContent {
            var theme by rememberSaveable { mutableStateOf(Theme.System) }
            val onChangeTheme = {
                // Change to the next theme for a toggle behavior.
                theme =
                    when (theme) {
                        Theme.System -> Theme.Dark
                        Theme.Dark -> Theme.Light
                        Theme.Light -> Theme.System
                    }
            }

            val isSystemInDarkTheme = isSystemInDarkTheme()
            val isDark = theme == Theme.Dark || (theme == Theme.System && isSystemInDarkTheme)
            val useDarkIcons = !isDark
            val systemUiController = rememberSystemUiController()
            SideEffect {
                systemUiController.setSystemBarsColor(
                    color = Color.Transparent,
                    darkIcons = useDarkIcons,
                )

                uiModeManager.setApplicationNightMode(
                    when (theme) {
                        Theme.System -> UiModeManager.MODE_NIGHT_AUTO
                        Theme.Dark -> UiModeManager.MODE_NIGHT_YES
                        Theme.Light -> UiModeManager.MODE_NIGHT_NO
                    }
                )
            }

            GalleryApp(theme, onChangeTheme)
        }
    }
}

enum class Theme {
    System,
    Dark,
    Light,
}
