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

package com.android.systemui.volume.dialog.sliders.ui.viewmodel

import android.graphics.drawable.Drawable
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel

data class VolumeDialogSliderStateModel(
    val value: Float,
    val isDisabled: Boolean,
    val valueRange: ClosedFloatingPointRange<Float>,
    val icon: Drawable,
)

fun VolumeDialogStreamModel.toStateModel(
    isDisabled: Boolean,
    icon: Drawable,
): VolumeDialogSliderStateModel {
    return VolumeDialogSliderStateModel(
        value = level.toFloat(),
        isDisabled = isDisabled,
        valueRange = levelMin.toFloat()..levelMax.toFloat(),
        icon = icon,
    )
}
