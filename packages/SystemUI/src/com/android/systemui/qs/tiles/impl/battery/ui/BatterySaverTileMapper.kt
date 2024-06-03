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

package com.android.systemui.qs.tiles.impl.battery.ui

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.battery.domain.model.BatterySaverTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [BatterySaverTileModel] to [QSTileState]. */
open class BatterySaverTileMapper
@Inject
constructor(
    @Main protected val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<BatterySaverTileModel> {

    override fun map(config: QSTileConfig, data: BatterySaverTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.battery_detail_switch_title)
            contentDescription = label
            iconRes =
                if (data.isPowerSaving) R.drawable.qs_battery_saver_icon_on
                else R.drawable.qs_battery_saver_icon_off
            icon = { Icon.Loaded(resources.getDrawable(iconRes!!, theme), null) }

            sideViewIcon = QSTileState.SideViewIcon.None

            if (data.isPluggedIn) {
                activationState = QSTileState.ActivationState.UNAVAILABLE
                supportedActions = setOf(QSTileState.UserAction.LONG_CLICK)
                secondaryLabel = ""
            } else if (data.isPowerSaving) {
                activationState = QSTileState.ActivationState.ACTIVE
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)

                if (data is BatterySaverTileModel.Extreme) {
                    secondaryLabel =
                        resources.getString(
                            if (data.isExtremeSaving) R.string.extreme_battery_saver_text
                            else R.string.standard_battery_saver_text
                        )
                    stateDescription = secondaryLabel
                } else {
                    secondaryLabel = ""
                }
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
                secondaryLabel = ""
            }
        }
}
