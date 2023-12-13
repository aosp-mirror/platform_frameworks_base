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

package com.android.systemui.qs.tiles.impl.uimodenight.domain

import android.app.UiModeManager
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.text.TextUtils
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.uimodenight.domain.model.UiModeNightTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Maps [UiModeNightTileModel] to [QSTileState]. */
class UiModeNightTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Theme,
) : QSTileDataToStateMapper<UiModeNightTileModel> {
    companion object {
        val formatter12Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a")
        val formatter24Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
    override fun map(config: QSTileConfig, data: UiModeNightTileModel): QSTileState =
        with(data) {
            QSTileState.build(resources, theme, config.uiConfig) {
                var shouldSetSecondaryLabel = false

                if (isPowerSave) {
                    secondaryLabel =
                        resources.getString(
                            R.string.quick_settings_dark_mode_secondary_label_battery_saver
                        )
                } else if (uiMode == UiModeManager.MODE_NIGHT_AUTO && isLocationEnabled) {
                    secondaryLabel =
                        resources.getString(
                            if (isNightMode)
                                R.string.quick_settings_dark_mode_secondary_label_until_sunrise
                            else R.string.quick_settings_dark_mode_secondary_label_on_at_sunset
                        )
                } else if (uiMode == UiModeManager.MODE_NIGHT_CUSTOM) {
                    if (nightModeCustomType == UiModeManager.MODE_NIGHT_CUSTOM_TYPE_SCHEDULE) {
                        val time: LocalTime =
                            if (isNightMode) {
                                customNightModeEnd
                            } else {
                                customNightModeStart
                            }

                        val formatter: DateTimeFormatter =
                            if (is24HourFormat) formatter24Hour else formatter12Hour

                        secondaryLabel =
                            resources.getString(
                                if (isNightMode)
                                    R.string.quick_settings_dark_mode_secondary_label_until
                                else R.string.quick_settings_dark_mode_secondary_label_on_at,
                                formatter.format(time)
                            )
                    } else if (
                        nightModeCustomType == UiModeManager.MODE_NIGHT_CUSTOM_TYPE_BEDTIME
                    ) {
                        secondaryLabel =
                            resources.getString(
                                if (isNightMode)
                                    R.string
                                        .quick_settings_dark_mode_secondary_label_until_bedtime_ends
                                else R.string.quick_settings_dark_mode_secondary_label_on_at_bedtime
                            )
                    } else {
                        secondaryLabel = null // undefined type of nightModeCustomType
                        shouldSetSecondaryLabel = true
                    }
                } else {
                    secondaryLabel = null
                    shouldSetSecondaryLabel = true
                }

                contentDescription =
                    if (TextUtils.isEmpty(secondaryLabel)) label
                    else TextUtils.concat(label, ", ", secondaryLabel)
                if (isPowerSave) {
                    activationState = QSTileState.ActivationState.UNAVAILABLE
                    if (shouldSetSecondaryLabel)
                        secondaryLabel = resources.getStringArray(R.array.tile_states_dark)[0]
                } else {
                    activationState =
                        if (isNightMode) QSTileState.ActivationState.ACTIVE
                        else QSTileState.ActivationState.INACTIVE

                    if (shouldSetSecondaryLabel) {
                        secondaryLabel =
                            if (activationState == QSTileState.ActivationState.INACTIVE)
                                resources.getStringArray(R.array.tile_states_dark)[1]
                            else resources.getStringArray(R.array.tile_states_dark)[2]
                    }
                }

                val iconRes =
                    if (activationState == QSTileState.ActivationState.ACTIVE)
                        R.drawable.qs_light_dark_theme_icon_on
                    else R.drawable.qs_light_dark_theme_icon_off
                val loadedIcon =
                    Icon.Loaded(resources.getDrawable(iconRes, theme), contentDescription = null)
                icon = { loadedIcon }

                supportedActions =
                    if (activationState == QSTileState.ActivationState.UNAVAILABLE)
                        setOf(QSTileState.UserAction.LONG_CLICK)
                    else setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            }
        }
}
