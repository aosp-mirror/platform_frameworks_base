/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.biometrics.domain.interactor

import android.content.Context
import android.graphics.Rect
import android.hardware.biometrics.SensorLocationInternal
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.SensorLocation
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class FingerprintPropertyInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val context: Context,
    repository: FingerprintPropertyRepository,
    configurationInteractor: ConfigurationInteractor,
    displayStateInteractor: DisplayStateInteractor,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {
    val propertiesInitialized: Flow<Boolean> = repository.propertiesInitialized
    val isUdfps: StateFlow<Boolean> =
        repository.sensorType
            .map { it.isUdfps() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = repository.sensorType.value.isUdfps(),
            )

    /**
     * Devices with multiple physical displays use unique display ids to determine which sensor is
     * on the active physical display. This value represents a unique physical display id.
     */
    private val uniqueDisplayId: Flow<String> =
        displayStateInteractor.displayChanges
            .map { context.display?.uniqueId }
            .filterNotNull()
            .distinctUntilChanged()

    /**
     * Sensor location for the:
     * - current physical display
     * - device's natural screen resolution
     * - device's natural orientation
     */
    private val unscaledSensorLocation: Flow<SensorLocationInternal> =
        combine(
            repository.sensorLocations,
            uniqueDisplayId,
        ) { locations, displayId ->
            // Devices without multiple physical displays do not use the display id as the key;
            // instead, the key is an empty string.
            locations.getOrDefault(
                displayId,
                locations.getOrDefault("", SensorLocationInternal.DEFAULT)
            )
        }

    /**
     * Sensor location for the:
     * - current physical display
     * - current screen resolution
     * - device's natural orientation
     */
    val sensorLocation: Flow<SensorLocation> =
        combine(
            unscaledSensorLocation,
            configurationInteractor.scaleForResolution,
        ) { unscaledSensorLocation, scale ->
            val sensorLocation =
                SensorLocation(
                    unscaledSensorLocation.sensorLocationX,
                    unscaledSensorLocation.sensorLocationY,
                    unscaledSensorLocation.sensorRadius,
                )
            sensorLocation.scale = scale
            sensorLocation
        }

    /**
     * Sensor location for the:
     * - current physical display
     * - current screen resolution
     * - device's current orientation
     */
    val udfpsSensorBounds: Flow<Rect> =
        udfpsOverlayInteractor.udfpsOverlayParams.map { it.sensorBounds }.distinctUntilChanged()
}
