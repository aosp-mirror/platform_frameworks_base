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

package com.android.systemui.qs.tiles.impl.sensorprivacy.ui

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.model.SensorPrivacyToggleTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Maps [SensorPrivacyToggleTileModel] to [QSTileState]. */
class SensorPrivacyToggleTileMapper
@AssistedInject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
    @Assisted private val sensorPrivacyTileResources: SensorPrivacyTileResources,
) : QSTileDataToStateMapper<SensorPrivacyToggleTileModel> {

    @AssistedFactory
    interface Factory {
        fun create(
            sensorPrivacyTileResources: SensorPrivacyTileResources
        ): SensorPrivacyToggleTileMapper
    }

    override fun map(config: QSTileConfig, data: SensorPrivacyToggleTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(sensorPrivacyTileResources.getTileLabelRes())
            contentDescription = label
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            iconRes = sensorPrivacyTileResources.getIconRes(data.isBlocked)
            icon = { Icon.Loaded(resources.getDrawable(iconRes!!, theme), null) }

            sideViewIcon = QSTileState.SideViewIcon.None

            if (data.isBlocked) {
                activationState = QSTileState.ActivationState.INACTIVE
                secondaryLabel = resources.getString(R.string.quick_settings_camera_mic_blocked)
            } else {
                activationState = QSTileState.ActivationState.ACTIVE
                secondaryLabel = resources.getString(R.string.quick_settings_camera_mic_available)
            }
        }
}
