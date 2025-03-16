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

package com.android.systemui.volume.dialog.ringer.ui.viewmodel

import android.annotation.DrawableRes
import android.annotation.StringRes
import com.android.settingslib.volume.shared.model.RingerMode

/** Models ringer button that corresponds to each ringer mode. */
data class RingerButtonViewModel(
    /** Image resource id for the image button. */
    @DrawableRes val imageResId: Int,
    /** Content description for a11y. */
    @StringRes val contentDescriptionResId: Int,
    /** Hint label for accessibility use. */
    @StringRes val hintLabelResId: Int,
    /** Used to notify view model when button is clicked. */
    val ringerMode: RingerMode,
)
