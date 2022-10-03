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

package com.android.systemui.user.shared.model

import android.graphics.drawable.Drawable
import com.android.systemui.common.shared.model.Text

/** Represents a single user on the device. */
data class UserModel(
    /** ID of the user, unique across all users on this device. */
    val id: Int,
    /** Human-facing name for this user. */
    val name: Text,
    /** Human-facing image for this user. */
    val image: Drawable,
    /** Whether this user is the currently-selected user. */
    val isSelected: Boolean,
    /** Whether this use is selectable. A non-selectable user cannot be switched to. */
    val isSelectable: Boolean,
)
