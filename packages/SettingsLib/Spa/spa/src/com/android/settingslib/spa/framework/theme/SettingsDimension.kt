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

package com.android.settingslib.spa.framework.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

object SettingsDimension {
    val paddingTiny = 2.dp
    val paddingExtraSmall = 4.dp
    val paddingSmall = 4.dp
    val paddingLarge = 16.dp
    val paddingExtraLarge = 24.dp

    val spinnerHorizontalPadding = paddingExtraLarge
    val spinnerVerticalPadding = paddingLarge

    val actionIconWidth = 32.dp
    val actionIconHeight = 40.dp
    val actionIconPadding = 4.dp

    val itemIconSize = 24.dp
    val itemIconContainerSize = 72.dp
    val itemPaddingStart = paddingExtraLarge
    val itemPaddingEnd = paddingLarge
    val itemPaddingVertical = paddingLarge
    val itemPadding = PaddingValues(
        start = itemPaddingStart,
        top = itemPaddingVertical,
        end = itemPaddingEnd,
        bottom = itemPaddingVertical,
    )
    val textFieldPadding = PaddingValues(
        start = itemPaddingStart,
        end = itemPaddingEnd,
    )
    val menuFieldPadding = PaddingValues(
        start = itemPaddingStart,
        end = itemPaddingEnd,
        bottom = itemPaddingVertical,
    )
    val itemPaddingAround = 8.dp
    val itemDividerHeight = 32.dp

    val iconLarge = 48.dp
    val introIconSize = 40.dp

    /** The size when app icon is displayed in list. */
    val appIconItemSize = 32.dp

    /** The size when app icon is displayed in App info page. */
    val appIconInfoSize = iconLarge

    /** The vertical padding for buttons. */
    val buttonPaddingVertical = 12.dp

    /** The [PaddingValues] for buttons. */
    val buttonPadding = PaddingValues(horizontal = itemPaddingEnd, vertical = buttonPaddingVertical)

    /** The horizontal padding for dialog items. */
    val dialogItemPaddingHorizontal = itemPaddingStart

    /** The [PaddingValues] for dialog items. */
    val dialogItemPadding =
        PaddingValues(horizontal = dialogItemPaddingHorizontal, vertical = buttonPaddingVertical)

    /** The sizes info of illustration widget. */
    val illustrationMaxWidth = 412.dp
    val illustrationMaxHeight = 300.dp
    val illustrationPadding = paddingLarge
    val illustrationCornerRadius = 28.dp
}
