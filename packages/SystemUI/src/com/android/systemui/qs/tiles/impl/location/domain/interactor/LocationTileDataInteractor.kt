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

package com.android.systemui.qs.tiles.impl.location.domain.interactor

import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.location.domain.model.LocationTileModel
import com.android.systemui.statusbar.policy.LocationController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Observes location state changes providing the [LocationTileModel]. */
class LocationTileDataInteractor
@Inject
constructor(
    private val locationController: LocationController,
) : QSTileDataInteractor<LocationTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<LocationTileModel> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
            val initialValue = locationController.isLocationEnabled
            trySend(LocationTileModel(initialValue))

            val callback =
                object : LocationController.LocationChangeCallback {
                    override fun onLocationSettingsChanged(locationEnabled: Boolean) {
                        trySend(LocationTileModel(locationEnabled))
                    }
                }
            locationController.addCallback(callback)
            awaitClose { locationController.removeCallback(callback) }
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}
