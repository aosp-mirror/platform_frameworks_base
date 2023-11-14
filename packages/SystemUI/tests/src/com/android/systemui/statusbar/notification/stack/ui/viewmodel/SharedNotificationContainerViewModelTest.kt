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
 *
 */

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.SysUITestComponent
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.collectLastValue
import com.android.runCurrent
import com.android.runTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.user.domain.UserDomainLayerModule
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SharedNotificationContainerViewModelTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<SharedNotificationContainerViewModel> {

        val configurationRepository: FakeConfigurationRepository
        val keyguardRepository: FakeKeyguardRepository
        val keyguardInteractor: KeyguardInteractor
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val shadeRepository: FakeShadeRepository
        val sharedNotificationContainerInteractor: SharedNotificationContainerInteractor

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerSharedNotificationContainerViewModelTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule { set(Flags.FULL_SCREEN_USER_SWITCHER, true) },
                mocks = TestMocksModule(),
            )

    @Test
    fun validateMarginStartInSplitShade() =
        testComponent.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginStart).isEqualTo(0)
        }

    @Test
    fun validateMarginStart() =
        testComponent.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginStart).isEqualTo(20)
        }

    @Test
    fun validateMarginEnd() =
        testComponent.runTest {
            overrideResource(R.dimen.notification_panel_margin_horizontal, 50)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginEnd).isEqualTo(50)
        }

    @Test
    fun validateMarginBottom() =
        testComponent.runTest {
            overrideResource(R.dimen.notification_panel_margin_bottom, 50)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginBottom).isEqualTo(50)
        }

    @Test
    fun validateMarginTopWithLargeScreenHeader() =
        testComponent.runTest {
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 50)
            overrideResource(R.dimen.notification_panel_margin_top, 0)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginTop).isEqualTo(50)
        }

    @Test
    fun validateMarginTop() =
        testComponent.runTest {
            overrideResource(R.bool.config_use_large_screen_shade_header, false)
            overrideResource(R.dimen.large_screen_shade_header_height, 50)
            overrideResource(R.dimen.notification_panel_margin_top, 0)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginTop).isEqualTo(0)
        }

    @Test
    fun isOnLockscreen() =
        testComponent.runTest {
            val isOnLockscreen by collectLastValue(underTest.isOnLockscreen)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )
            assertThat(isOnLockscreen).isFalse()

            // While progressing from lockscreen, should still be true
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    value = 0.8f,
                    transitionState = TransitionState.RUNNING
                )
            )
            assertThat(isOnLockscreen).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )
            assertThat(isOnLockscreen).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.PRIMARY_BOUNCER,
                testScope,
            )
            assertThat(isOnLockscreen).isTrue()
        }

    @Test
    fun isOnLockscreenWithoutShade() =
        testComponent.runTest {
            val isOnLockscreenWithoutShade by collectLastValue(underTest.isOnLockscreenWithoutShade)

            // First on AOD
            shadeRepository.setLockscreenShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope,
            )
            assertThat(isOnLockscreenWithoutShade).isFalse()

            // Now move to lockscreen
            showLockscreen()

            // While state is LOCKSCREEN, validate variations of both shade and qs expansion
            shadeRepository.setLockscreenShadeExpansion(0.1f)
            shadeRepository.setQsExpansion(0f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeRepository.setLockscreenShadeExpansion(0.1f)
            shadeRepository.setQsExpansion(0.1f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeRepository.setLockscreenShadeExpansion(0f)
            shadeRepository.setQsExpansion(0.1f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeRepository.setLockscreenShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            assertThat(isOnLockscreenWithoutShade).isTrue()
        }

    @Test
    fun positionOnLockscreenNotInSplitShade() =
        testComponent.runTest {
            val position by collectLastValue(underTest.position)

            // When not in split shade
            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            runCurrent()

            // Start on lockscreen
            showLockscreen()

            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)

            assertThat(position)
                .isEqualTo(SharedNotificationContainerPosition(top = 1f, bottom = 2f))
        }

    @Test
    fun positionOnLockscreenInSplitShade() =
        testComponent.runTest {
            val position by collectLastValue(underTest.position)

            // When in split shade
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()
            runCurrent()

            // Start on lockscreen
            showLockscreen()

            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)
            runCurrent()

            // Top should be overridden to 0f
            assertThat(position)
                .isEqualTo(SharedNotificationContainerPosition(top = 0f, bottom = 2f))
        }

    @Test
    fun positionOnShade() =
        testComponent.runTest {
            val position by collectLastValue(underTest.position)

            // Start on lockscreen with shade expanded
            showLockscreenWithShadeExpanded()

            // When not in split shade
            sharedNotificationContainerInteractor.setTopPosition(10f)

            assertThat(position)
                .isEqualTo(
                    SharedNotificationContainerPosition(top = 10f, bottom = 0f, animate = true)
                )
        }

    @Test
    fun positionOnQS() =
        testComponent.runTest {
            val position by collectLastValue(underTest.position)

            // Start on lockscreen with shade expanded
            showLockscreenWithQSExpanded()

            // When not in split shade
            sharedNotificationContainerInteractor.setTopPosition(10f)

            assertThat(position)
                .isEqualTo(
                    SharedNotificationContainerPosition(top = 10f, bottom = 0f, animate = false)
                )
        }

    @Test
    fun maxNotificationsOnLockscreen() =
        testComponent.runTest {
            var notificationCount = 10
            val maxNotifications by
                collectLastValue(underTest.getMaxNotifications { notificationCount })

            showLockscreen()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)

            assertThat(maxNotifications).isEqualTo(10)

            // Also updates when directly requested (as it would from NotificationStackScrollLayout)
            notificationCount = 25
            sharedNotificationContainerInteractor.notificationStackChanged()
            assertThat(maxNotifications).isEqualTo(25)
        }

    @Test
    fun maxNotificationsOnLockscreen_DoesNotUpdateWhenUserInteracting() =
        testComponent.runTest {
            var notificationCount = 10
            val maxNotifications by
                collectLastValue(underTest.getMaxNotifications { notificationCount })

            showLockscreen()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)

            assertThat(maxNotifications).isEqualTo(10)

            // Shade expanding... still 10
            shadeRepository.setLockscreenShadeExpansion(0.5f)
            assertThat(maxNotifications).isEqualTo(10)

            notificationCount = 25

            // When shade is expanding by user interaction
            shadeRepository.setLegacyLockscreenShadeTracking(true)

            // Should still be 10, since the user is interacting
            assertThat(maxNotifications).isEqualTo(10)

            shadeRepository.setLegacyLockscreenShadeTracking(false)
            shadeRepository.setLockscreenShadeExpansion(0f)

            // Stopped tracking, show 25
            assertThat(maxNotifications).isEqualTo(25)
        }

    @Test
    fun maxNotificationsOnShade() =
        testComponent.runTest {
            val maxNotifications by collectLastValue(underTest.getMaxNotifications { 10 })

            // Show lockscreen with shade expanded
            showLockscreenWithShadeExpanded()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)

            // -1 means No Limit
            assertThat(maxNotifications).isEqualTo(-1)
        }

    private suspend fun TestComponent.showLockscreen() {
        shadeRepository.setLockscreenShadeExpansion(0f)
        shadeRepository.setQsExpansion(0f)
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope,
        )
    }

    private suspend fun TestComponent.showLockscreenWithShadeExpanded() {
        shadeRepository.setLockscreenShadeExpansion(1f)
        shadeRepository.setQsExpansion(0f)
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope,
        )
    }

    private suspend fun TestComponent.showLockscreenWithQSExpanded() {
        shadeRepository.setLockscreenShadeExpansion(0f)
        shadeRepository.setQsExpansion(1f)
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope,
        )
    }
}
