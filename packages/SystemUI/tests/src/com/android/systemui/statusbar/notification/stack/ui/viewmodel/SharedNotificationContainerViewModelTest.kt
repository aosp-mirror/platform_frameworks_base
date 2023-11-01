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
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
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
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
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
class SharedNotificationContainerViewModelTest : SysuiTestCase() {

    private lateinit var testComponent: TestComponent

    private val shadeRepository
        get() = testComponent.shadeRepository
    private val keyguardRepository
        get() = testComponent.keyguardRepository
    private val configurationRepository
        get() = testComponent.configurationRepository
    private val sharedNotificationContainerInteractor: SharedNotificationContainerInteractor
        get() = testComponent.sharedNotificationContainerInteractor
    private val underTest: SharedNotificationContainerViewModel
        get() = testComponent.underTest
    private val keyguardInteractor: KeyguardInteractor
        get() = testComponent.keyguardInteractor
    private val keyguardTransitionRepository
        get() = testComponent.keyguardTransitionRepository
    private val testScope
        get() = testComponent.testScope

    @Mock private lateinit var notificationStackSizeCalculator: NotificationStackSizeCalculator
    @Mock
    private lateinit var notificationStackScrollLayoutController:
        NotificationStackScrollLayoutController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(notificationStackScrollLayoutController.getView()).thenReturn(mock())
        whenever(notificationStackScrollLayoutController.getShelfHeight()).thenReturn(0)

        testComponent =
            DaggerSharedNotificationContainerViewModelTest_TestComponent.factory()
                .create(
                    test = this,
                    featureFlags =
                        FakeFeatureFlagsClassicModule {
                            set(Flags.FULL_SCREEN_USER_SWITCHER, true)
                        },
                    mocks =
                        TestMocksModule(
                            notificationStackSizeCalculator = notificationStackSizeCalculator,
                            notificationStackScrollLayoutController =
                                notificationStackScrollLayoutController,
                        )
                )
    }

    @Test
    fun validateMarginStartInSplitShade() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginStart).isEqualTo(0)
        }

    @Test
    fun validateMarginStart() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginStart).isEqualTo(20)
        }

    @Test
    fun validateMarginEnd() =
        testScope.runTest {
            overrideResource(R.dimen.notification_panel_margin_horizontal, 50)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginEnd).isEqualTo(50)
        }

    @Test
    fun validateMarginBottom() =
        testScope.runTest {
            overrideResource(R.dimen.notification_panel_margin_bottom, 50)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginBottom).isEqualTo(50)
        }

    @Test
    fun validateMarginTopWithLargeScreenHeader() =
        testScope.runTest {
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 50)
            overrideResource(R.dimen.notification_panel_margin_top, 0)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginTop).isEqualTo(50)
        }

    @Test
    fun validateMarginTop() =
        testScope.runTest {
            overrideResource(R.bool.config_use_large_screen_shade_header, false)
            overrideResource(R.dimen.large_screen_shade_header_height, 50)
            overrideResource(R.dimen.notification_panel_margin_top, 0)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginTop).isEqualTo(0)
        }

    @Test
    fun isOnLockscreen() =
        testScope.runTest {
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
                this,
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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
            whenever(
                    notificationStackSizeCalculator.computeMaxKeyguardNotifications(
                        any(),
                        any(),
                        any(),
                        any()
                    )
                )
                .thenReturn(10)

            val maxNotifications by collectLastValue(underTest.maxNotifications)

            showLockscreen()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)

            assertThat(maxNotifications).isEqualTo(10)
        }

    @Test
    fun maxNotificationsOnShade() =
        testScope.runTest {
            whenever(
                    notificationStackSizeCalculator.computeMaxKeyguardNotifications(
                        any(),
                        any(),
                        any(),
                        any()
                    )
                )
                .thenReturn(10)
            val maxNotifications by collectLastValue(underTest.maxNotifications)

            // Show lockscreen with shade expanded
            showLockscreenWithShadeExpanded()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)

            // -1 means No Limit
            assertThat(maxNotifications).isEqualTo(-1)
        }

    private suspend fun TestScope.showLockscreen() {
        shadeRepository.setLockscreenShadeExpansion(0f)
        shadeRepository.setQsExpansion(0f)
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            this,
        )
    }

    private suspend fun TestScope.showLockscreenWithShadeExpanded() {
        shadeRepository.setLockscreenShadeExpansion(1f)
        shadeRepository.setQsExpansion(0f)
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            this,
        )
    }

    private suspend fun TestScope.showLockscreenWithQSExpanded() {
        shadeRepository.setLockscreenShadeExpansion(0f)
        shadeRepository.setQsExpansion(1f)
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            this,
        )
    }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent {

        val underTest: SharedNotificationContainerViewModel

        val configurationRepository: FakeConfigurationRepository
        val keyguardRepository: FakeKeyguardRepository
        val keyguardInteractor: KeyguardInteractor
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val shadeRepository: FakeShadeRepository
        val sharedNotificationContainerInteractor: SharedNotificationContainerInteractor
        val testScope: TestScope

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }
}
