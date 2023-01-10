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
 *
 */

package com.android.systemui.user.ui.viewmodel

import android.graphics.drawable.Drawable
import com.android.systemui.common.shared.model.Text

/** Models UI state for representing a single user. */
data class UserViewModel(
    /**
     * Key to use with the view or compose system to keep track of the view/composable across
     * changes to the collection of [UserViewModel] instances.
     */
    val viewKey: Int,
    val name: Text,
    val image: Drawable,
    /** Whether a marker should be shown to highlight that this user is the selected one. */
    val isSelectionMarkerVisible: Boolean,
    val alpha: Float,
    val onClicked: (() -> Unit)?,
)
