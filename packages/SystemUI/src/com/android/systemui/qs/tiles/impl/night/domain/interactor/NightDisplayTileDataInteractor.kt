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

package com.android.systemui.qs.tiles.impl.night.domain.interactor

import android.content.Context
import android.hardware.display.ColorDisplayManager
import android.os.UserHandle
import com.android.systemui.accessibility.data.repository.NightDisplayRepository
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.night.domain.model.NightDisplayTileModel
import com.android.systemui.util.time.DateFormatUtil
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Observes screen record state changes providing the [NightDisplayTileModel]. */
class NightDisplayTileDataInteractor
@Inject
constructor(
    @Application private val context: Context,
    private val dateFormatUtil: DateFormatUtil,
    private val nightDisplayRepository: NightDisplayRepository,
) : QSTileDataInteractor<NightDisplayTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<NightDisplayTileModel> =
        nightDisplayRepository.nightDisplayState(user).map {
            generateModel(
                it.autoMode,
                it.isActivated,
                it.startTime,
                it.endTime,
                it.shouldForceAutoMode,
                it.locationEnabled
            )
        }

    /** This checks resources and there fore does not make a binder call. */
    override fun availability(user: UserHandle): Flow<Boolean> =
        flowOf(ColorDisplayManager.isNightDisplayAvailable(context))

    private fun generateModel(
        autoMode: Int,
        isNightDisplayActivated: Boolean,
        customStartTime: LocalTime?,
        customEndTime: LocalTime?,
        shouldForceAutoMode: Boolean,
        locationEnabled: Boolean,
    ): NightDisplayTileModel {
        if (autoMode == ColorDisplayManager.AUTO_MODE_TWILIGHT) {
            return NightDisplayTileModel.AutoModeTwilight(
                isNightDisplayActivated,
                shouldForceAutoMode,
                locationEnabled,
            )
        } else if (autoMode == ColorDisplayManager.AUTO_MODE_CUSTOM_TIME) {
            return NightDisplayTileModel.AutoModeCustom(
                isNightDisplayActivated,
                shouldForceAutoMode,
                customStartTime,
                customEndTime,
                dateFormatUtil.is24HourFormat,
            )
        } else { // auto mode off
            return NightDisplayTileModel.AutoModeOff(isNightDisplayActivated, shouldForceAutoMode)
        }
    }
}
