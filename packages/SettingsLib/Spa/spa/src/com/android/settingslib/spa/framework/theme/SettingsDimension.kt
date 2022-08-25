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

package com.android.settingslib.spa.framework.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

object SettingsDimension {
    val itemIconSize = 24.dp
    val itemIconContainerSize = 72.dp
    val itemPaddingStart = 24.dp
    val itemPaddingEnd = 16.dp
    val itemPaddingVertical = 16.dp
    val itemPadding = PaddingValues(
        start = itemPaddingStart,
        top = itemPaddingVertical,
        end = itemPaddingEnd,
        bottom = itemPaddingVertical,
    )
    val itemPaddingAround = 8.dp

    /** The size when app icon is displayed in list. */
    val appIconItemSize = 32.dp

    /** The size when app icon is displayed in App info page. */
    val appIconInfoSize = 48.dp
}
