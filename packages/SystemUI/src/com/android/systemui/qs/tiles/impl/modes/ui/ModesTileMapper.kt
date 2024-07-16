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

package com.android.systemui.qs.tiles.impl.modes.ui

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

class ModesTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    val theme: Resources.Theme,
) : QSTileDataToStateMapper<ModesTileModel> {
    override fun map(config: QSTileConfig, data: ModesTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            iconRes =
                if (data.isActivated) {
                    R.drawable.qs_dnd_icon_on
                } else {
                    R.drawable.qs_dnd_icon_off
                }
            val icon =
                Icon.Loaded(
                    resources.getDrawable(iconRes!!, theme),
                    contentDescription = null,
                )
            this.icon = { icon }
            if (data.isActivated) {
                activationState = QSTileState.ActivationState.ACTIVE
                secondaryLabel = "Some modes enabled idk" // TODO(b/346519570)
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                secondaryLabel = "Off" // TODO(b/346519570)
            }
            contentDescription = label
            supportedActions =
                setOf(
                    QSTileState.UserAction.CLICK,
                    QSTileState.UserAction.LONG_CLICK,
                )
        }
}
