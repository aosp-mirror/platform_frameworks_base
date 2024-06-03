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

package com.android.systemui.keyguard.data.repository

import android.graphics.Point
import android.os.PowerManager.WAKE_REASON_UNKNOWN
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.powerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LightRevealScrimRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val fakeKeyguardRepository = kosmos.fakeKeyguardRepository
    private val powerRepository = kosmos.powerRepository
    private lateinit var underTest: LightRevealScrimRepositoryImpl

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            LightRevealScrimRepositoryImpl(
                kosmos.fakeKeyguardRepository,
                context,
                powerRepository,
                mock()
            )
    }

    @Test
    fun nextRevealEffect_effectSwitchesBetweenDefaultAndBiometricWithNoDupes() = runTest {
        val values = mutableListOf<LightRevealEffect>()
        val job = launch { underTest.revealEffect.collect { values.add(it) } }

        powerRepository.updateWakefulness(
            rawState = WakefulnessState.STARTING_TO_WAKE,
            lastWakeReason = WakeSleepReason.fromPowerManagerWakeReason(WAKE_REASON_UNKNOWN),
            powerButtonLaunchGestureTriggered =
                powerRepository.wakefulness.value.powerButtonLaunchGestureTriggered,
        )
        powerRepository.updateWakefulness(rawState = WakefulnessState.AWAKE)

        // We should initially emit the default reveal effect.
        runCurrent()
        values.assertEffectsMatchPredicates({ it == DEFAULT_REVEAL_EFFECT })

        // The source and sensor locations are still null, so we should still be using the
        // default reveal despite a biometric unlock.
        fakeKeyguardRepository.setBiometricUnlockState(
            BiometricUnlockMode.WAKE_AND_UNLOCK,
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )

        runCurrent()
        values.assertEffectsMatchPredicates(
            { it == DEFAULT_REVEAL_EFFECT },
        )

        // We got a source but still have no sensor locations, so should be sticking with
        // the default effect.
        fakeKeyguardRepository.setBiometricUnlockState(
            BiometricUnlockMode.WAKE_AND_UNLOCK,
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )

        runCurrent()
        values.assertEffectsMatchPredicates(
            { it == DEFAULT_REVEAL_EFFECT },
        )

        // We got a location for the face sensor, but we unlocked with fingerprint.
        val faceLocation = Point(250, 0)
        fakeKeyguardRepository.setFaceSensorLocation(faceLocation)

        runCurrent()
        values.assertEffectsMatchPredicates(
            { it == DEFAULT_REVEAL_EFFECT },
        )

        // Now we have fingerprint sensor locations, and wake and unlock via fingerprint.
        val fingerprintLocation = Point(500, 500)
        fakeKeyguardRepository.setFingerprintSensorLocation(fingerprintLocation)
        fakeKeyguardRepository.setBiometricUnlockState(
            BiometricUnlockMode.WAKE_AND_UNLOCK_PULSING,
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )

        // We should now have switched to the circle reveal, at the fingerprint location.
        runCurrent()
        values.assertEffectsMatchPredicates(
            { it == DEFAULT_REVEAL_EFFECT },
            {
                it is CircleReveal &&
                    it.centerX == fingerprintLocation.x &&
                    it.centerY == fingerprintLocation.y
            },
        )

        // Subsequent wake and unlocks should not emit duplicate, identical CircleReveals.
        val valuesPrevSize = values.size
        fakeKeyguardRepository.setBiometricUnlockState(
            BiometricUnlockMode.WAKE_AND_UNLOCK_PULSING,
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )
        fakeKeyguardRepository.setBiometricUnlockState(
            BiometricUnlockMode.WAKE_AND_UNLOCK_FROM_DREAM,
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )
        assertEquals(valuesPrevSize, values.size)

        // Non-biometric unlock, we should return to the default reveal.
        fakeKeyguardRepository.setBiometricUnlockState(
            BiometricUnlockMode.NONE,
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )

        runCurrent()
        values.assertEffectsMatchPredicates(
            { it == DEFAULT_REVEAL_EFFECT },
            {
                it is CircleReveal &&
                    it.centerX == fingerprintLocation.x &&
                    it.centerY == fingerprintLocation.y
            },
            { it == DEFAULT_REVEAL_EFFECT },
        )

        // We already have a face location, so switching to face source should update the
        // CircleReveal.
        fakeKeyguardRepository.setBiometricUnlockState(
            BiometricUnlockMode.WAKE_AND_UNLOCK,
            BiometricUnlockSource.FACE_SENSOR
        )
        runCurrent()

        values.assertEffectsMatchPredicates(
            { it == DEFAULT_REVEAL_EFFECT },
            {
                it is CircleReveal &&
                    it.centerX == fingerprintLocation.x &&
                    it.centerY == fingerprintLocation.y
            },
            { it == DEFAULT_REVEAL_EFFECT },
            { it is CircleReveal && it.centerX == faceLocation.x && it.centerY == faceLocation.y },
        )

        job.cancel()
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    fun revealAmount_emitsTo1AfterAnimationStarted() =
        testScope.runTest {
            val value by collectLastValue(underTest.revealAmount)
            runCurrent()
            underTest.startRevealAmountAnimator(true, 500L)
            assertEquals(0.0f, value)
            animatorTestRule.advanceTimeBy(500L)
            assertEquals(1.0f, value)
        }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    fun revealAmount_startingRevealTwiceWontRerunAnimator() =
        testScope.runTest {
            val value by collectLastValue(underTest.revealAmount)
            runCurrent()
            underTest.startRevealAmountAnimator(true, 500L)
            assertEquals(0.0f, value)
            animatorTestRule.advanceTimeBy(250L)
            assertEquals(0.5f, value)
            underTest.startRevealAmountAnimator(true, 500L)
            animatorTestRule.advanceTimeBy(250L)
            assertEquals(1.0f, value)
        }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    fun revealAmount_emitsTo0AfterAnimationStartedReversed() =
        testScope.runTest {
            val lastValue by collectLastValue(underTest.revealAmount)
            runCurrent()
            underTest.startRevealAmountAnimator(true, 500L)
            animatorTestRule.advanceTimeBy(500L)
            underTest.startRevealAmountAnimator(false, 500L)
            animatorTestRule.advanceTimeBy(500L)
            assertEquals(0.0f, lastValue)
        }

    /**
     * Asserts that the list of LightRevealEffects satisfies the list of predicates, in order, with
     * no leftover elements.
     */
    private fun List<LightRevealEffect>.assertEffectsMatchPredicates(
        vararg predicates: (LightRevealEffect) -> Boolean
    ) {
        println(this)
        assertEquals(predicates.size, this.size)

        assertFalse(
            zip(predicates) { effect, predicate -> predicate(effect) }.any { matched -> !matched }
        )
    }
}
