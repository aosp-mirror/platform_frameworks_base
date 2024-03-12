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

import android.Manifest.permission.USE_BIOMETRIC_INTERNAL
import android.annotation.RequiresPermission
import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.biometrics.SensorProperties
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.biometrics.shared.model.toSensorStrength
import com.android.systemui.biometrics.shared.model.toSensorType
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * A repository for the global state of FingerprintProperty.
 *
 * There is never more than one instance of the FingerprintProperty at any given time.
 */
interface FingerprintPropertyRepository {
    /** Whether the fingerprint properties have been initialized yet. */
    val propertiesInitialized: StateFlow<Boolean>

    /** The id of fingerprint sensor. */
    val sensorId: Flow<Int>

    /** The security strength of sensor (convenience, weak, strong). */
    val strength: Flow<SensorStrength>

    /** The types of fingerprint sensor (rear, ultrasonic, optical, etc.). */
    val sensorType: StateFlow<FingerprintSensorType>

    /** The sensor location relative to each physical display. */
    val sensorLocations: Flow<Map<String, SensorLocationInternal>>
}

@SysUISingleton
class FingerprintPropertyRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val fingerprintManager: FingerprintManager?,
) : FingerprintPropertyRepository {

    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    private val props: StateFlow<FingerprintSensorPropertiesInternal> =
        conflatedCallbackFlow {
                val callback =
                    object : IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        override fun onAllAuthenticatorsRegistered(
                            sensors: List<FingerprintSensorPropertiesInternal>
                        ) {
                            if (sensors.isEmpty()) {
                                trySendWithFailureLogging(
                                    DEFAULT_PROPS,
                                    TAG,
                                    "no registered sensors, use default props"
                                )
                            } else {
                                trySendWithFailureLogging(
                                    sensors[0],
                                    TAG,
                                    "update properties on authenticators registered"
                                )
                            }
                        }
                    }
                withContext(backgroundDispatcher) {
                    fingerprintManager?.addAuthenticatorsRegisteredCallback(callback)
                }
                awaitClose {}
            }
            .stateIn(
                applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = UNINITIALIZED_PROPS,
            )

    override val propertiesInitialized: StateFlow<Boolean> =
        props
            .map { it != UNINITIALIZED_PROPS }
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = props.value != UNINITIALIZED_PROPS,
            )

    override val sensorId: Flow<Int> = props.map { it.sensorId }

    override val strength: Flow<SensorStrength> = props.map { it.sensorStrength.toSensorStrength() }

    override val sensorType: StateFlow<FingerprintSensorType> =
        props
            .map { it.sensorType.toSensorType() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = props.value.sensorType.toSensorType(),
            )

    override val sensorLocations: Flow<Map<String, SensorLocationInternal>> =
        props.map {
            it.allLocations.associateBy { sensorLocationInternal ->
                sensorLocationInternal.displayId
            }
        }

    companion object {
        private const val TAG = "FingerprintPropertyRepositoryImpl"
        private val UNINITIALIZED_PROPS =
            FingerprintSensorPropertiesInternal(
                -2 /* sensorId */,
                SensorProperties.STRENGTH_CONVENIENCE,
                0 /* maxEnrollmentsPerUser */,
                listOf<ComponentInfoInternal>(),
                FingerprintSensorProperties.TYPE_UNKNOWN,
                false /* halControlsIllumination */,
                true /* resetLockoutRequiresHardwareAuthToken */,
                listOf<SensorLocationInternal>(SensorLocationInternal.DEFAULT)
            )
        private val DEFAULT_PROPS =
            FingerprintSensorPropertiesInternal(
                -1 /* sensorId */,
                SensorProperties.STRENGTH_CONVENIENCE,
                0 /* maxEnrollmentsPerUser */,
                listOf<ComponentInfoInternal>(),
                FingerprintSensorProperties.TYPE_UNKNOWN,
                false /* halControlsIllumination */,
                true /* resetLockoutRequiresHardwareAuthToken */,
                listOf<SensorLocationInternal>(SensorLocationInternal.DEFAULT)
            )
    }
}
