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

package com.android.systemui.biometrics.domain.interactor

import android.content.Context
import android.hardware.biometrics.SensorLocationInternal
import android.view.WindowManager
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.domain.model.SideFpsSensorLocation
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.isDefaultOrientation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.log.SideFpsLogger
import com.android.systemui.res.R
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@SysUISingleton
class SideFpsSensorInteractor
@Inject
constructor(
    private val context: Context,
    fingerprintPropertyRepository: FingerprintPropertyRepository,
    windowManager: WindowManager,
    displayStateInteractor: DisplayStateInteractor,
    featureFlags: FeatureFlagsClassic,
    fingerprintInteractiveToAuthProvider: Optional<FingerprintInteractiveToAuthProvider>,
    private val logger: SideFpsLogger,
) {

    private val sensorForCurrentDisplay =
        combine(
                displayStateInteractor.displayChanges,
                fingerprintPropertyRepository.sensorLocations,
                ::Pair
            )
            .map { (_, locations) -> locations[context.display?.uniqueId] }
            .filterNotNull()

    val isAvailable: Flow<Boolean> =
        fingerprintPropertyRepository.sensorType.map { it == FingerprintSensorType.POWER_BUTTON }

    val authenticationDuration: Flow<Long> =
        flowOf(context.resources?.getInteger(R.integer.config_restToUnlockDuration)?.toLong() ?: 0L)

    val isProlongedTouchRequiredForAuthentication: Flow<Boolean> =
        if (
            fingerprintInteractiveToAuthProvider.isEmpty ||
                !featureFlags.isEnabled(Flags.REST_TO_UNLOCK)
        ) {
            flowOf(false)
        } else {
            combine(
                isAvailable,
                fingerprintInteractiveToAuthProvider.get().enabledForCurrentUser
            ) { sfpsAvailable, isSettingEnabled ->
                logger.logStateChange(sfpsAvailable, isSettingEnabled)
                sfpsAvailable && isSettingEnabled
            }
        }

    val sensorLocation: Flow<SideFpsSensorLocation> =
        combine(displayStateInteractor.currentRotation, sensorForCurrentDisplay, ::Pair).map {
            (rotation, sensorLocation: SensorLocationInternal) ->
            val isSensorVerticalInDefaultOrientation = sensorLocation.sensorLocationY != 0
            // device dimensions in the current rotation
            val size = windowManager.maximumWindowMetrics.bounds
            val isDefaultOrientation = rotation.isDefaultOrientation()
            // Width and height are flipped is device is not in rotation_0 or rotation_180
            // Flipping it to the width and height of the device in default orientation.
            val displayWidth = if (isDefaultOrientation) size.width() else size.height()
            val displayHeight = if (isDefaultOrientation) size.height() else size.width()
            val sensorWidth = context.resources?.getInteger(R.integer.config_sfpsSensorWidth) ?: 0

            val (sensorLeft, sensorTop) =
                if (isSensorVerticalInDefaultOrientation) {
                    when (rotation) {
                        DisplayRotation.ROTATION_0 -> {
                            Pair(displayWidth, sensorLocation.sensorLocationY)
                        }
                        DisplayRotation.ROTATION_90 -> {
                            Pair(sensorLocation.sensorLocationY, 0)
                        }
                        DisplayRotation.ROTATION_180 -> {
                            Pair(0, displayHeight - sensorLocation.sensorLocationY - sensorWidth)
                        }
                        DisplayRotation.ROTATION_270 -> {
                            Pair(
                                displayHeight - sensorLocation.sensorLocationY - sensorWidth,
                                displayWidth
                            )
                        }
                    }
                } else {
                    when (rotation) {
                        DisplayRotation.ROTATION_0 -> {
                            Pair(sensorLocation.sensorLocationX, 0)
                        }
                        DisplayRotation.ROTATION_90 -> {
                            Pair(0, displayWidth - sensorLocation.sensorLocationX - sensorWidth)
                        }
                        DisplayRotation.ROTATION_180 -> {
                            Pair(
                                displayWidth - sensorLocation.sensorLocationX - sensorWidth,
                                displayHeight
                            )
                        }
                        DisplayRotation.ROTATION_270 -> {
                            Pair(displayHeight, sensorLocation.sensorLocationX)
                        }
                    }
                }

            logger.sensorLocationStateChanged(
                size,
                rotation,
                displayWidth,
                displayHeight,
                sensorWidth,
                isSensorVerticalInDefaultOrientation
            )

            SideFpsSensorLocation(
                left = sensorLeft,
                top = sensorTop,
                width = sensorWidth,
                isSensorVerticalInDefaultOrientation = isSensorVerticalInDefaultOrientation
            )
        }
}
