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

package com.android.systemui.qs.tiles.impl.fontscaling.domain

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.fontscaling.domain.model.FontScalingTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [FontScalingTileModel] to [QSTileState]. */
class FontScalingTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<FontScalingTileModel> {

    override fun map(config: QSTileConfig, data: FontScalingTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            iconRes = R.drawable.ic_qs_font_scaling
            val icon =
                Icon.Loaded(
                    resources.getDrawable(
                        iconRes!!,
                        theme,
                    ),
                    contentDescription = null
                )
            this.icon = { icon }
            contentDescription = label
            activationState = QSTileState.ActivationState.ACTIVE
            sideViewIcon = QSTileState.SideViewIcon.Chevron
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
        }
}
