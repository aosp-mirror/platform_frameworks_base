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

package com.android.systemui.people.ui.viewmodel

import android.graphics.Bitmap
import com.android.systemui.people.widget.PeopleTileKey

/** Models UI state for a single tile/conversation. */
data class PeopleTileViewModel(
    val key: PeopleTileKey,
    val icon: Bitmap,
    val username: String?,
)
