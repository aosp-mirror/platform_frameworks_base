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

import android.hardware.biometrics.SensorLocationInternal
import android.hardware.fingerprint.FingerprintManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.shareIn

/**
 * A repository for the global state of FingerprintProperty.
 *
 * There is never more than one instance of the FingerprintProperty at any given time.
 */
interface FingerprintPropertyRepository {

    /**
     * If the repository is initialized or not. Other properties are defaults until this is true.
     */
    val isInitialized: Flow<Boolean>

    /** The id of fingerprint sensor. */
    val sensorId: StateFlow<Int>

    /** The security strength of sensor (convenience, weak, strong). */
    val strength: StateFlow<SensorStrength>

    /** The types of fingerprint sensor (rear, ultrasonic, optical, etc.). */
    val sensorType: StateFlow<FingerprintSensorType>

    /** The sensor location relative to each physical display. */
    val sensorLocations: StateFlow<Map<String, SensorLocationInternal>>
}

@SysUISingleton
class FingerprintPropertyRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val fingerprintManager: FingerprintManager
) : FingerprintPropertyRepository {

    override val isInitialized: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        override fun onAllAuthenticatorsRegistered(
                            sensors: List<FingerprintSensorPropertiesInternal>
                        ) {
                            if (sensors.isNotEmpty()) {
                                setProperties(sensors[0])
                                trySendWithFailureLogging(true, TAG, "initialize properties")
                            }
                        }
                    }
                fingerprintManager.addAuthenticatorsRegisteredCallback(callback)
                trySendWithFailureLogging(false, TAG, "initial value defaulting to false")
                awaitClose {}
            }
            .shareIn(scope = applicationScope, started = SharingStarted.Eagerly, replay = 1)

    private val _sensorId: MutableStateFlow<Int> = MutableStateFlow(-1)
    override val sensorId: StateFlow<Int> = _sensorId.asStateFlow()

    private val _strength: MutableStateFlow<SensorStrength> =
        MutableStateFlow(SensorStrength.CONVENIENCE)
    override val strength = _strength.asStateFlow()

    private val _sensorType: MutableStateFlow<FingerprintSensorType> =
        MutableStateFlow(FingerprintSensorType.UNKNOWN)
    override val sensorType = _sensorType.asStateFlow()

    private val _sensorLocations: MutableStateFlow<Map<String, SensorLocationInternal>> =
        MutableStateFlow(mapOf("" to SensorLocationInternal.DEFAULT))
    override val sensorLocations: StateFlow<Map<String, SensorLocationInternal>> =
        _sensorLocations.asStateFlow()

    private fun setProperties(prop: FingerprintSensorPropertiesInternal) {
        _sensorId.value = prop.sensorId
        _strength.value = sensorStrengthIntToObject(prop.sensorStrength)
        _sensorType.value = sensorTypeIntToObject(prop.sensorType)
        _sensorLocations.value =
            prop.allLocations.associateBy { sensorLocationInternal ->
                sensorLocationInternal.displayId
            }
    }

    companion object {
        private const val TAG = "FingerprintPropertyRepositoryImpl"
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
