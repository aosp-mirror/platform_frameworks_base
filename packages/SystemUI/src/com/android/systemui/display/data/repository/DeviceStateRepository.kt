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

package com.android.systemui.display.data.repository

import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import com.android.internal.R
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DeviceStateRepository.DeviceState
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

interface DeviceStateRepository {
    val state: StateFlow<DeviceState>

    enum class DeviceState {
        /** Device state in [R.array.config_foldedDeviceStates] */
        FOLDED,
        /** Device state in [R.array.config_halfFoldedDeviceStates] */
        HALF_FOLDED,
        /** Device state in [R.array.config_openDeviceStates] */
        UNFOLDED,
        /** Device state in [R.array.config_rearDisplayDeviceStates] */
        REAR_DISPLAY,
        /** Device state in [R.array.config_concurrentDisplayDeviceStates] */
        CONCURRENT_DISPLAY,
        /** Device state in none of the other arrays. */
        UNKNOWN,
    }
}

class DeviceStateRepositoryImpl
@Inject
constructor(
    context: Context,
    deviceStateManager: DeviceStateManager,
    @Background bgScope: CoroutineScope,
    @Background executor: Executor
) : DeviceStateRepository {

    override val state: StateFlow<DeviceState> =
        conflatedCallbackFlow {
                val callback =
                    DeviceStateManager.DeviceStateCallback { state ->
                        trySend(deviceStateToPosture(state.identifier))
                    }
                deviceStateManager.registerCallback(executor, callback)
                awaitClose { deviceStateManager.unregisterCallback(callback) }
            }
            .stateIn(bgScope, started = SharingStarted.WhileSubscribed(), DeviceState.UNKNOWN)

    private fun deviceStateToPosture(deviceStateId: Int): DeviceState {
        return deviceStateMap.firstOrNull { (ids, _) -> deviceStateId in ids }?.deviceState
            ?: DeviceState.UNKNOWN
    }

    private val deviceStateMap =
        listOf(
                R.array.config_foldedDeviceStates to DeviceState.FOLDED,
                R.array.config_halfFoldedDeviceStates to DeviceState.HALF_FOLDED,
                R.array.config_openDeviceStates to DeviceState.UNFOLDED,
                R.array.config_rearDisplayDeviceStates to DeviceState.REAR_DISPLAY,
                R.array.config_concurrentDisplayDeviceStates to DeviceState.CONCURRENT_DISPLAY,
            )
            .map { IdsPerDeviceState(context.resources.getIntArray(it.first).toSet(), it.second) }

    private data class IdsPerDeviceState(val ids: Set<Int>, val deviceState: DeviceState)
}
