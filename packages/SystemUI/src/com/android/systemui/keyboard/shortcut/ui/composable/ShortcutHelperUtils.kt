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

package com.android.systemui.keyboard.shortcut.ui.composable

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import com.android.compose.windowsizeclass.LocalWindowSizeClass

/**
 * returns true if either size of the window is compact. This represents majority of phone windows
 * portrait
 */
@Composable
fun hasCompactWindowSize() =
    LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact ||
        LocalWindowSizeClass.current.heightSizeClass == WindowHeightSizeClass.Compact
