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
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.BiometricsDomainLayerModule
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository
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
import com.android.systemui.statusbar.notification.data.repository.FakeNotificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.data.repository.FakeDeviceProvisioningRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationIconContainerAlwaysOnDisplayViewModelTest : SysuiTestCase() {

    @Mock private lateinit var dozeParams: DozeParameters
    @Mock private lateinit var screenOffAnimController: ScreenOffAnimationController

    private lateinit var testComponent: TestComponent
    private val underTest: NotificationIconContainerAlwaysOnDisplayViewModel
        get() = testComponent.underTest
    private val deviceEntryRepository: FakeDeviceEntryRepository
        get() = testComponent.deviceEntryRepository
    private val deviceProvisioningRepository: FakeDeviceProvisioningRepository
        get() = testComponent.deviceProvisioningRepository
    private val keyguardRepository: FakeKeyguardRepository
        get() = testComponent.keyguardRepository
    private val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        get() = testComponent.keyguardTransitionRepository
    private val notifsKeyguardRepository: FakeNotificationsKeyguardViewStateRepository
        get() = testComponent.notifsKeyguardRepository
    private val powerRepository: FakePowerRepository
        get() = testComponent.powerRepository
    private val scope: TestScope
        get() = testComponent.scope

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        testComponent =
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

        keyguardRepository.setKeyguardShowing(true)
        keyguardRepository.setKeyguardOccluded(false)
        deviceProvisioningRepository.setFactoryResetProtectionActive(false)
        powerRepository.updateWakefulness(
            rawState = WakefulnessState.AWAKE,
            lastWakeReason = WakeSleepReason.OTHER,
            lastSleepReason = WakeSleepReason.OTHER,
        )
    }

    @Test
    fun animationsEnabled_isFalse_whenFrpIsActive() =
        scope.runTest {
            deviceProvisioningRepository.setFactoryResetProtectionActive(true)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isFalse_whenDeviceAsleepAndNotPulsing() =
        scope.runTest {
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isTrue_whenDeviceAsleepAndPulsing() =
        scope.runTest {
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isFalse_whenStartingToSleepAndNotControlScreenOff() =
        scope.runTest {
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isFalse()
        }

    @Test
    fun animationsEnabled_isTrue_whenStartingToSleepAndControlScreenOff() =
        scope.runTest {
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isTrue_whenNotAsleep() =
        scope.runTest {
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isTrue_whenKeyguardIsShowing() =
        scope.runTest {
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
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
    fun isDozing_startAodTransition() =
        scope.runTest {
            val isDozing by collectLastValue(underTest.isDozing)
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()
            assertThat(isDozing?.value).isTrue()
            assertThat(isDozing?.isAnimating).isTrue()
        }

    @Test
    fun isDozing_startDozeTransition() =
        scope.runTest {
            val isDozing by collectLastValue(underTest.isDozing)
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.DOZING,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()
            assertThat(isDozing?.value).isTrue()
            assertThat(isDozing?.isAnimating).isFalse()
        }

    @Test
    fun isDozing_startDozeToAodTransition() =
        scope.runTest {
            val isDozing by collectLastValue(underTest.isDozing)
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.DOZING,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()
            assertThat(isDozing?.value).isTrue()
            assertThat(isDozing?.isAnimating).isTrue()
        }

    @Test
    fun isNotDozing_startAodToGoneTransition() =
        scope.runTest {
            val isDozing by collectLastValue(underTest.isDozing)
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()
            assertThat(isDozing?.value).isFalse()
            assertThat(isDozing?.isAnimating).isTrue()
        }

    @Test
    fun isDozing_stopAnimation() =
        scope.runTest {
            val isDozing by collectLastValue(underTest.isDozing)
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            assertThat(isDozing?.isAnimating).isEqualTo(true)
            isDozing?.stopAnimating()
            runCurrent()

            assertThat(isDozing?.isAnimating).isEqualTo(false)
        }

    @Test
    fun isNotVisible_pulseExpanding() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            notifsKeyguardRepository.setPulseExpanding(true)
            runCurrent()

            assertThat(isVisible?.value).isFalse()
        }

    @Test
    fun isNotVisible_notOnKeyguard_dontShowAodIconsWhenShade() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OFF,
                to = KeyguardState.GONE,
                scope,
            )
            whenever(screenOffAnimController.shouldShowAodIconsWhenShade()).thenReturn(false)
            runCurrent()

            assertThat(isVisible?.value).isFalse()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun isVisible_bypassEnabled() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            deviceEntryRepository.setBypassEnabled(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
        }

    @Test
    fun isNotVisible_pulseExpanding_notBypassing() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            notifsKeyguardRepository.setPulseExpanding(true)
            deviceEntryRepository.setBypassEnabled(false)
            runCurrent()

            assertThat(isVisible?.value).isEqualTo(false)
        }

    @Test
    fun isVisible_notifsFullyHidden_bypassEnabled() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            notifsKeyguardRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(true)
            notifsKeyguardRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun isVisible_notifsFullyHidden_bypassDisabled_aodDisabled() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            notifsKeyguardRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParams.alwaysOn).thenReturn(false)
            notifsKeyguardRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun isVisible_notifsFullyHidden_bypassDisabled_displayNeedsBlanking() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            notifsKeyguardRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParams.alwaysOn).thenReturn(true)
            whenever(dozeParams.displayNeedsBlanking).thenReturn(true)
            notifsKeyguardRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun isVisible_notifsFullyHidden_bypassDisabled() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            notifsKeyguardRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParams.alwaysOn).thenReturn(true)
            whenever(dozeParams.displayNeedsBlanking).thenReturn(false)
            notifsKeyguardRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun isVisible_stopAnimation() =
        scope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            runCurrent()
            notifsKeyguardRepository.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParams.alwaysOn).thenReturn(true)
            whenever(dozeParams.displayNeedsBlanking).thenReturn(false)
            notifsKeyguardRepository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(true)
            isVisible?.stopAnimating()
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(false)
        }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                BiometricsDomainLayerModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent {

        val underTest: NotificationIconContainerAlwaysOnDisplayViewModel

        val deviceEntryRepository: FakeDeviceEntryRepository
        val deviceProvisioningRepository: FakeDeviceProvisioningRepository
        val keyguardRepository: FakeKeyguardRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val notifsKeyguardRepository: FakeNotificationsKeyguardViewStateRepository
        val powerRepository: FakePowerRepository
        val scope: TestScope

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                mocks: TestMocksModule,
                featureFlags: FakeFeatureFlagsClassicModule,
            ): TestComponent
        }
    }
}
