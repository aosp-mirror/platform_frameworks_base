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
 *
 */

package com.android.systemui.biometrics.data.repository

import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback
import android.util.Log
import com.android.systemui.biometrics.shared.model.LockoutMode
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.biometrics.shared.model.toLockoutMode
import com.android.systemui.biometrics.shared.model.toSensorStrength
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** A repository for the global state of Face sensor. */
interface FacePropertyRepository {
    /** Face sensor information, null if it is not available. */
    val sensorInfo: StateFlow<FaceSensorInfo?>

    /** Get the current lockout mode for the user. This makes a binder based service call. */
    suspend fun getLockoutMode(userId: Int): LockoutMode
}

/** Describes a biometric sensor */
data class FaceSensorInfo(val id: Int, val strength: SensorStrength)

private const val TAG = "FaceSensorPropertyRepositoryImpl"

@SysUISingleton
class FacePropertyRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val faceManager: FaceManager?,
) : FacePropertyRepository {

    override val sensorInfo: StateFlow<FaceSensorInfo?> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
                val callback =
                    object : IFaceAuthenticatorsRegisteredCallback.Stub() {
                        override fun onAllAuthenticatorsRegistered(
                            sensors: List<FaceSensorPropertiesInternal>,
                        ) {
                            if (sensors.isEmpty()) return
                            trySendWithFailureLogging(
                                FaceSensorInfo(
                                    sensors.first().sensorId,
                                    sensors.first().sensorStrength.toSensorStrength()
                                ),
                                TAG,
                                "onAllAuthenticatorsRegistered"
                            )
                        }
                    }
                withContext(backgroundDispatcher) {
                    faceManager?.addAuthenticatorsRegisteredCallback(callback)
                }
                awaitClose {}
            }
            .onEach { Log.d(TAG, "sensorProps changed: $it") }
            .stateIn(applicationScope, SharingStarted.Eagerly, null)

    override suspend fun getLockoutMode(userId: Int): LockoutMode {
        if (sensorInfo.value == null || faceManager == null) {
            return LockoutMode.NONE
        }
        return faceManager.getLockoutModeForUser(sensorInfo.value!!.id, userId).toLockoutMode()
    }
}
