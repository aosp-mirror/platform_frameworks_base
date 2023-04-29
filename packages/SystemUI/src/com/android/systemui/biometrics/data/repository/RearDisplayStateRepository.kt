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

package com.android.systemui.biometrics.data.repository

import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import com.android.internal.util.ArrayUtils
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Provide current rear display state. */
interface RearDisplayStateRepository {
    /** Provides the current rear display state. */
    val isInRearDisplayMode: StateFlow<Boolean>
}

@SysUISingleton
class RearDisplayStateRepositoryImpl
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    @Application context: Context,
    deviceStateManager: DeviceStateManager,
    @Main mainExecutor: Executor
) : RearDisplayStateRepository {
    override val isInRearDisplayMode: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val sendRearDisplayStateUpdate = { state: Boolean ->
                    trySendWithFailureLogging(
                        state,
                        TAG,
                        "Error sending rear display state update to $state"
                    )
                }

                val callback =
                    DeviceStateManager.DeviceStateCallback { state ->
                        val isInRearDisplayMode =
                            ArrayUtils.contains(
                                context.resources.getIntArray(
                                    com.android.internal.R.array.config_rearDisplayDeviceStates
                                ),
                                state
                            )
                        sendRearDisplayStateUpdate(isInRearDisplayMode)
                    }

                sendRearDisplayStateUpdate(false)
                deviceStateManager.registerCallback(mainExecutor, callback)
                awaitClose { deviceStateManager.unregisterCallback(callback) }
            }
            .stateIn(
                applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    companion object {
        const val TAG = "RearDisplayStateRepositoryImpl"
    }
}
