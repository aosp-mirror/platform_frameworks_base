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

import android.graphics.Rect
import android.graphics.drawable.Icon
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
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.HeadsUpNotificationIconViewStateRepository
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher
import com.android.systemui.statusbar.phone.data.repository.FakeDarkIconRepository
import com.android.systemui.statusbar.policy.data.repository.FakeDeviceProvisioningRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationIconContainerStatusBarViewModelTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                BiometricsDomainLayerModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<NotificationIconContainerStatusBarViewModel> {

        val activeNotificationsRepository: ActiveNotificationListRepository
        val darkIconRepository: FakeDarkIconRepository
        val deviceProvisioningRepository: FakeDeviceProvisioningRepository
        val headsUpViewStateRepository: HeadsUpNotificationIconViewStateRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val keyguardRepository: FakeKeyguardRepository
        val powerRepository: FakePowerRepository
        val shadeRepository: FakeShadeRepository

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

    private val testComponent: TestComponent =
        DaggerNotificationIconContainerStatusBarViewModelTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        set(Flags.FULL_SCREEN_USER_SWITCHER, value = false)
                    },
                mocks =
                    TestMocksModule(
                        dozeParameters = dozeParams,
                    ),
            )

    @Before
    fun setup() {
        testComponent.apply {
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
        testComponent.runTest {
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
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
            val animationsEnabled by collectLastValue(underTest.animationsEnabled)
            runCurrent()
            assertThat(animationsEnabled).isTrue()
        }

    @Test
    fun animationsEnabled_isTrue_whenKeyguardIsNotShowing() =
        testComponent.runTest {
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

    @Test
    fun iconColors_testsDarkBounds() =
        testComponent.runTest {
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

            val staticDrawableColor = iconColors.staticDrawableColor(Rect())

            assertThat(staticDrawableColor).isEqualTo(0xAABBCC)
        }

    @Test
    fun iconColors_staticDrawableColor_notInDarkTintArea() =
        testComponent.runTest {
            darkIconRepository.darkState.value =
                SysuiDarkIconDispatcher.DarkChange(
                    listOf(Rect(0, 0, 5, 5)),
                    0f,
                    0xAABBCC,
                )
            val iconColorsLookup by collectLastValue(underTest.iconColors)
            val iconColors = iconColorsLookup?.iconColors(Rect(1, 1, 4, 4))
            val staticDrawableColor = iconColors?.staticDrawableColor(Rect(6, 6, 7, 7))
            assertThat(staticDrawableColor).isEqualTo(DarkIconDispatcher.DEFAULT_ICON_TINT)
        }

    @Test
    fun iconColors_notInDarkTintArea() =
        testComponent.runTest {
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

    @Test
    fun isolatedIcon_animateOnAppear_shadeCollapsed() =
        testComponent.runTest {
            val icon: Icon = mock()
            shadeRepository.setLegacyShadeExpansion(0f)
            activeNotificationsRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                groupKey = "group",
                                statusBarIcon = icon
                            )
                        )
                    }
                    .build()
            val isolatedIcon by collectLastValue(underTest.isolatedIcon)
            runCurrent()

            headsUpViewStateRepository.isolatedNotification.value = "notif1"
            runCurrent()

            assertThat(isolatedIcon?.value?.notifKey).isEqualTo("notif1")
            assertThat(isolatedIcon?.isAnimating).isTrue()
        }

    @Test
    fun isolatedIcon_dontAnimateOnAppear_shadeExpanded() =
        testComponent.runTest {
            val icon: Icon = mock()
            shadeRepository.setLegacyShadeExpansion(.5f)
            activeNotificationsRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                groupKey = "group",
                                statusBarIcon = icon
                            )
                        )
                    }
                    .build()
            val isolatedIcon by collectLastValue(underTest.isolatedIcon)
            runCurrent()

            headsUpViewStateRepository.isolatedNotification.value = "notif1"
            runCurrent()

            assertThat(isolatedIcon?.value?.notifKey).isEqualTo("notif1")
            assertThat(isolatedIcon?.isAnimating).isFalse()
        }

    @Test
    fun isolatedIcon_updateWhenIconDataChanges() =
        testComponent.runTest {
            val icon: Icon = mock()
            val isolatedIcon by collectLastValue(underTest.isolatedIcon)
            runCurrent()

            headsUpViewStateRepository.isolatedNotification.value = "notif1"
            runCurrent()

            activeNotificationsRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                groupKey = "group",
                                statusBarIcon = icon
                            )
                        )
                    }
                    .build()
            runCurrent()

            assertThat(isolatedIcon?.value?.notifKey).isEqualTo("notif1")
        }
}
