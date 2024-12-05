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
import android.hardware.devicestate.DeviceState as PlatformDeviceState
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN
import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.feature.flags.Flags as DeviceStateManagerFlags
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
        /** Device state that corresponds to the device being folded */
        FOLDED,
        /** Device state that corresponds to the device being half-folded */
        HALF_FOLDED,
        /** Device state in that corresponds to the device being unfolded */
        UNFOLDED,
        /** Device state that corresponds to the device being in rear display mode */
        REAR_DISPLAY,
        /**
         * Device state that corresponds to the device being in rear display mode with the inner
         * display showing a system-provided affordance to cancel the mode.
         *
         * TODO(b/371095273): This state will be removed after the RDM_V2 flag lifecycle is complete
         *   at which point the REAR_DISPLAY state will be the will be the new and only rear display
         *   mode.
         */
        REAR_DISPLAY_OUTER_DEFAULT,
        /** Device state in that corresponds to the device being in concurrent display mode */
        CONCURRENT_DISPLAY,
        /** Device state in none of the other arrays. */
        UNKNOWN,
    }
}

class DeviceStateRepositoryImpl
@Inject
constructor(
    val context: Context,
    val deviceStateManager: DeviceStateManager,
    @Background bgScope: CoroutineScope,
    @Background executor: Executor,
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
        return if (DeviceStateManagerFlags.deviceStatePropertyMigration()) {
            deviceStateManager.supportedDeviceStates
                .firstOrNull { it.identifier == deviceStateId }
                ?.toDeviceStateEnum() ?: DeviceState.UNKNOWN
        } else {
            deviceStateMap.firstOrNull { (ids, _) -> deviceStateId in ids }?.deviceState
                ?: DeviceState.UNKNOWN
        }
    }

    private val deviceStateMap: List<IdsPerDeviceState> =
        listOf(
                R.array.config_foldedDeviceStates to DeviceState.FOLDED,
                R.array.config_halfFoldedDeviceStates to DeviceState.HALF_FOLDED,
                R.array.config_openDeviceStates to DeviceState.UNFOLDED,
                R.array.config_rearDisplayDeviceStates to DeviceState.REAR_DISPLAY,
                R.array.config_concurrentDisplayDeviceStates to DeviceState.CONCURRENT_DISPLAY,
            )
            .map { IdsPerDeviceState(context.resources.getIntArray(it.first).toSet(), it.second) }

    private data class IdsPerDeviceState(val ids: Set<Int>, val deviceState: DeviceState)

    /**
     * Maps a [PlatformDeviceState] to the corresponding [DeviceState] value based on the properties
     * of the state.
     */
    private fun PlatformDeviceState.toDeviceStateEnum(): DeviceState {
        return when {
            hasProperties(
                PROPERTY_FEATURE_REAR_DISPLAY,
                PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT,
            ) -> {
                DeviceState.REAR_DISPLAY_OUTER_DEFAULT
            }
            hasProperty(PROPERTY_FEATURE_REAR_DISPLAY) -> DeviceState.REAR_DISPLAY
            hasProperty(PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT) -> {
                DeviceState.CONCURRENT_DISPLAY
            }
            hasProperty(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY) -> DeviceState.FOLDED
            hasProperties(
                PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN,
            ) -> DeviceState.HALF_FOLDED
            hasProperty(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY) -> {
                DeviceState.UNFOLDED
            }
            else -> DeviceState.UNKNOWN
        }
    }
}
