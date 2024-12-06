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

package com.android.systemui.qs.tiles.impl.hearingdevices.domain

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.hearingdevices.domain.model.HearingDevicesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/** Maps [HearingDevicesTileModel] to [QSTileState]. */
class HearingDevicesTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<HearingDevicesTileModel> {

    override fun map(config: QSTileConfig, data: HearingDevicesTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.quick_settings_hearing_devices_label)
            iconRes = R.drawable.qs_hearing_devices_icon
            icon = Icon.Loaded(resources.getDrawable(iconRes!!, theme), null)
            sideViewIcon = QSTileState.SideViewIcon.Chevron
            contentDescription = label
            if (data.isAnyActiveHearingDevice) {
                activationState = QSTileState.ActivationState.ACTIVE
                secondaryLabel =
                    resources.getString(R.string.quick_settings_hearing_devices_connected)
            } else if (data.isAnyPairedHearingDevice) {
                activationState = QSTileState.ActivationState.INACTIVE
                secondaryLabel =
                    resources.getString(R.string.quick_settings_hearing_devices_disconnected)
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                secondaryLabel = ""
            }
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
        }
}
