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
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.biometrics.shared.model.toSensorStrength
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** A repository for the global state of Face sensor. */
interface FacePropertyRepository {
    /** Face sensor information, null if it is not available. */
    val sensorInfo: Flow<FaceSensorInfo?>
}

/** Describes a biometric sensor */
data class FaceSensorInfo(val id: Int, val strength: SensorStrength)

private const val TAG = "FaceSensorPropertyRepositoryImpl"

@SysUISingleton
class FacePropertyRepositoryImpl
@Inject
constructor(@Application private val applicationScope: CoroutineScope, faceManager: FaceManager?) :
    FacePropertyRepository {

    private val sensorProps: Flow<List<FaceSensorPropertiesInternal>> =
        faceManager?.let {
            ConflatedCallbackFlow.conflatedCallbackFlow {
                    val callback =
                        object : IFaceAuthenticatorsRegisteredCallback.Stub() {
                            override fun onAllAuthenticatorsRegistered(
                                sensors: List<FaceSensorPropertiesInternal>
                            ) {
                                trySendWithFailureLogging(
                                    sensors,
                                    TAG,
                                    "onAllAuthenticatorsRegistered"
                                )
                            }
                        }
                    it.addAuthenticatorsRegisteredCallback(callback)
                    awaitClose {}
                }
                .shareIn(applicationScope, SharingStarted.Eagerly)
        }
            ?: flowOf(emptyList())

    override val sensorInfo: Flow<FaceSensorInfo?> =
        sensorProps
            .map { it.firstOrNull() }
            .map { it?.let { FaceSensorInfo(it.sensorId, it.sensorStrength.toSensorStrength()) } }
}
