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
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class FakeFingerprintPropertyRepository @Inject constructor() : FingerprintPropertyRepository {

    private val _sensorId: MutableStateFlow<Int> = MutableStateFlow(-1)
    override val sensorId = _sensorId.asStateFlow()

    private val _strength: MutableStateFlow<SensorStrength> =
        MutableStateFlow(SensorStrength.CONVENIENCE)
    override val strength = _strength.asStateFlow()

    private val _sensorType: MutableStateFlow<FingerprintSensorType> =
        MutableStateFlow(FingerprintSensorType.UNKNOWN)
    override val sensorType = _sensorType.asStateFlow()

    private val _sensorLocations: MutableStateFlow<Map<String, SensorLocationInternal>> =
        MutableStateFlow(mapOf("" to SensorLocationInternal.DEFAULT))
    override val sensorLocations = _sensorLocations.asStateFlow()

    fun setProperties(
        sensorId: Int,
        strength: SensorStrength,
        sensorType: FingerprintSensorType,
        sensorLocations: Map<String, SensorLocationInternal>
    ) {
        _sensorId.value = sensorId
        _strength.value = strength
        _sensorType.value = sensorType
        _sensorLocations.value = sensorLocations
    }

    /** setProperties as if the device supports UDFPS_OPTICAL. */
    fun supportsUdfps() {
        setProperties(
            sensorId = 0,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.UDFPS_OPTICAL,
            sensorLocations = emptyMap(),
        )
    }

    /** setProperties as if the device supports the rear fingerprint sensor. */
    fun supportsRearFps() {
        setProperties(
            sensorId = 0,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.REAR,
            sensorLocations = emptyMap(),
        )
    }
}

@Module
interface FakeFingerprintPropertyRepositoryModule {
    @Binds fun bindFake(fake: FakeFingerprintPropertyRepository): FingerprintPropertyRepository
}
