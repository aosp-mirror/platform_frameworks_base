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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestMocksModule
import com.android.systemui.biometrics.domain.BiometricsDomainLayerModule
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.data.repository.FakeDeviceProvisioningRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationIconContainerAlwaysOnDisplayViewModelTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                BiometricsDomainLayerModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent :
        SysUITestComponent<NotificationIconContainerAlwaysOnDisplayViewModel> {

        val deviceProvisioningRepository: FakeDeviceProvisioningRepository
        val keyguardRepository: FakeKeyguardRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val powerRepository: FakePowerRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                mocks: TestMocksModule,
                featureFlags: FakeFeatureFlagsClassicModule,
            ): TestComponent
        }
    }

    private val dozeParams: DozeParameters = mock()
    private val screenOffAnimController: ScreenOffAnimationController = mock()

    private val testComponent: TestComponent =
        DaggerNotificationIconContainerAlwaysOnDisplayViewModelTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        setDefault(Flags.FACE_AUTH_REFACTOR)
                        set(Flags.FULL_SCREEN_USER_SWITCHER, value = false)
                        setDefault(Flags.NEW_AOD_TRANSITION)
                    },
                mocks =
                    TestMocksModule(
                        dozeParameters = dozeParams,
                        screenOffAnimationController = screenOffAnimController,
                    ),
            )

    @Before
    fun setup() {
        testComponent.apply {
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            deviceProvisioningRepository.setFactoryResetProtectionActive(false)
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.OTHER,
                lastSleepReason = WakeSleepReason.OTHER,
            )
        }
    }

    @Test
    fun animationsEnabled_isFalse_whenFrpIsActive() =
        testComponent.runTest {
            deviceProvisioningRepository.setFactoryResetProtectionActive(true)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isFalse_whenDeviceAsleepAndNotPulsing() =
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
            whenever(dozeParams.shouldControlScreenOff()).thenReturn(false)
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isTrue_whenStartingToSleepAndControlScreenOff() =
        testComponent.runTest {
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
            whenever(dozeParams.shouldControlScreenOff()).thenReturn(true)
            val animationsEnabled by collectLastValue(underTest.areContainerChangesAnimated)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isTrue_whenNotAsleep() =
        testComponent.runTest {
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
    fun animationsEnabled_isTrue_whenKeyguardIsShowing() =
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
