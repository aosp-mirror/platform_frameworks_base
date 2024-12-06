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

package com.android.systemui.qs.tiles.impl.reducebrightness.ui

import android.content.res.Resources
import android.service.quicksettings.Tile
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.reducebrightness.domain.model.ReduceBrightColorsTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/** Maps [ReduceBrightColorsTileModel] to [QSTileState]. */
class ReduceBrightColorsTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<ReduceBrightColorsTileModel> {

    override fun map(config: QSTileConfig, data: ReduceBrightColorsTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            if (data.isEnabled) {
                activationState = QSTileState.ActivationState.ACTIVE
                iconRes = R.drawable.qs_extra_dim_icon_on
                secondaryLabel =
                    resources
                        .getStringArray(R.array.tile_states_reduce_brightness)[Tile.STATE_ACTIVE]
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                iconRes = R.drawable.qs_extra_dim_icon_off
                secondaryLabel =
                    resources
                        .getStringArray(R.array.tile_states_reduce_brightness)[Tile.STATE_INACTIVE]
            }
            icon = Icon.Loaded(resources.getDrawable(iconRes!!, theme), null)
            label =
                resources.getString(com.android.internal.R.string.reduce_bright_colors_feature_name)
            contentDescription = label
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
        }
}
