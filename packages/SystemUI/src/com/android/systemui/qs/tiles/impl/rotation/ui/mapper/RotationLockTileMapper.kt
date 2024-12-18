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

package com.android.systemui.qs.tiles.impl.rotation.ui.mapper

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.rotation.domain.model.RotationLockTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DevicePostureController
import javax.inject.Inject

/** Maps [RotationLockTileModel] to [QSTileState]. */
class RotationLockTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
    private val devicePostureController: DevicePostureController
) : QSTileDataToStateMapper<RotationLockTileModel> {
    override fun map(config: QSTileConfig, data: RotationLockTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            this.label = resources.getString(R.string.quick_settings_rotation_unlocked_label)
            this.contentDescription =
                resources.getString(R.string.accessibility_quick_settings_rotation)

            if (data.isRotationLocked) {
                activationState = QSTileState.ActivationState.INACTIVE
                this.secondaryLabel = EMPTY_SECONDARY_STRING
                iconRes = R.drawable.qs_auto_rotate_icon_off
            } else {
                activationState = QSTileState.ActivationState.ACTIVE
                this.secondaryLabel =
                    if (data.isCameraRotationEnabled) {
                        resources.getString(R.string.rotation_lock_camera_rotation_on)
                    } else {
                        EMPTY_SECONDARY_STRING
                    }
                this.iconRes = R.drawable.qs_auto_rotate_icon_on
            }
            this.icon = {
                Icon.Loaded(resources.getDrawable(iconRes!!, theme), contentDescription = null)
            }
            if (isDeviceFoldable()) {
                this.secondaryLabel = getSecondaryLabelWithPosture(this.activationState)
            }
            this.stateDescription = this.secondaryLabel
            this.sideViewIcon = QSTileState.SideViewIcon.None
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
        }

    private fun isDeviceFoldable(): Boolean {
        val intArray = resources.getIntArray(com.android.internal.R.array.config_foldedDeviceStates)
        return intArray.isNotEmpty()
    }

    private fun getSecondaryLabelWithPosture(activationState: QSTileState.ActivationState): String {
        val stateNames = resources.getStringArray(R.array.tile_states_rotation)
        val stateName =
            stateNames[
                if (activationState == QSTileState.ActivationState.ACTIVE) ON_INDEX else OFF_INDEX]
        val posture =
            if (
                devicePostureController.devicePosture ==
                    DevicePostureController.DEVICE_POSTURE_CLOSED
            )
                resources.getString(R.string.quick_settings_rotation_posture_folded)
            else resources.getString(R.string.quick_settings_rotation_posture_unfolded)

        return resources.getString(
            R.string.rotation_tile_with_posture_secondary_label_template,
            stateName,
            posture
        )
    }

    private companion object {
        const val EMPTY_SECONDARY_STRING = ""
        const val OFF_INDEX = 1
        const val ON_INDEX = 2
    }
}
