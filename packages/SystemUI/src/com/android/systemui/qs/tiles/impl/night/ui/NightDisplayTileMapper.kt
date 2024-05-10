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

package com.android.systemui.qs.tiles.impl.night.ui

import android.content.res.Resources
import android.service.quicksettings.Tile
import android.text.TextUtils
import androidx.annotation.StringRes
import com.android.systemui.accessibility.qs.QSAccessibilityModule
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.impl.night.domain.model.NightDisplayTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import java.time.DateTimeException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Maps [NightDisplayTileModel] to [QSTileState]. */
class NightDisplayTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
    private val logger: QSTileLogger,
) : QSTileDataToStateMapper<NightDisplayTileModel> {
    override fun map(config: QSTileConfig, data: NightDisplayTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.quick_settings_night_display_label)
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            sideViewIcon = QSTileState.SideViewIcon.None

            if (data.isActivated) {
                activationState = QSTileState.ActivationState.ACTIVE
                val loadedIcon =
                    Icon.Loaded(
                        resources.getDrawable(R.drawable.qs_nightlight_icon_on, theme),
                        contentDescription = null
                    )
                icon = { loadedIcon }
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                val loadedIcon =
                    Icon.Loaded(
                        resources.getDrawable(R.drawable.qs_nightlight_icon_off, theme),
                        contentDescription = null
                    )
                icon = { loadedIcon }
            }

            secondaryLabel = getSecondaryLabel(data, resources)

            contentDescription =
                if (TextUtils.isEmpty(secondaryLabel)) label
                else TextUtils.concat(label, ", ", secondaryLabel)
        }

    private fun getSecondaryLabel(
        data: NightDisplayTileModel,
        resources: Resources
    ): CharSequence? {
        when (data) {
            is NightDisplayTileModel.AutoModeTwilight -> {
                if (!data.isLocationEnabled) {
                    return null
                } else {
                    return resources.getString(
                        if (data.isActivated)
                            R.string.quick_settings_night_secondary_label_until_sunrise
                        else R.string.quick_settings_night_secondary_label_on_at_sunset
                    )
                }
            }
            is NightDisplayTileModel.AutoModeOff -> {
                val subtitleArray = resources.getStringArray(R.array.tile_states_night)
                return subtitleArray[
                    if (data.isActivated) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE]
            }
            is NightDisplayTileModel.AutoModeCustom -> {
                // User-specified time, approximated to the nearest hour.
                @StringRes val toggleTimeStringRes: Int
                val toggleTime: LocalTime
                if (data.isActivated) {
                    toggleTime = data.endTime ?: return null
                    toggleTimeStringRes = R.string.quick_settings_secondary_label_until
                } else {
                    toggleTime = data.startTime ?: return null
                    toggleTimeStringRes = R.string.quick_settings_night_secondary_label_on_at
                }

                try {
                    val formatter = if (data.is24HourFormat) formatter24Hour else formatter12Hour
                    val formatArg = formatter.format(toggleTime)
                    return resources.getString(toggleTimeStringRes, formatArg)
                } catch (exception: DateTimeException) {
                    logger.logWarning(spec, exception.message.toString())
                    return null
                }
            }
        }
    }

    private companion object {
        val formatter12Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a")
        val formatter24Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val spec = TileSpec.create(QSAccessibilityModule.NIGHT_DISPLAY_TILE_SPEC)
    }
}
