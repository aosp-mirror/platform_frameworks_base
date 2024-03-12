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

package com.android.systemui.qs.tiles.impl.battery.domain.interactor

import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.battery.domain.model.BatterySaverTileModel
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.kotlin.combine
import com.android.systemui.util.kotlin.getBatteryLevel
import com.android.systemui.util.kotlin.isBatteryPowerSaveEnabled
import com.android.systemui.util.kotlin.isDevicePluggedIn
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/** Observes BatterySaver mode state changes providing the [BatterySaverTileModel.Standard]. */
open class BatterySaverTileDataInteractor
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    private val batteryController: BatteryController,
) : QSTileDataInteractor<BatterySaverTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<BatterySaverTileModel> =
        combine(
            batteryController.isDevicePluggedIn().distinctUntilChanged().flowOn(bgCoroutineContext),
            batteryController
                .isBatteryPowerSaveEnabled()
                .distinctUntilChanged()
                .flowOn(bgCoroutineContext),
            batteryController.getBatteryLevel().distinctUntilChanged().flowOn(bgCoroutineContext),
        ) {
            isPluggedIn: Boolean,
            isPowerSaverEnabled: Boolean,
            _, // we are only interested in battery level change, not the actual level
            ->
            BatterySaverTileModel.Standard(isPluggedIn, isPowerSaverEnabled)
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}
