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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Models UI state for an action that can be performed on a user. */
data class UserActionViewModel(
    /**
     * Key to use with the view or compose system to keep track of the view/composable across
     * changes to the collection of [UserActionViewModel] instances.
     */
    val viewKey: Long,
    @DrawableRes val iconResourceId: Int,
    @StringRes val textResourceId: Int,
    val onClicked: () -> Unit,
)
