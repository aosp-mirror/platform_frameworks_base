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

import android.content.Context
import com.android.internal.R as internalR
import com.android.settingslib.Utils
import com.android.systemui.res.R

/** Models the UI state of ringer button */
data class RingerButtonUiModel(
    /** Icon color. */
    val tintColor: Int,
    val backgroundColor: Int,
    val cornerRadius: Int,
) {
    companion object {
        fun getUnselectedButton(context: Context): RingerButtonUiModel {
            return RingerButtonUiModel(
                tintColor =
                    Utils.getColorAttrDefaultColor(context, internalR.attr.materialColorOnSurface),
                backgroundColor =
                    Utils.getColorAttrDefaultColor(
                        context,
                        internalR.attr.materialColorSurfaceContainerHighest,
                    ),
                cornerRadius =
                    context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_background_square_corner_radius
                    ),
            )
        }

        fun getSelectedButton(context: Context): RingerButtonUiModel {
            return RingerButtonUiModel(
                tintColor =
                    Utils.getColorAttrDefaultColor(context, internalR.attr.materialColorOnPrimary),
                backgroundColor =
                    Utils.getColorAttrDefaultColor(context, internalR.attr.materialColorPrimary),
                cornerRadius =
                    context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_ringer_selected_button_background_radius
                    ),
            )
        }
    }
}
