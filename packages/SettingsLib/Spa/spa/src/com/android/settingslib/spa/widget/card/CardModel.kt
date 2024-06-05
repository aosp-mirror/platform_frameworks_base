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

package com.android.settingslib.spa.widget.card

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class CardButton(
    val text: String,
    val contentDescription: String? = null,
    val onClick: () -> Unit,
)

data class CardModel(
    val title: String,
    val text: String,
    val imageVector: ImageVector? = null,
    val isVisible: () -> Boolean = { true },

    /**
     * A dismiss button will be displayed if this is not null.
     *
     * And this callback will be called when user clicks the button.
     */
    val onDismiss: (() -> Unit)? = null,

    val buttons: List<CardButton> = emptyList(),

    /** If specified, this color will be used to tint the icon and the buttons. */
    val tintColor: Color = Color.Unspecified,

    /** If specified, this color will be used to tint the icon and the buttons. */
    val containerColor: Color = Color.Unspecified,

    val onClick: (() -> Unit)? = null,
)
