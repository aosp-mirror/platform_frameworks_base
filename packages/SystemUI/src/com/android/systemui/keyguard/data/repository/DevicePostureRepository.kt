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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.statusbar.policy.DevicePostureController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

/** Provide current device posture state. */
interface DevicePostureRepository {
    /** Provides the current device posture. */
    val currentDevicePosture: Flow<DevicePosture>
}

@SysUISingleton
class DevicePostureRepositoryImpl
@Inject
constructor(
    private val postureController: DevicePostureController,
    @Main private val mainDispatcher: CoroutineDispatcher
) : DevicePostureRepository {
    override val currentDevicePosture: Flow<DevicePosture>
        get() =
            conflatedCallbackFlow {
                    val sendPostureUpdate = { posture: Int ->
                        val currentDevicePosture = DevicePosture.toPosture(posture)
                        trySendWithFailureLogging(
                            currentDevicePosture,
                            TAG,
                            "Error sending posture update to $currentDevicePosture"
                        )
                    }
                    val callback = DevicePostureController.Callback { sendPostureUpdate(it) }
                    postureController.addCallback(callback)
                    sendPostureUpdate(postureController.devicePosture)

                    awaitClose { postureController.removeCallback(callback) }
                }
                .flowOn(mainDispatcher) // DevicePostureController requirement

    companion object {
        const val TAG = "PostureRepositoryImpl"
    }
}
