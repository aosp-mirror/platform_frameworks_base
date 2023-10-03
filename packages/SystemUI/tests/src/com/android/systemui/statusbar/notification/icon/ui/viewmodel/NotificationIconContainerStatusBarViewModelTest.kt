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

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.BiometricsDomainLayerModule
import com.android.systemui.coroutines.collectLastValue
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
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher
import com.android.systemui.statusbar.phone.data.repository.FakeDarkIconRepository
import com.android.systemui.statusbar.policy.data.repository.FakeDeviceProvisioningRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.whenever
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
class NotificationIconContainerStatusBarViewModelTest : SysuiTestCase() {

    @Mock lateinit var dozeParams: DozeParameters

    private lateinit var testComponent: TestComponent

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        testComponent =
            DaggerNotificationIconContainerStatusBarViewModelTest_TestComponent.factory()
                .create(
                    test = this,
                    featureFlags =
                        FakeFeatureFlagsClassicModule {
                            set(Flags.FACE_AUTH_REFACTOR, value = false)
                            set(Flags.FULL_SCREEN_USER_SWITCHER, value = false)
                        },
                    mocks =
                        TestMocksModule(
                            dozeParameters = dozeParams,
                        ),
                )
                .apply {
                    keyguardRepository.setKeyguardShowing(false)
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
        with(testComponent) {
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
        }

    @Test
    fun animationsEnabled_isFalse_whenDeviceAsleepAndNotPulsing() =
        with(testComponent) {
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
        }

    @Test
    fun animationsEnabled_isTrue_whenDeviceAsleepAndPulsing() =
        with(testComponent) {
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
        }

    @Test
    fun animationsEnabled_isFalse_whenStartingToSleepAndNotControlScreenOff() =
        with(testComponent) {
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
        }

    @Test
    fun animationsEnabled_isTrue_whenStartingToSleepAndControlScreenOff() =
        with(testComponent) {
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
        }

    @Test
    fun animationsEnabled_isTrue_whenNotAsleep() =
        with(testComponent) {
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
        }

    @Test
    fun animationsEnabled_isTrue_whenKeyguardIsNotShowing() =
        with(testComponent) {
            scope.runTest {
                val animationsEnabled by collectLastValue(underTest.animationsEnabled)

                keyguardTransitionRepository.sendTransitionStep(
                    TransitionStep(
                        transitionState = TransitionState.STARTED,
                    )
                )
                keyguardRepository.setKeyguardShowing(true)
                runCurrent()

                assertThat(animationsEnabled).isFalse()

                keyguardRepository.setKeyguardShowing(false)
                runCurrent()

                assertThat(animationsEnabled).isTrue()
            }
        }

    @Test
    fun iconColors_testsDarkBounds() =
        with(testComponent) {
            scope.runTest {
                darkIconRepository.darkState.value =
                    SysuiDarkIconDispatcher.DarkChange(
                        emptyList(),
                        0f,
                        0xAABBCC,
                    )
                val iconColorsLookup by collectLastValue(underTest.iconColors)
                assertThat(iconColorsLookup).isNotNull()

                val iconColors = iconColorsLookup?.iconColors(Rect())
                assertThat(iconColors).isNotNull()
                iconColors!!

                assertThat(iconColors.tint).isEqualTo(0xAABBCC)

                val staticDrawableColor = iconColors.staticDrawableColor(Rect(), isColorized = true)

                assertThat(staticDrawableColor).isEqualTo(0xAABBCC)
            }
        }

    @Test
    fun iconColors_staticDrawableColor_nonColorized() =
        with(testComponent) {
            scope.runTest {
                darkIconRepository.darkState.value =
                    SysuiDarkIconDispatcher.DarkChange(
                        emptyList(),
                        0f,
                        0xAABBCC,
                    )
                val iconColorsLookup by collectLastValue(underTest.iconColors)
                val iconColors = iconColorsLookup?.iconColors(Rect())
                val staticDrawableColor =
                    iconColors?.staticDrawableColor(Rect(), isColorized = false)
                assertThat(staticDrawableColor).isEqualTo(DarkIconDispatcher.DEFAULT_ICON_TINT)
            }
        }

    @Test
    fun iconColors_staticDrawableColor_isColorized_notInDarkTintArea() =
        with(testComponent) {
            scope.runTest {
                darkIconRepository.darkState.value =
                    SysuiDarkIconDispatcher.DarkChange(
                        listOf(Rect(0, 0, 5, 5)),
                        0f,
                        0xAABBCC,
                    )
                val iconColorsLookup by collectLastValue(underTest.iconColors)
                val iconColors = iconColorsLookup?.iconColors(Rect(1, 1, 4, 4))
                val staticDrawableColor =
                    iconColors?.staticDrawableColor(Rect(6, 6, 7, 7), isColorized = true)
                assertThat(staticDrawableColor).isEqualTo(DarkIconDispatcher.DEFAULT_ICON_TINT)
            }
        }

    @Test
    fun iconColors_notInDarkTintArea() =
        with(testComponent) {
            scope.runTest {
                darkIconRepository.darkState.value =
                    SysuiDarkIconDispatcher.DarkChange(
                        listOf(Rect(0, 0, 5, 5)),
                        0f,
                        0xAABBCC,
                    )
                val iconColorsLookup by collectLastValue(underTest.iconColors)
                val iconColors = iconColorsLookup?.iconColors(Rect(6, 6, 7, 7))
                assertThat(iconColors).isNull()
            }
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

        val underTest: NotificationIconContainerStatusBarViewModel

        val darkIconRepository: FakeDarkIconRepository
        val deviceProvisioningRepository: FakeDeviceProvisioningRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val keyguardRepository: FakeKeyguardRepository
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
