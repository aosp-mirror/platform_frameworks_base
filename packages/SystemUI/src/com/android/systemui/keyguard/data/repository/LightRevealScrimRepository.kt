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
import androidx.core.animation.Animator
import androidx.core.animation.ValueAnimator
import com.android.keyguard.logging.ScrimLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.power.data.repository.PowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakeSleepReason.TAP
import com.android.systemui.res.R
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LiftReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.PowerButtonReveal
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

val DEFAULT_REVEAL_EFFECT = LiftReveal
const val DEFAULT_REVEAL_DURATION = 500L

/**
 * Encapsulates state relevant to the light reveal scrim, the view used to reveal/hide screen
 * contents during transitions between DOZE or AOD and lockscreen/unlocked.
 */
interface LightRevealScrimRepository {

    /**
     * The reveal effect that should be used for the next lock/unlock. We switch between either the
     * biometric unlock effect (if wake and unlocking) or the non-biometric effect, and position it
     * at the current screen position of the appropriate sensor.
     */
    val revealEffect: Flow<LightRevealEffect>

    val revealAmount: Flow<Float>

    val isAnimating: Boolean

    fun startRevealAmountAnimator(reveal: Boolean, duration: Long = DEFAULT_REVEAL_DURATION)
}

@SysUISingleton
class LightRevealScrimRepositoryImpl
@Inject
constructor(
    keyguardRepository: KeyguardRepository,
    val context: Context,
    powerRepository: PowerRepository,
    private val scrimLogger: ScrimLogger,
) : LightRevealScrimRepository {
    companion object {
        val TAG = LightRevealScrimRepository::class.simpleName!!
    }

    /** The reveal effect used if the device was locked/unlocked via the power button. */
    private val powerButtonRevealEffect: Flow<LightRevealEffect> =
        flowOf(
            PowerButtonReveal(
                context.resources
                    .getDimensionPixelSize(R.dimen.physical_power_button_center_screen_location_y)
                    .toFloat()
            )
        )

    private val tapRevealEffect: Flow<LightRevealEffect> =
        keyguardRepository.lastDozeTapToWakePosition.map {
            it?.let { constructCircleRevealFromPoint(it) } ?: DEFAULT_REVEAL_EFFECT
        }

    /**
     * Reveal effect to use for a fingerprint unlock. This is reconstructed if the fingerprint
     * sensor location on the screen (in pixels) changes due to configuration changes.
     */
    private val fingerprintRevealEffect: Flow<LightRevealEffect> =
        keyguardRepository.fingerprintSensorLocation.map {
            it?.let { constructCircleRevealFromPoint(it) } ?: DEFAULT_REVEAL_EFFECT
        }

    /**
     * Reveal effect to use for a face unlock. This is reconstructed if the face sensor/front camera
     * location on the screen (in pixels) changes due to configuration changes.
     */
    private val faceRevealEffect: Flow<LightRevealEffect> =
        keyguardRepository.faceSensorLocation.map {
            it?.let { constructCircleRevealFromPoint(it) } ?: DEFAULT_REVEAL_EFFECT
        }

    /** The reveal effect we'll use for the next non-biometric unlock (tap, power button, etc). */
    private val nonBiometricRevealEffect: Flow<LightRevealEffect> =
        powerRepository.wakefulness.flatMapLatest { wakefulnessModel ->
            when {
                wakefulnessModel.isAwakeOrAsleepFrom(WakeSleepReason.POWER_BUTTON) ->
                    powerButtonRevealEffect
                wakefulnessModel.isAwakeFrom(TAP) -> tapRevealEffect
                else -> flowOf(LiftReveal)
            }
        }

    private val revealAmountAnimator = ValueAnimator.ofFloat(0f, 1f)

    override val revealAmount: Flow<Float> = callbackFlow {
        val updateListener =
            Animator.AnimatorUpdateListener {
                val value = (it as ValueAnimator).animatedValue as Float
                trySend(value)

                if (value <= 0.0f || value >= 1.0f) {
                    scrimLogger.d(TAG, "revealAmount", value)
                }
            }
        revealAmountAnimator.addUpdateListener(updateListener)
        awaitClose { revealAmountAnimator.removeUpdateListener(updateListener) }
    }
    override val isAnimating: Boolean
        get() = revealAmountAnimator.isRunning

    private var willBeOrIsRevealed: Boolean? = null

    override fun startRevealAmountAnimator(reveal: Boolean, duration: Long) {
        if (reveal == willBeOrIsRevealed) return
        willBeOrIsRevealed = reveal
        revealAmountAnimator.duration = duration
        if (reveal && !revealAmountAnimator.isRunning) {
            revealAmountAnimator.start()
        } else {
            revealAmountAnimator.reverse()
        }
        scrimLogger.d(TAG, "startRevealAmountAnimator, reveal", reveal)
    }

    override val revealEffect: Flow<LightRevealEffect> =
        keyguardRepository.biometricUnlockState
            .flatMapLatest { biometricUnlockState ->
                // Use the biometric reveal for any flavor of wake and unlocking.
                when (biometricUnlockState.mode) {
                    BiometricUnlockMode.WAKE_AND_UNLOCK,
                    BiometricUnlockMode.WAKE_AND_UNLOCK_PULSING,
                    BiometricUnlockMode.WAKE_AND_UNLOCK_FROM_DREAM -> {
                        if (biometricUnlockState.source == BiometricUnlockSource.FACE_SENSOR) {
                            faceRevealEffect
                        } else {
                            fingerprintRevealEffect
                        }
                    }
                    else -> nonBiometricRevealEffect
                }
            }
            .distinctUntilChanged()

    private fun constructCircleRevealFromPoint(point: Point): LightRevealEffect {
        return with(point) {
            val display = checkNotNull(context.display)
            CircleReveal(
                x,
                y,
                startRadius = 0,
                endRadius = max(max(x, display.width - x), max(y, display.height - y)),
            )
        }
    }
}
