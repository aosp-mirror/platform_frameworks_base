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
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
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
    private val testScope = TestScope(StandardTestDispatcher())

    private val disableFlagsRepository = FakeDisableFlagsRepository()
    private val userSetupRepository = FakeUserSetupRepository()
    private val shadeRepository = FakeShadeRepository()
    private val keyguardRepository = FakeKeyguardRepository()

    private lateinit var configurationRepository: FakeConfigurationRepository
    private lateinit var sharedNotificationContainerInteractor:
        SharedNotificationContainerInteractor
    private lateinit var underTest: SharedNotificationContainerViewModel
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var shadeInteractor: ShadeInteractor

    @Mock private lateinit var notificationStackSizeCalculator: NotificationStackSizeCalculator
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var userInteractor: UserInteractor
    @Mock
    private lateinit var notificationStackScrollLayoutController:
        NotificationStackScrollLayoutController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(notificationStackScrollLayoutController.getView()).thenReturn(mock())
        whenever(notificationStackScrollLayoutController.getShelfHeight()).thenReturn(0)

        configurationRepository = FakeConfigurationRepository()
        KeyguardTransitionInteractorFactory.create(
                scope = testScope.backgroundScope,
            )
            .also {
                keyguardInteractor = it.keyguardInteractor
                keyguardTransitionInteractor = it.keyguardTransitionInteractor
                keyguardTransitionRepository = it.repository
            }

        shadeInteractor =
            ShadeInteractor(
                testScope.backgroundScope,
                disableFlagsRepository,
                keyguardRepository,
                userSetupRepository,
                deviceProvisionedController,
                userInteractor,
                shadeRepository,
            )

        sharedNotificationContainerInteractor =
            SharedNotificationContainerInteractor(
                configurationRepository,
                mContext,
            )
        underTest =
            SharedNotificationContainerViewModel(
                sharedNotificationContainerInteractor,
                keyguardInteractor,
                keyguardTransitionInteractor,
                notificationStackSizeCalculator,
                notificationStackScrollLayoutController,
                shadeInteractor
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

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(to = KeyguardState.GONE, transitionState = TransitionState.FINISHED)
            )
            assertThat(isOnLockscreen).isFalse()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.FINISHED
                )
            )
            assertThat(isOnLockscreen).isTrue()
        }

    @Test
    fun isOnLockscreenWithoutShade() =
        testScope.runTest {
            val isOnLockscreenWithoutShade by collectLastValue(underTest.isOnLockscreenWithoutShade)

            // First on AOD
            shadeRepository.setShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    to = KeyguardState.OCCLUDED,
                    transitionState = TransitionState.FINISHED
                )
            )
            assertThat(isOnLockscreenWithoutShade).isFalse()

            // Now move to lockscreen
            showLockscreen()

            // While state is LOCKSCREEN, validate variations of both shade and qs expansion
            shadeRepository.setShadeExpansion(0.1f)
            shadeRepository.setQsExpansion(0f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeRepository.setShadeExpansion(0.1f)
            shadeRepository.setQsExpansion(0.1f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeRepository.setShadeExpansion(0f)
            shadeRepository.setQsExpansion(0.1f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeRepository.setShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            assertThat(isOnLockscreenWithoutShade).isTrue()
        }

    @Test
    fun positionOnLockscreenNotInSplitShade() =
        testScope.runTest {
            val position by collectLastValue(underTest.position)

            // Start on lockscreen
            showLockscreen()

            // When not in split shade
            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()

            keyguardInteractor.sharedNotificationContainerPosition.value =
                SharedNotificationContainerPosition(top = 1f, bottom = 2f)

            assertThat(position)
                .isEqualTo(SharedNotificationContainerPosition(top = 1f, bottom = 2f))
        }

    @Test
    fun positionOnLockscreenInSplitShade() =
        testScope.runTest {
            val position by collectLastValue(underTest.position)

            // Start on lockscreen
            showLockscreen()

            // When in split shade
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()

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
                .isEqualTo(SharedNotificationContainerPosition(top = 10f, bottom = 0f))
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

    private suspend fun showLockscreen() {
        shadeRepository.setShadeExpansion(0f)
        shadeRepository.setQsExpansion(0f)
        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                to = KeyguardState.LOCKSCREEN,
                transitionState = TransitionState.FINISHED
            )
        )
    }

    private suspend fun showLockscreenWithShadeExpanded() {
        shadeRepository.setShadeExpansion(1f)
        shadeRepository.setQsExpansion(0f)
        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                to = KeyguardState.LOCKSCREEN,
                transitionState = TransitionState.FINISHED
            )
        )
    }
}
