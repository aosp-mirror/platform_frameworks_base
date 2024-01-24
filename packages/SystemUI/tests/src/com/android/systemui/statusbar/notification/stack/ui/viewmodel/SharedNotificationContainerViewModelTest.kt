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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.keyguardRootViewModel
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.mockLargeScreenHeaderHelper
import com.android.systemui.statusbar.notification.stack.domain.interactor.sharedNotificationContainerInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SharedNotificationContainerViewModelTest : SysuiTestCase() {

    val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    val testScope = kosmos.testScope
    val configurationRepository = kosmos.fakeConfigurationRepository
    val keyguardRepository = kosmos.fakeKeyguardRepository
    val keyguardInteractor = kosmos.keyguardInteractor
    val keyguardRootViewModel = kosmos.keyguardRootViewModel
    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val communalInteractor = kosmos.communalInteractor
    val shadeRepository = kosmos.shadeRepository
    val sharedNotificationContainerInteractor = kosmos.sharedNotificationContainerInteractor
    val largeScreenHeaderHelper = kosmos.mockLargeScreenHeaderHelper

    val underTest = kosmos.sharedNotificationContainerViewModel

    @Before
    fun setUp() {
        overrideResource(R.bool.config_use_split_notification_shade, false)
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
    fun validatePaddingTopInSplitShade_refactorFlagOff_usesLargeHeaderResource() =
        testScope.runTest {
            mSetFlagsRule.disableFlags(FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR)
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(5)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.paddingTop).isEqualTo(30)
        }

    @Test
    fun validatePaddingTopInSplitShade_refactorFlagOn_usesLargeHeaderHelper() =
        testScope.runTest {
            mSetFlagsRule.enableFlags(FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR)
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(5)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.paddingTop).isEqualTo(40)
        }

    @Test
    fun validatePaddingTop() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.paddingTop).isEqualTo(0)
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
    fun validateMarginTopWithLargeScreenHeader_refactorFlagOff_usesResource() =
        testScope.runTest {
            mSetFlagsRule.disableFlags(FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR)
            val headerResourceHeight = 50
            val headerHelperHeight = 100
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight())
                .thenReturn(headerHelperHeight)
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, headerResourceHeight)
            overrideResource(R.dimen.notification_panel_margin_top, 0)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginTop).isEqualTo(headerResourceHeight)
        }

    @Test
    fun validateMarginTopWithLargeScreenHeader_refactorFlagOn_usesHelper() =
        testScope.runTest {
            mSetFlagsRule.enableFlags(FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR)
            val headerResourceHeight = 50
            val headerHelperHeight = 100
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight())
                .thenReturn(headerHelperHeight)
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, headerResourceHeight)
            overrideResource(R.dimen.notification_panel_margin_top, 0)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginTop).isEqualTo(headerHelperHeight)
        }

    @Test
    fun glanceableHubAlpha() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.glanceableHubAlpha)

            // Start on lockscreen
            showLockscreen()
            assertThat(alpha).isEqualTo(1f)

            // Start transitioning to glanceable hub
            val progress = 0.6f
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GLANCEABLE_HUB,
                    value = 0f,
                )
            )
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.RUNNING,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GLANCEABLE_HUB,
                    value = progress,
                )
            )
            runCurrent()
            // Expected alpha is inverse of progress as notifications are fading away
            assertThat(alpha).isEqualTo(1 - progress)

            // Finish transition to glanceable hub
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GLANCEABLE_HUB,
                    value = 1f,
                )
            )
            val idleTransitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(
                    ObservableCommunalTransitionState.Idle(CommunalSceneKey.Communal)
                )
            communalInteractor.setTransitionState(idleTransitionState)
            runCurrent()
            assertThat(alpha).isEqualTo(0f)

            // While state is GLANCEABLE_HUB, verify alpha is restored to full if glanceable hub is
            // not fully visible.
            shadeRepository.setLockscreenShadeExpansion(0.1f)
            assertThat(alpha).isEqualTo(1f)
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
    fun isOnGlanceableHubWithoutShade() =
        testScope.runTest {
            val isOnGlanceableHubWithoutShade by
                collectLastValue(underTest.isOnGlanceableHubWithoutShade)

            // Start on lockscreen
            showLockscreen()
            assertThat(isOnGlanceableHubWithoutShade).isFalse()

            // Move to glanceable hub
            val idleTransitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(
                    ObservableCommunalTransitionState.Idle(CommunalSceneKey.Communal)
                )
            communalInteractor.setTransitionState(idleTransitionState)
            runCurrent()
            assertThat(isOnGlanceableHubWithoutShade).isTrue()

            // While state is GLANCEABLE_HUB, validate variations of both shade and qs expansion
            shadeRepository.setLockscreenShadeExpansion(0.1f)
            shadeRepository.setQsExpansion(0f)
            assertThat(isOnGlanceableHubWithoutShade).isFalse()

            shadeRepository.setLockscreenShadeExpansion(0.1f)
            shadeRepository.setQsExpansion(0.1f)
            assertThat(isOnGlanceableHubWithoutShade).isFalse()

            shadeRepository.setLockscreenShadeExpansion(0f)
            shadeRepository.setQsExpansion(0.1f)
            assertThat(isOnGlanceableHubWithoutShade).isFalse()

            shadeRepository.setLockscreenShadeExpansion(0f)
            shadeRepository.setQsExpansion(0f)
            assertThat(isOnGlanceableHubWithoutShade).isTrue()
        }

    @Test
    fun boundsOnLockscreenNotInSplitShade() =
        testScope.runTest {
            val bounds by collectLastValue(underTest.bounds)

            // When not in split shade
            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            runCurrent()

            // Start on lockscreen
            showLockscreen()

            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 2f)
            )

            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = 1f, bottom = 2f))
        }

    @Test
    fun boundsOnLockscreenInSplitShade_refactorFlagOff_usesLargeHeaderResource() =
        testScope.runTest {
            mSetFlagsRule.disableFlags(FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR)
            val bounds by collectLastValue(underTest.bounds)

            // When in split shade
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(5)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            configurationRepository.onAnyConfigurationChange()
            runCurrent()

            // Start on lockscreen
            showLockscreen()

            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 2f)
            )
            runCurrent()

            // Top should be equal to bounds (1) + padding adjustment (30)
            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = 31f, bottom = 2f))
        }

    @Test
    fun boundsOnLockscreenInSplitShade_refactorFlagOn_usesLargeHeaderHelper() =
        testScope.runTest {
            mSetFlagsRule.enableFlags(FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR)
            val bounds by collectLastValue(underTest.bounds)

            // When in split shade
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(5)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            configurationRepository.onAnyConfigurationChange()
            runCurrent()

            // Start on lockscreen
            showLockscreen()

            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 2f)
            )
            runCurrent()

            // Top should be equal to bounds (1) + padding adjustment (40)
            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = 41f, bottom = 2f))
        }

    @Test
    fun boundsOnShade() =
        testScope.runTest {
            val bounds by collectLastValue(underTest.bounds)

            // Start on lockscreen with shade expanded
            showLockscreenWithShadeExpanded()

            // When not in split shade
            sharedNotificationContainerInteractor.setTopPosition(10f)

            assertThat(bounds)
                .isEqualTo(NotificationContainerBounds(top = 10f, bottom = 0f, isAnimated = true))
        }

    @Test
    fun boundsOnQS() =
        testScope.runTest {
            val bounds by collectLastValue(underTest.bounds)

            // Start on lockscreen with shade expanded
            showLockscreenWithQSExpanded()

            // When not in split shade
            sharedNotificationContainerInteractor.setTopPosition(10f)

            assertThat(bounds)
                .isEqualTo(NotificationContainerBounds(top = 10f, bottom = 0f, isAnimated = false))
        }

    @Test
    fun maxNotificationsOnLockscreen() =
        testScope.runTest {
            var notificationCount = 10
            val calculateSpace = { space: Float, useExtraShelfSpace: Boolean -> notificationCount }
            val maxNotifications by collectLastValue(underTest.getMaxNotifications(calculateSpace))

            showLockscreen()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 2f)
            )

            assertThat(maxNotifications).isEqualTo(10)

            // Also updates when directly requested (as it would from NotificationStackScrollLayout)
            notificationCount = 25
            sharedNotificationContainerInteractor.notificationStackChanged()
            assertThat(maxNotifications).isEqualTo(25)
        }

    @Test
    fun maxNotificationsOnLockscreen_DoesNotUpdateWhenUserInteracting() =
        testScope.runTest {
            var notificationCount = 10
            val calculateSpace = { space: Float, useExtraShelfSpace: Boolean -> notificationCount }
            val maxNotifications by collectLastValue(underTest.getMaxNotifications(calculateSpace))

            showLockscreen()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 2f)
            )

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
        testScope.runTest {
            val calculateSpace = { space: Float, useExtraShelfSpace: Boolean -> 10 }
            val maxNotifications by collectLastValue(underTest.getMaxNotifications(calculateSpace))

            // Show lockscreen with shade expanded
            showLockscreenWithShadeExpanded()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 2f)
            )

            // -1 means No Limit
            assertThat(maxNotifications).isEqualTo(-1)
        }

    @Test
    fun translationYUpdatesOnKeyguard() =
        testScope.runTest {
            val translationY by collectLastValue(underTest.translationY)

            configurationRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                -100
            )
            configurationRepository.onAnyConfigurationChange()

            // legacy expansion means the user is swiping up, usually for the bouncer
            shadeRepository.setLegacyShadeExpansion(0.5f)

            showLockscreen()

            // The translation values are negative
            assertThat(translationY).isLessThan(0f)
        }

    @Test
    fun translationYDoesNotUpdateWhenShadeIsExpanded() =
        testScope.runTest {
            val translationY by collectLastValue(underTest.translationY)

            configurationRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                -100
            )
            configurationRepository.onAnyConfigurationChange()

            // legacy expansion means the user is swiping up, usually for the bouncer but also for
            // shade collapsing
            shadeRepository.setLegacyShadeExpansion(0.5f)

            showLockscreenWithShadeExpanded()

            assertThat(translationY).isEqualTo(0f)
        }

    @Test
    fun updateBounds_fromKeyguardRoot() =
        testScope.runTest {
            val bounds by collectLastValue(underTest.bounds)

            val top = 123f
            val bottom = 456f
            keyguardRootViewModel.onNotificationContainerBoundsChanged(top, bottom)
            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = top, bottom = bottom))
        }

    @Test
    fun shadeCollapseFadeIn() =
        testScope.runTest {
            val fadeIn by collectLastValue(underTest.shadeCollpaseFadeIn)

            // Start on lockscreen without the shade
            underTest.setShadeCollapseFadeInComplete(false)
            showLockscreen()
            assertThat(fadeIn).isEqualTo(false)

            // ... then the shade expands
            showLockscreenWithShadeExpanded()
            assertThat(fadeIn).isEqualTo(false)

            // ... it collapses
            showLockscreen()
            assertThat(fadeIn).isEqualTo(true)

            // ... now send animation complete signal
            underTest.setShadeCollapseFadeInComplete(true)
            assertThat(fadeIn).isEqualTo(false)
        }

    private suspend fun TestScope.showLockscreen() {
        shadeRepository.setLockscreenShadeExpansion(0f)
        shadeRepository.setQsExpansion(0f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope,
        )
    }

    private suspend fun TestScope.showLockscreenWithShadeExpanded() {
        shadeRepository.setLockscreenShadeExpansion(1f)
        shadeRepository.setQsExpansion(0f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope,
        )
    }

    private suspend fun TestScope.showLockscreenWithQSExpanded() {
        shadeRepository.setLockscreenShadeExpansion(0f)
        shadeRepository.setQsExpansion(1f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope,
        )
    }
}
