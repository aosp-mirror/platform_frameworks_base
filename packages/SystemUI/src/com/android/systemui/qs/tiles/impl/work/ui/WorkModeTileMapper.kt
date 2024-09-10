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

package com.android.systemui.qs.tiles.impl.work.ui

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_WORK_PROFILE_LABEL
import android.content.res.Resources
import android.service.quicksettings.Tile
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.work.domain.model.WorkModeTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [WorkModeTileModel] to [QSTileState]. */
class WorkModeTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
    private val devicePolicyManager: DevicePolicyManager,
) : QSTileDataToStateMapper<WorkModeTileModel> {
    override fun map(config: QSTileConfig, data: WorkModeTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = getTileLabel()!!
            contentDescription = label
            iconRes = com.android.internal.R.drawable.stat_sys_managed_profile_status
            icon = {
                Icon.Loaded(resources.getDrawable(iconRes!!, theme), contentDescription = null)
            }

            when (data) {
                is WorkModeTileModel.HasActiveProfile -> {
                    if (data.isEnabled) {
                        activationState = QSTileState.ActivationState.ACTIVE
                        secondaryLabel = ""
                    } else {
                        activationState = QSTileState.ActivationState.INACTIVE
                        secondaryLabel =
                            resources.getString(R.string.quick_settings_work_mode_paused_state)
                    }
                    supportedActions =
                        setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
                }
                is WorkModeTileModel.NoActiveProfile -> {
                    activationState = QSTileState.ActivationState.UNAVAILABLE
                    secondaryLabel =
                        resources.getStringArray(R.array.tile_states_work)[Tile.STATE_UNAVAILABLE]
                    supportedActions = setOf()
                }
            }

            sideViewIcon = QSTileState.SideViewIcon.None
        }

    private fun getTileLabel(): CharSequence? {
        return devicePolicyManager.resources.getString(QS_WORK_PROFILE_LABEL) {
            resources.getString(R.string.quick_settings_work_mode_label)
        }
    }
}
