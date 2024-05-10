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

package com.android.systemui.qs.tiles.impl.airplane.domain

import android.content.res.Resources
import android.content.res.Resources.Theme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.airplane.domain.model.AirplaneModeTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [AirplaneModeTileModel] to [QSTileState]. */
class AirplaneModeMapper
@Inject
constructor(
    @Main private val resources: Resources,
    val theme: Theme,
) : QSTileDataToStateMapper<AirplaneModeTileModel> {

    override fun map(config: QSTileConfig, data: AirplaneModeTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            val icon =
                Icon.Loaded(
                    resources.getDrawable(
                        if (data.isEnabled) {
                            R.drawable.qs_airplane_icon_on
                        } else {
                            R.drawable.qs_airplane_icon_off
                        },
                        theme,
                    ),
                    contentDescription = null
                )
            this.icon = { icon }
            if (data.isEnabled) {
                activationState = QSTileState.ActivationState.ACTIVE
                secondaryLabel = resources.getStringArray(R.array.tile_states_airplane)[2]
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                secondaryLabel = resources.getStringArray(R.array.tile_states_airplane)[1]
            }
            contentDescription = label
            supportedActions =
                setOf(
                    QSTileState.UserAction.CLICK,
                    QSTileState.UserAction.LONG_CLICK,
                )
        }
}
