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

package com.android.systemui.qs.tiles.impl.saver.domain

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.saver.domain.model.DataSaverTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [DataSaverTileModel] to [QSTileState]. */
class DataSaverTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<DataSaverTileModel> {
    override fun map(config: QSTileConfig, data: DataSaverTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            with(data) {
                if (isEnabled) {
                    activationState = QSTileState.ActivationState.ACTIVE
                    iconRes = R.drawable.qs_data_saver_icon_on
                    secondaryLabel = resources.getStringArray(R.array.tile_states_saver)[2]
                } else {
                    activationState = QSTileState.ActivationState.INACTIVE
                    iconRes = R.drawable.qs_data_saver_icon_off
                    secondaryLabel = resources.getStringArray(R.array.tile_states_saver)[1]
                }
                val loadedIcon =
                    Icon.Loaded(resources.getDrawable(iconRes!!, theme), contentDescription = null)
                icon = { loadedIcon }
                contentDescription = label
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            }
        }
}
