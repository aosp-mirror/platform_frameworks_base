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

package com.android.systemui.media.controls.ui.viewmodel

import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import com.android.systemui.animation.Expandable

/** Models UI state for media recommendation item */
data class MediaRecViewModel(
    val contentDescription: CharSequence,
    val title: CharSequence = "",
    val subtitle: CharSequence = "",
    /** track progress [0 - 100] for the recommendation album. */
    val progress: Int = 0,
    val albumIcon: Icon? = null,
    val appIcon: Drawable,
    val onClicked: ((Expandable, Int) -> Unit),
)
