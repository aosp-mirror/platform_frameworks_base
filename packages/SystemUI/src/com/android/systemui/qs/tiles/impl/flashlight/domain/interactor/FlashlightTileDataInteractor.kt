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

package com.android.systemui.qs.tiles.impl.flashlight.domain.interactor

import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.flashlight.domain.model.FlashlightTileModel
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Observes flashlight state changes providing the [FlashlightTileModel]. */
class FlashlightTileDataInteractor
@Inject
constructor(
    private val flashlightController: FlashlightController,
) : QSTileDataInteractor<FlashlightTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<FlashlightTileModel> = conflatedCallbackFlow {
        val initialValue = flashlightController.isEnabled
        trySend(FlashlightTileModel(initialValue))

        val callback =
            object : FlashlightController.FlashlightListener {
                override fun onFlashlightChanged(enabled: Boolean) {
                    trySend(FlashlightTileModel(enabled))
                }
                override fun onFlashlightError() {
                    trySend(FlashlightTileModel(false))
                }
                override fun onFlashlightAvailabilityChanged(available: Boolean) {
                    trySend(FlashlightTileModel(flashlightController.isEnabled))
                }
            }
        flashlightController.addCallback(callback)
        awaitClose { flashlightController.removeCallback(callback) }
    }

    override fun availability(user: UserHandle): Flow<Boolean> =
        flowOf(flashlightController.hasFlashlight())
}
