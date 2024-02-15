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

package com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.uimodenight.domain.model.UiModeNightTileModel
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.util.time.DateFormatUtil
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Observes ui mode night state changes providing the [UiModeNightTileModel]. */
class UiModeNightTileDataInteractor
@Inject
constructor(
    @Application private val context: Context,
    private val configurationController: ConfigurationController,
    private val uiModeManager: UiModeManager,
    private val batteryController: BatteryController,
    private val locationController: LocationController,
    private val dateFormatUtil: DateFormatUtil,
) : QSTileDataInteractor<UiModeNightTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<UiModeNightTileModel> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
            // send initial state
            trySend(createModel())

            val configurationCallback =
                object : ConfigurationController.ConfigurationListener {
                    override fun onUiModeChanged() {
                        trySend(createModel())
                    }
                }
            configurationController.addCallback(configurationCallback)

            val batteryCallback =
                object : BatteryController.BatteryStateChangeCallback {
                    override fun onPowerSaveChanged(isPowerSave: Boolean) {
                        trySend(createModel())
                    }
                }
            batteryController.addCallback(batteryCallback)

            val locationCallback =
                object : LocationController.LocationChangeCallback {
                    override fun onLocationSettingsChanged(locationEnabled: Boolean) {
                        trySend(createModel())
                    }
                }
            locationController.addCallback(locationCallback)

            awaitClose {
                configurationController.removeCallback(configurationCallback)
                batteryController.removeCallback(batteryCallback)
                locationController.removeCallback(locationCallback)
            }
        }

    private fun createModel(): UiModeNightTileModel {
        val uiMode = uiModeManager.nightMode
        val nightMode =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val powerSave = batteryController.isPowerSave
        val locationEnabled = locationController.isLocationEnabled
        val nightModeCustomType = uiModeManager.nightModeCustomType
        val use24HourFormat = dateFormatUtil.is24HourFormat
        val customNightModeEnd = uiModeManager.customNightModeEnd
        val customNightModeStart = uiModeManager.customNightModeStart

        return UiModeNightTileModel(
            uiMode,
            nightMode,
            powerSave,
            locationEnabled,
            nightModeCustomType,
            use24HourFormat,
            customNightModeEnd,
            customNightModeStart
        )
    }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}
