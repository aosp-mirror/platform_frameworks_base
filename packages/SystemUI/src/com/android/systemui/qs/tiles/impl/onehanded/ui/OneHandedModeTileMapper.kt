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

package com.android.systemui.qs.tiles.impl.onehanded.ui

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.onehanded.domain.model.OneHandedModeTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [OneHandedModeTileModel] to [QSTileState]. */
class OneHandedModeTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<OneHandedModeTileModel> {

    override fun map(config: QSTileConfig, data: OneHandedModeTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            val subtitleArray = resources.getStringArray(R.array.tile_states_onehanded)
            label = resources.getString(R.string.quick_settings_onehanded_label)
            icon = {
                Icon.Loaded(
                    resources.getDrawable(
                        com.android.internal.R.drawable.ic_qs_one_handed_mode,
                        theme
                    ),
                    null
                )
            }
            if (data.isEnabled) {
                activationState = QSTileState.ActivationState.ACTIVE
                secondaryLabel = subtitleArray[2]
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                secondaryLabel = subtitleArray[1]
            }
            sideViewIcon = QSTileState.SideViewIcon.None
            contentDescription = label
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
        }
}
