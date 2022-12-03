/*
 * Copyright (C) 2022 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.data.repository

import android.content.Context
import android.graphics.Point
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LiftReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.PowerButtonReveal
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

val DEFAULT_REVEAL_EFFECT = LiftReveal

/**
 * Encapsulates state relevant to the light reveal scrim, the view used to reveal/hide screen
 * contents during transitions between AOD and lockscreen/unlocked.
 */
interface LightRevealScrimRepository {

    /**
     * The reveal effect that should be used for the next lock/unlock. We switch between either the
     * biometric unlock effect (if wake and unlocking) or the non-biometric effect, and position it
     * at the current screen position of the appropriate sensor.
     */
    val revealEffect: Flow<LightRevealEffect>
}

@SysUISingleton
class LightRevealScrimRepositoryImpl
@Inject
constructor(
    keyguardRepository: KeyguardRepository,
    val context: Context,
) : LightRevealScrimRepository {

    /** The reveal effect used if the device was locked/unlocked via the power button. */
    private val powerButtonReveal =
        PowerButtonReveal(
            context.resources
                .getDimensionPixelSize(R.dimen.physical_power_button_center_screen_location_y)
                .toFloat()
        )

    /**
     * Reveal effect to use for a fingerprint unlock. This is reconstructed if the fingerprint
     * sensor location on the screen (in pixels) changes due to configuration changes.
     */
    private val fingerprintRevealEffect: Flow<LightRevealEffect?> =
        keyguardRepository.fingerprintSensorLocation.map {
            it?.let { constructCircleRevealFromPoint(it) }
        }

    /**
     * Reveal effect to use for a face unlock. This is reconstructed if the face sensor/front camera
     * location on the screen (in pixels) changes due to configuration changes.
     */
    private val faceRevealEffect: Flow<LightRevealEffect?> =
        keyguardRepository.faceSensorLocation.map { it?.let { constructCircleRevealFromPoint(it) } }

    /**
     * The reveal effect we'll use for the next biometric unlock animation. We switch between the
     * fingerprint/face unlock effect flows depending on the biometric unlock source.
     */
    private val biometricRevealEffect: Flow<LightRevealEffect?> =
        keyguardRepository.biometricUnlockSource.flatMapLatest { source ->
            when (source) {
                BiometricUnlockSource.FINGERPRINT_SENSOR -> fingerprintRevealEffect
                BiometricUnlockSource.FACE_SENSOR -> faceRevealEffect
                else -> flowOf(null)
            }
        }

    /** The reveal effect we'll use for the next non-biometric unlock (tap, power button, etc). */
    private val nonBiometricRevealEffect: Flow<LightRevealEffect?> =
        keyguardRepository.wakefulness.map { wakefulnessModel ->
            val wakingUpFromPowerButton =
                wakefulnessModel.isWakingUpOrAwake &&
                    wakefulnessModel.lastWakeReason == WakeSleepReason.POWER_BUTTON
            val sleepingFromPowerButton =
                !wakefulnessModel.isWakingUpOrAwake &&
                    wakefulnessModel.lastSleepReason == WakeSleepReason.POWER_BUTTON

            if (wakingUpFromPowerButton || sleepingFromPowerButton) {
                powerButtonReveal
            } else {
                LiftReveal
            }
        }

    override val revealEffect =
        combine(
                keyguardRepository.biometricUnlockState,
                biometricRevealEffect,
                nonBiometricRevealEffect
            ) { biometricUnlockState, biometricReveal, nonBiometricReveal ->

                // Use the biometric reveal for any flavor of wake and unlocking.
                when (biometricUnlockState) {
                    BiometricUnlockModel.WAKE_AND_UNLOCK,
                    BiometricUnlockModel.WAKE_AND_UNLOCK_PULSING,
                    BiometricUnlockModel.WAKE_AND_UNLOCK_FROM_DREAM -> biometricReveal
                    else -> nonBiometricReveal
                }
                    ?: DEFAULT_REVEAL_EFFECT
            }
            .distinctUntilChanged()

    private fun constructCircleRevealFromPoint(point: Point): LightRevealEffect {
        return with(point) {
            CircleReveal(
                x,
                y,
                startRadius = 0,
                endRadius =
                    max(max(x, context.display.width - x), max(y, context.display.height - y)),
            )
        }
    }
}
