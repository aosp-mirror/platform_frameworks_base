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

package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.content.res.mainResources
import android.platform.test.annotations.DisableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR
import com.android.systemui.Flags.FLAG_NEW_AOD_TRANSITION
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.alwaysOnDisplayNotificationIconsInteractor
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationIconContainerAlwaysOnDisplayViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, value = false) }
        }

    val underTest =
        NotificationIconContainerAlwaysOnDisplayViewModel(
            kosmos.testDispatcher,
            kosmos.alwaysOnDisplayNotificationIconsInteractor,
            kosmos.keyguardInteractor,
            kosmos.keyguardTransitionInteractor,
            kosmos.mainResources,
            kosmos.shadeInteractor,
        )
    val testScope = kosmos.testScope
    val keyguardRepository = kosmos.fakeKeyguardRepository
    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val powerRepository = kosmos.fakePowerRepository

    @Before
    fun setup() {
        keyguardRepository.setKeyguardShowing(true)
        keyguardRepository.setKeyguardOccluded(false)
        kosmos.fakePowerRepository.updateWakefulness(
            rawState = WakefulnessState.AWAKE,
            lastWakeReason = WakeSleepReason.OTHER,
            lastSleepReason = WakeSleepReason.OTHER,
        )
        mSetFlagsRule.enableFlags(FLAG_NEW_AOD_TRANSITION)
    }

    @Test
    fun animationsEnabled_isFalse_whenDeviceAsleepAndNotPulsing() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.ASLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(
                    to = DozeStateModel.DOZE_AOD,
                )
            )
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isTrue_whenDeviceAsleepAndPulsing() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.ASLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(
                    to = DozeStateModel.DOZE_PULSING,
                )
            )
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isFalse_whenStartingToSleepAndNotControlScreenOff() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            whenever(kosmos.dozeParameters.shouldControlScreenOff()).thenReturn(false)
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isTrue_whenStartingToSleepAndControlScreenOff() =
        testScope.runTest {
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            assertThat(animationsEnabled).isTrue()

            powerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            whenever(kosmos.dozeParameters.shouldControlScreenOff()).thenReturn(true)
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isTrue_whenNotAsleep() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    @DisableFlags(FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun animationsEnabled_isTrue_whenKeyguardIsShowing() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()

            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            runCurrent()

            assertThat(animationsEnabled).isTrue()

            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            assertThat(animationsEnabled).isFalse()

            keyguardRepository.setKeyguardShowing(false)
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun tintAlpha_isZero_whenNotOnAodOrDozing() =
        testScope.runTest {
            val tintAlpha by collectLastValue(underTest.tintAlpha)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.DOZING,
                to = KeyguardState.GONE,
                testScope,
            )
            runCurrent()
            assertThat(tintAlpha).isZero()
        }

    @Test
    fun tintAlpha_isOne_whenOnAod() =
        testScope.runTest {
            val tintAlpha by collectLastValue(underTest.tintAlpha)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
                testScope,
            )
            runCurrent()
            assertThat(tintAlpha).isEqualTo(1f)
        }

    @Test
    fun tintAlpha_isOne_whenDozing() =
        testScope.runTest {
            val tintAlpha by collectLastValue(underTest.tintAlpha)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.DOZING,
                testScope,
            )
            assertThat(tintAlpha).isEqualTo(1f)
        }

    @Test
    fun tintAlpha_isOne_whenTransitionFromAodToDoze() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
                testScope,
            )
            val tintAlpha by collectLastValue(underTest.tintAlpha)
            runCurrent()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.AOD,
                    to = KeyguardState.DOZING,
                    value = 0f,
                )
            )
            runCurrent()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.AOD,
                    to = KeyguardState.DOZING,
                    value = 0.5f,
                )
            )
            runCurrent()

            assertThat(tintAlpha).isEqualTo(1f)
        }

    @Test
    fun tintAlpha_isFraction_midTransitionToAod() =
        testScope.runTest {
            val tintAlpha by collectLastValue(underTest.tintAlpha)
            runCurrent()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                )
            )
            runCurrent()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0.5f,
                )
            )
            runCurrent()

            assertThat(tintAlpha).isEqualTo(0.5f)
        }

    @Test
    fun iconAnimationsEnabled_whenOnLockScreen() =
        testScope.runTest {
            val iconAnimationsEnabled by collectLastValue(underTest.areIconAnimationsEnabled)
            runCurrent()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            assertThat(iconAnimationsEnabled).isTrue()
        }

    @Test
    fun iconAnimationsDisabled_whenOnAod() =
        testScope.runTest {
            val iconAnimationsEnabled by collectLastValue(underTest.areIconAnimationsEnabled)
            runCurrent()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
                testScope,
            )

            assertThat(iconAnimationsEnabled).isFalse()
        }

    @Test
    fun iconAnimationsDisabled_whenDozing() =
        testScope.runTest {
            val iconAnimationsEnabled by collectLastValue(underTest.areIconAnimationsEnabled)
            runCurrent()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.DOZING,
                testScope,
            )

            assertThat(iconAnimationsEnabled).isFalse()
        }
}
