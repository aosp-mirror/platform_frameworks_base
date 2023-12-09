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

package com.android.systemui.statusbar.notification.footer.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestMocksModule
import com.android.systemui.collectLastValue
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ActivatableNotificationViewModelModule
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.user.domain.interactor.HeadlessSystemUserModeModule
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@EnableFlags(FooterViewRefactor.FLAG_NAME)
class FooterViewModelTest : SysuiTestCase() {
    private lateinit var footerViewModel: FooterViewModel

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                ActivatableNotificationViewModelModule::class,
                FooterViewModelModule::class,
                HeadlessSystemUserModeModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<Optional<FooterViewModel>> {
        val activeNotificationListRepository: ActiveNotificationListRepository
        val configurationRepository: FakeConfigurationRepository
        val keyguardRepository: FakeKeyguardRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val shadeRepository: FakeShadeRepository
        val powerRepository: FakePowerRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private val dozeParameters: DozeParameters = mock()

    private val testComponent: TestComponent =
        DaggerFooterViewModelTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        set(com.android.systemui.flags.Flags.FULL_SCREEN_USER_SWITCHER, true)
                    },
                mocks =
                    TestMocksModule(
                        dozeParameters = dozeParameters,
                    )
            )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // The underTest in the component is Optional, because that matches the provider we
        // currently have for the footer view model.
        footerViewModel = testComponent.underTest.get()
    }

    @Test
    fun testMessageVisible_whenFilteredNotifications() =
        testComponent.runTest {
            val visible by collectLastValue(footerViewModel.message.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true

            assertThat(visible).isTrue()
        }

    @Test
    fun testMessageVisible_whenNoFilteredNotifications() =
        testComponent.runTest {
            val visible by collectLastValue(footerViewModel.message.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false

            assertThat(visible).isFalse()
        }

    @Test
    fun testClearAllButtonVisible_whenHasClearableNotifs() =
        testComponent.runTest {
            val visible by collectLastValue(footerViewModel.clearAllButton.isVisible)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            assertThat(visible?.value).isTrue()
        }

    @Test
    fun testClearAllButtonVisible_whenHasNoClearableNotifs() =
        testComponent.runTest {
            val visible by collectLastValue(footerViewModel.clearAllButton.isVisible)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            runCurrent()

            assertThat(visible?.value).isFalse()
        }

    @Test
    fun testClearAllButtonAnimating_whenShadeExpandedAndTouchable() =
        testComponent.runTest {
            val visible by collectLastValue(footerViewModel.clearAllButton.isVisible)
            runCurrent()

            // WHEN shade is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setLegacyShadeExpansion(1f)
            // AND QS not expanded
            shadeRepository.setQsExpansion(0f)
            // AND device is awake
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            runCurrent()

            // AND there are clearable notifications
            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            // THEN button visibility should animate
            assertThat(visible?.isAnimating).isTrue()
        }

    @Test
    fun testClearAllButtonAnimating_whenShadeNotExpanded() =
        testComponent.runTest {
            val visible by collectLastValue(footerViewModel.clearAllButton.isVisible)
            runCurrent()

            // WHEN shade is collapsed
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setLegacyShadeExpansion(0f)
            // AND QS not expanded
            shadeRepository.setQsExpansion(0f)
            // AND device is awake
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            runCurrent()

            // AND there are clearable notifications
            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            // THEN button visibility should not animate
            assertThat(visible?.isAnimating).isFalse()
        }
}
