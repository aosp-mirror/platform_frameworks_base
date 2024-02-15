/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.DeviceControlsTile
import com.android.systemui.statusbar.policy.DeviceControlsController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [AutoAddable] for [DeviceControlsTile.TILE_SPEC].
 *
 * It will send a signal to add the tile when updating to a device that supports device controls. It
 * will send a signal to remove the tile when the device does not support controls.
 */
@SysUISingleton
class DeviceControlsAutoAddable
@Inject
constructor(
    private val deviceControlsController: DeviceControlsController,
) : AutoAddable {

    private val spec = TileSpec.create(DeviceControlsTile.TILE_SPEC)

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return conflatedCallbackFlow {
            val callback =
                object : DeviceControlsController.Callback {
                    override fun onControlsUpdate(position: Int?) {
                        position?.let { trySend(AutoAddSignal.Add(spec, position)) }
                        deviceControlsController.removeCallback()
                    }

                    override fun removeControlsAutoTracker() {
                        trySend(AutoAddSignal.Remove(spec))
                    }
                }

            deviceControlsController.setCallback(callback)

            awaitClose { deviceControlsController.removeCallback() }
        }
    }

    override val autoAddTracking: AutoAddTracking
        get() = AutoAddTracking.Always

    override val description = "DeviceControlsAutoAddable ($autoAddTracking)"
}
