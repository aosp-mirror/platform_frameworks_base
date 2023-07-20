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

import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.biometrics.SensorProperties
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * A repository for the global state of FingerprintProperty.
 *
 * There is never more than one instance of the FingerprintProperty at any given time.
 */
interface FingerprintPropertyRepository {

    /** The id of fingerprint sensor. */
    val sensorId: Flow<Int>

    /** The security strength of sensor (convenience, weak, strong). */
    val strength: Flow<SensorStrength>

    /** The types of fingerprint sensor (rear, ultrasonic, optical, etc.). */
    val sensorType: Flow<FingerprintSensorType>

    /** The sensor location relative to each physical display. */
    val sensorLocations: Flow<Map<String, SensorLocationInternal>>
}

@SysUISingleton
class FingerprintPropertyRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val fingerprintManager: FingerprintManager?
) : FingerprintPropertyRepository {

    private val props: Flow<FingerprintSensorPropertiesInternal> =
        conflatedCallbackFlow {
                val callback =
                    object : IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        override fun onAllAuthenticatorsRegistered(
                            sensors: List<FingerprintSensorPropertiesInternal>
                        ) {
                            if (sensors.isNotEmpty()) {
                                trySendWithFailureLogging(sensors[0], TAG, "initialize properties")
                            } else {
                                trySendWithFailureLogging(
                                    DEFAULT_PROPS,
                                    TAG,
                                    "initialize with default properties"
                                )
                            }
                        }
                    }
                fingerprintManager?.addAuthenticatorsRegisteredCallback(callback)
                trySendWithFailureLogging(DEFAULT_PROPS, TAG, "initialize with default properties")
                awaitClose {}
            }
            .shareIn(scope = applicationScope, started = SharingStarted.Eagerly, replay = 1)

    override val sensorId: Flow<Int> = props.map { it.sensorId }
    override val strength: Flow<SensorStrength> =
        props.map { sensorStrengthIntToObject(it.sensorStrength) }
    override val sensorType: Flow<FingerprintSensorType> =
        props.map { sensorTypeIntToObject(it.sensorType) }
    override val sensorLocations: Flow<Map<String, SensorLocationInternal>> =
        props.map {
            it.allLocations.associateBy { sensorLocationInternal ->
                sensorLocationInternal.displayId
            }
        }

    companion object {
        private const val TAG = "FingerprintPropertyRepositoryImpl"
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

private fun sensorStrengthIntToObject(value: Int): SensorStrength {
    return when (value) {
        0 -> SensorStrength.CONVENIENCE
        1 -> SensorStrength.WEAK
        2 -> SensorStrength.STRONG
        else -> throw IllegalArgumentException("Invalid SensorStrength value: $value")
    }
}

private fun sensorTypeIntToObject(value: Int): FingerprintSensorType {
    return when (value) {
        0 -> FingerprintSensorType.UNKNOWN
        1 -> FingerprintSensorType.REAR
        2 -> FingerprintSensorType.UDFPS_ULTRASONIC
        3 -> FingerprintSensorType.UDFPS_OPTICAL
        4 -> FingerprintSensorType.POWER_BUTTON
        5 -> FingerprintSensorType.HOME_BUTTON
        else -> throw IllegalArgumentException("Invalid SensorType value: $value")
    }
}
