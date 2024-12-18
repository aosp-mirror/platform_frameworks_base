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

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.keyguard.ui.viewmodel.aodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.keyguardRootViewModel
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.setTransition
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.mockLargeScreenHeaderHelper
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.notification.stack.domain.interactor.sharedNotificationContainerInteractor
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
// SharedNotificationContainerViewModel is only bound when FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT is on
@EnableFlags(FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
class SharedNotificationContainerViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    val aodBurnInViewModel = mock(AodBurnInViewModel::class.java)
    lateinit var movementFlow: MutableStateFlow<BurnInModel>

    val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }

    init {
        kosmos.aodBurnInViewModel = aodBurnInViewModel
    }

    val testScope = kosmos.testScope
    val configurationRepository
        get() = kosmos.fakeConfigurationRepository

    val keyguardRepository
        get() = kosmos.fakeKeyguardRepository

    val keyguardInteractor
        get() = kosmos.keyguardInteractor

    val keyguardRootViewModel
        get() = kosmos.keyguardRootViewModel

    val keyguardTransitionRepository
        get() = kosmos.fakeKeyguardTransitionRepository

    val shadeTestUtil
        get() = kosmos.shadeTestUtil

    val sharedNotificationContainerInteractor
        get() = kosmos.sharedNotificationContainerInteractor

    val largeScreenHeaderHelper
        get() = kosmos.mockLargeScreenHeaderHelper

    val communalSceneRepository
        get() = kosmos.communalSceneRepository

    val shadeRepository
        get() = kosmos.fakeShadeRepository

    lateinit var underTest: SharedNotificationContainerViewModel

    @Before
    fun setUp() {
        shadeTestUtil.setSplitShade(false)
        movementFlow = MutableStateFlow(BurnInModel())
        whenever(aodBurnInViewModel.movement).thenReturn(movementFlow)
        underTest = kosmos.sharedNotificationContainerViewModel
    }

    @Test
    fun validateMarginStartInSplitShade() =
        testScope.runTest {
            shadeTestUtil.setSplitShade(true)
            overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginStart).isEqualTo(0)
        }

    @Test
    fun validateMarginStart() =
        testScope.runTest {
            shadeTestUtil.setSplitShade(false)
            overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginStart).isEqualTo(20)
        }

    @Test
    fun validatePaddingTopInSplitShade_usesLargeHeaderHelper() =
        testScope.runTest {
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(5)
            shadeTestUtil.setSplitShade(true)
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            val paddingTop by collectLastValue(underTest.paddingTopDimen)
            configurationRepository.onAnyConfigurationChange()

            // Should directly use the header height (flagged on value)
            assertThat(paddingTop).isEqualTo(5)
        }

    @Test
    fun validatePaddingTopInNonSplitShade_usesLargeScreenHeader() =
        testScope.runTest {
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(10)
            shadeTestUtil.setSplitShade(false)
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            val paddingTop by collectLastValue(underTest.paddingTopDimen)

            configurationRepository.onAnyConfigurationChange()

            assertThat(paddingTop).isEqualTo(10)
        }

    @Test
    fun validatePaddingTopInNonSplitShade_doesNotUseLargeScreenHeader() =
        testScope.runTest {
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(10)
            shadeTestUtil.setSplitShade(false)
            overrideResource(R.bool.config_use_large_screen_shade_header, false)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            val paddingTop by collectLastValue(underTest.paddingTopDimen)

            configurationRepository.onAnyConfigurationChange()
            assertThat(paddingTop).isEqualTo(0)
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
    @DisableSceneContainer
    fun validateMarginTopWithLargeScreenHeader_usesHelper() =
        testScope.runTest {
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
    @EnableSceneContainer
    fun validateMarginTopWithLargeScreenHeader_sceneContainerFlagOn_stillZero() =
        testScope.runTest {
            val headerResourceHeight = 50
            val headerHelperHeight = 100
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight())
                .thenReturn(headerHelperHeight)
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, headerResourceHeight)
            overrideResource(R.dimen.notification_panel_margin_top, 0)

            val dimens by collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()

            assertThat(dimens!!.marginTop).isEqualTo(0)
        }

    @Test
    fun glanceableHubAlpha_lockscreenToHub() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.glanceableHubAlpha)

            // Start on lockscreen
            showLockscreen()
            assertThat(alpha).isEqualTo(1f)

            // Start transitioning to glanceable hub
            val progress = 0.6f
            kosmos.setTransition(
                sceneTransition = Transition(from = Scenes.Lockscreen, to = Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.STARTED,
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        value = 0f,
                    ),
            )

            runCurrent()
            kosmos.setTransition(
                sceneTransition =
                    Transition(
                        from = Scenes.Lockscreen,
                        to = Scenes.Communal,
                        progress = flowOf(progress),
                    ),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.RUNNING,
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        value = progress,
                    ),
            )

            runCurrent()
            assertThat(alpha).isIn(Range.closed(0f, 1f))

            // Finish transition to glanceable hub
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.FINISHED,
                        from = LOCKSCREEN,
                        to = GLANCEABLE_HUB,
                        value = 1f,
                    ),
            )
            assertThat(alpha).isEqualTo(0f)

            // While state is GLANCEABLE_HUB, verify alpha is restored to full if glanceable hub is
            // not fully visible.
            shadeTestUtil.setLockscreenShadeExpansion(0.1f)
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    fun glanceableHubAlpha_dreamToHub() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.glanceableHubAlpha)

            // Start on lockscreen, notifications should be unhidden.
            showLockscreen()
            assertThat(alpha).isEqualTo(1f)

            // Go to dozing
            keyguardTransitionRepository.sendTransitionSteps(
                from = LOCKSCREEN,
                to = DOZING,
                testScope,
            )
            assertThat(alpha).isEqualTo(1f)

            // Start transitioning to glanceable hub
            val progress = 0.6f
            kosmos.setTransition(
                sceneTransition = Transition(from = Scenes.Lockscreen, to = Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.STARTED,
                        from = DOZING,
                        to = GLANCEABLE_HUB,
                        value = 0f,
                    ),
            )
            runCurrent()
            kosmos.setTransition(
                sceneTransition =
                    Transition(
                        from = Scenes.Lockscreen,
                        to = Scenes.Communal,
                        progress = flowOf(progress),
                    ),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.RUNNING,
                        from = DOZING,
                        to = GLANCEABLE_HUB,
                        value = progress,
                    ),
            )
            runCurrent()
            // Keep notifications hidden during the transition from dream to hub
            assertThat(alpha).isEqualTo(0)

            // Finish transition to glanceable hub
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.FINISHED,
                        from = DOZING,
                        to = GLANCEABLE_HUB,
                        value = 1f,
                    ),
            )
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun glanceableHubAlpha_dozingToHub() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.glanceableHubAlpha)

            // Start on lockscreen, notifications should be unhidden.
            showLockscreen()
            assertThat(alpha).isEqualTo(1f)

            // Transition to dream, notifications should be hidden so that transition
            // from dream->hub doesn't cause notification flicker.
            showDream()
            assertThat(alpha).isEqualTo(0f)

            // Start transitioning to glanceable hub
            val progress = 0.6f
            kosmos.setTransition(
                sceneTransition = Transition(from = Scenes.Lockscreen, to = Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.STARTED,
                        from = DREAMING,
                        to = GLANCEABLE_HUB,
                        value = 0f,
                    ),
            )
            runCurrent()
            kosmos.setTransition(
                sceneTransition =
                    Transition(
                        from = Scenes.Lockscreen,
                        to = Scenes.Communal,
                        progress = flowOf(progress),
                    ),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.RUNNING,
                        from = DREAMING,
                        to = GLANCEABLE_HUB,
                        value = progress,
                    ),
            )
            runCurrent()
            // Keep notifications hidden during the transition from dream to hub
            assertThat(alpha).isEqualTo(0)

            // Finish transition to glanceable hub
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        transitionState = TransitionState.FINISHED,
                        from = DREAMING,
                        to = GLANCEABLE_HUB,
                        value = 1f,
                    ),
            )
            assertThat(alpha).isEqualTo(0f)
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

            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Gone),
                stateTransition = TransitionStep(from = LOCKSCREEN, to = GONE),
            )
            assertThat(isOnLockscreen).isFalse()

            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Lockscreen),
                stateTransition = TransitionStep(from = GONE, to = LOCKSCREEN),
            )
            assertThat(isOnLockscreen).isTrue()
            // While progressing from lockscreen, should still be true
            kosmos.setTransition(
                sceneTransition = Transition(from = Scenes.Lockscreen, to = Scenes.Gone),
                stateTransition =
                    TransitionStep(
                        from = LOCKSCREEN,
                        to = GONE,
                        value = 0.8f,
                        transitionState = TransitionState.RUNNING,
                    ),
            )
            assertThat(isOnLockscreen).isTrue()

            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Lockscreen),
                stateTransition = TransitionStep(from = GONE, to = LOCKSCREEN),
            )
            assertThat(isOnLockscreen).isTrue()

            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Bouncer),
                stateTransition = TransitionStep(from = LOCKSCREEN, to = PRIMARY_BOUNCER),
            )
            assertThat(isOnLockscreen).isTrue()
        }

    @Test
    fun isOnLockscreenWithoutShade() =
        testScope.runTest {
            val isOnLockscreenWithoutShade by collectLastValue(underTest.isOnLockscreenWithoutShade)

            // First on AOD
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            shadeTestUtil.setQsExpansion(0f)
            keyguardTransitionRepository.sendTransitionSteps(
                from = LOCKSCREEN,
                to = OCCLUDED,
                testScope,
            )
            assertThat(isOnLockscreenWithoutShade).isFalse()

            // Now move to lockscreen
            showLockscreen()

            // While state is LOCKSCREEN, validate variations of both shade and qs expansion
            shadeTestUtil.setQsExpansion(0f)
            shadeTestUtil.setLockscreenShadeExpansion(0.1f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeTestUtil.setLockscreenShadeExpansion(0.1f)
            shadeTestUtil.setShadeAndQsExpansion(0.1f, .9f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeTestUtil.setLockscreenShadeExpansion(0f)
            shadeTestUtil.setQsExpansion(0.1f)
            assertThat(isOnLockscreenWithoutShade).isFalse()

            shadeTestUtil.setQsExpansion(0f)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
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
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition = TransitionStep(from = LOCKSCREEN, to = GLANCEABLE_HUB),
            )

            assertThat(isOnGlanceableHubWithoutShade).isTrue()

            // While state is GLANCEABLE_HUB, validate variations of both shade and qs expansion
            shadeTestUtil.setQsExpansion(0f)
            shadeTestUtil.setLockscreenShadeExpansion(0.1f)
            assertThat(isOnGlanceableHubWithoutShade).isFalse()

            shadeTestUtil.setLockscreenShadeExpansion(0.1f)
            shadeTestUtil.setShadeAndQsExpansion(0.1f, .9f)
            assertThat(isOnGlanceableHubWithoutShade).isFalse()

            shadeTestUtil.setLockscreenShadeExpansion(0f)
            shadeTestUtil.setQsExpansion(0.1f)
            assertThat(isOnGlanceableHubWithoutShade).isFalse()

            shadeTestUtil.setQsExpansion(0f)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition = TransitionStep(from = LOCKSCREEN, to = GLANCEABLE_HUB),
            )
            assertThat(isOnGlanceableHubWithoutShade).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun boundsOnLockscreenNotInSplitShade() =
        testScope.runTest {
            val bounds by collectLastValue(underTest.bounds)

            // When not in split shade
            shadeTestUtil.setSplitShade(false)
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
    @DisableSceneContainer
    fun boundsDoNotChangeWhileLockscreenToAodTransitionIsActive() =
        testScope.runTest {
            val bounds by collectLastValue(underTest.bounds)

            // Start on lockscreen
            showLockscreen()

            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 1f)
            )
            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = 1f, bottom = 1f))

            // Begin transition to AOD
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(LOCKSCREEN, AOD, 0f, TransitionState.STARTED)
            )
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(LOCKSCREEN, AOD, 0.5f, TransitionState.RUNNING)
            )

            // Attempt to update bounds
            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 5f, bottom = 5f)
            )
            // Bounds should not have moved
            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = 1f, bottom = 1f))

            // Transition is over, now move
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(LOCKSCREEN, AOD, 1f, TransitionState.FINISHED)
            )
            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = 5f, bottom = 5f))
        }

    @Test
    @DisableSceneContainer
    fun boundsOnLockscreenInSplitShade_usesLargeHeaderHelper() =
        testScope.runTest {
            val bounds by collectLastValue(underTest.bounds)

            // When in split shade
            whenever(largeScreenHeaderHelper.getLargeScreenHeaderHeight()).thenReturn(5)
            shadeTestUtil.setSplitShade(true)
            overrideResource(R.bool.config_use_large_screen_shade_header, true)
            overrideResource(R.dimen.large_screen_shade_header_height, 10)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 50)

            configurationRepository.onAnyConfigurationChange()
            runCurrent()

            // Start on lockscreen
            showLockscreen()

            keyguardInteractor.setNotificationContainerBounds(
                NotificationContainerBounds(top = 1f, bottom = 52f)
            )
            runCurrent()

            // Top should be equal to bounds (1) - padding adjustment (5)
            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = -4f, bottom = 2f))
        }

    @Test
    @DisableSceneContainer
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
    @DisableSceneContainer
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
            advanceTimeBy(50L)
            showLockscreen()

            shadeTestUtil.setSplitShade(false)
            configurationRepository.onAnyConfigurationChange()

            assertThat(maxNotifications).isEqualTo(10)

            // Also updates when directly requested (as it would from NotificationStackScrollLayout)
            notificationCount = 25
            sharedNotificationContainerInteractor.notificationStackChanged()
            advanceTimeBy(50L)
            assertThat(maxNotifications).isEqualTo(25)

            // Also ensure another collection starts with the same value. As an example, folding
            // then unfolding will restart the coroutine and it must get the last value immediately.
            val newMaxNotifications by
                collectLastValue(underTest.getMaxNotifications(calculateSpace))
            advanceTimeBy(50L)
            assertThat(newMaxNotifications).isEqualTo(25)
        }

    @Test
    fun maxNotificationsOnLockscreen_DoesNotUpdateWhenUserInteracting() =
        testScope.runTest {
            var notificationCount = 10
            val calculateSpace = { space: Float, useExtraShelfSpace: Boolean -> notificationCount }
            val maxNotifications by collectLastValue(underTest.getMaxNotifications(calculateSpace))
            advanceTimeBy(50L)
            showLockscreen()

            shadeTestUtil.setSplitShade(false)
            configurationRepository.onAnyConfigurationChange()

            assertThat(maxNotifications).isEqualTo(10)

            // Shade expanding... still 10
            shadeTestUtil.setLockscreenShadeExpansion(0.5f)
            assertThat(maxNotifications).isEqualTo(10)

            notificationCount = 25

            // When shade is expanding by user interaction
            shadeTestUtil.setLockscreenShadeTracking(true)

            // Should still be 10, since the user is interacting
            assertThat(maxNotifications).isEqualTo(10)

            shadeTestUtil.setLockscreenShadeTracking(false)
            shadeTestUtil.setLockscreenShadeExpansion(0f)

            // Stopped tracking, show 25
            assertThat(maxNotifications).isEqualTo(25)
        }

    @Test
    fun maxNotificationsOnShade() =
        testScope.runTest {
            val calculateSpace = { space: Float, useExtraShelfSpace: Boolean -> 10 }
            val maxNotifications by collectLastValue(underTest.getMaxNotifications(calculateSpace))
            advanceTimeBy(50L)

            // Show lockscreen with shade expanded
            showLockscreenWithShadeExpanded()

            shadeTestUtil.setSplitShade(false)
            configurationRepository.onAnyConfigurationChange()

            // -1 means No Limit
            assertThat(maxNotifications).isEqualTo(-1)
        }

    @Test
    @DisableSceneContainer
    fun translationYUpdatesOnKeyguardForBurnIn() =
        testScope.runTest {
            val translationY by collectLastValue(underTest.translationY)

            showLockscreen()
            assertThat(translationY).isEqualTo(0)

            movementFlow.value = BurnInModel(translationY = 150)
            assertThat(translationY).isEqualTo(150f)
        }

    @Test
    @DisableSceneContainer
    fun translationYUpdatesOnKeyguard() =
        testScope.runTest {
            val translationY by collectLastValue(underTest.translationY)

            configurationRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                -100,
            )
            configurationRepository.onAnyConfigurationChange()

            // legacy expansion means the user is swiping up, usually for the bouncer
            shadeTestUtil.setShadeExpansion(0.5f)

            showLockscreen()

            // The translation values are negative
            assertThat(translationY).isLessThan(0f)
        }

    @Test
    @DisableSceneContainer
    fun translationYDoesNotUpdateWhenShadeIsExpanded() =
        testScope.runTest {
            val translationY by collectLastValue(underTest.translationY)

            configurationRepository.setDimensionPixelSize(
                R.dimen.keyguard_translate_distance_on_swipe_up,
                -100,
            )
            configurationRepository.onAnyConfigurationChange()

            // legacy expansion means the user is swiping up, usually for the bouncer but also for
            // shade collapsing
            shadeTestUtil.setShadeExpansion(0.5f)

            showLockscreenWithShadeExpanded()

            assertThat(translationY).isEqualTo(0f)
        }

    @Test
    @DisableSceneContainer
    fun updateBounds_fromGone_withoutTransitions() =
        testScope.runTest {
            // Start step is already at 1.0
            val runningStep = TransitionStep(GONE, AOD, 1.0f, TransitionState.RUNNING)
            val finishStep = TransitionStep(GONE, AOD, 1.0f, TransitionState.FINISHED)

            val bounds by collectLastValue(underTest.bounds)
            val top = 123f
            val bottom = 456f

            keyguardTransitionRepository.sendTransitionStep(runningStep)
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(finishStep)
            runCurrent()
            keyguardRootViewModel.onNotificationContainerBoundsChanged(top, bottom)
            runCurrent()

            assertThat(bounds).isEqualTo(NotificationContainerBounds(top = top, bottom = bottom))
        }

    @Test
    fun alphaOnFullQsExpansion() =
        testScope.runTest {
            val viewState = ViewStateAccessor()
            val alpha by
                collectLastValue(underTest.keyguardAlpha(viewState, testScope.backgroundScope))

            showLockscreenWithQSExpanded()

            // Alpha fades out as QS expands
            shadeTestUtil.setQsExpansion(0.5f)
            assertThat(alpha).isWithin(0.01f).of(0.5f)
            shadeTestUtil.setQsExpansion(0.9f)
            assertThat(alpha).isWithin(0.01f).of(0.1f)

            // Ensure that alpha is set back to 1f when QS is fully expanded
            shadeTestUtil.setQsExpansion(1f)
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    @BrokenWithSceneContainer(330311871)
    fun alphaWhenGoneIsSetToOne() =
        testScope.runTest {
            val viewState = ViewStateAccessor()
            val alpha by
                collectLastValue(underTest.keyguardAlpha(viewState, testScope.backgroundScope))

            showLockscreen()
            assertThat(alpha).isEqualTo(1f)

            // GONE transition gets to 90% complete
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = LOCKSCREEN,
                    to = GONE,
                    transitionState = TransitionState.STARTED,
                    value = 0f,
                )
            )
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = LOCKSCREEN,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.9f,
                )
            )
            runCurrent()
            // Change in state should not immediately set value to 1f. Should wait for
            // transition to complete
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)

            // Transition is active, and NSSL should be nearly faded out
            assertThat(alpha).isLessThan(0.5f)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = LOCKSCREEN,
                    to = GONE,
                    transitionState = TransitionState.FINISHED,
                    value = 1f,
                )
            )
            runCurrent()
            // Should reset to 1f
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    fun shadeCollapseFadeIn() =
        testScope.runTest {
            val fadeIn by collectValues(underTest.shadeCollapseFadeIn)

            // Start on lockscreen without the shade
            showLockscreen()
            assertThat(fadeIn[0]).isEqualTo(false)

            // ... then the shade expands
            showLockscreenWithShadeExpanded()
            assertThat(fadeIn[0]).isEqualTo(false)

            // ... it collapses
            showLockscreen()
            assertThat(fadeIn[1]).isEqualTo(true)

            // ... and ensure the value goes back to false
            assertThat(fadeIn[2]).isEqualTo(false)
        }

    @Test
    fun shadeCollapseFadeIn_doesNotRunIfTransitioningToAod() =
        testScope.runTest {
            val fadeIn by collectValues(underTest.shadeCollapseFadeIn)

            // Start on lockscreen without the shade
            showLockscreen()
            assertThat(fadeIn[0]).isEqualTo(false)

            // ... then the shade expands
            showLockscreenWithShadeExpanded()
            assertThat(fadeIn[0]).isEqualTo(false)

            // ... then user hits power to go to AOD
            keyguardTransitionRepository.sendTransitionSteps(from = LOCKSCREEN, to = AOD, testScope)
            // ... followed by a shade collapse
            showLockscreen()
            // ... does not trigger a fade in
            assertThat(fadeIn[0]).isEqualTo(false)
        }

    @Test
    @BrokenWithSceneContainer(330311871)
    fun alpha_isZero_fromPrimaryBouncerToGoneWhileCommunalSceneVisible() =
        testScope.runTest {
            val viewState = ViewStateAccessor()
            val alpha by
                collectLastValue(underTest.keyguardAlpha(viewState, testScope.backgroundScope))

            showPrimaryBouncer()
            showCommunalScene()

            // PRIMARY_BOUNCER->GONE transition is started
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.STARTED,
                    value = 0f,
                )
            )
            runCurrent()

            // PRIMARY_BOUNCER->GONE transition running
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.1f,
                )
            )
            runCurrent()
            assertThat(alpha).isEqualTo(0f)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.9f,
                )
            )
            runCurrent()
            assertThat(alpha).isEqualTo(0f)

            hideCommunalScene()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.FINISHED,
                    value = 1f,
                )
            )
            runCurrent()
            // Resets to 1f after communal scene is hidden
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    @BrokenWithSceneContainer(330311871)
    fun alpha_fromPrimaryBouncerToGoneWhenCommunalSceneNotVisible() =
        testScope.runTest {
            val viewState = ViewStateAccessor()
            val alpha by
                collectLastValue(underTest.keyguardAlpha(viewState, testScope.backgroundScope))

            showPrimaryBouncer()
            hideCommunalScene()

            // PRIMARY_BOUNCER->GONE transition is started
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            // PRIMARY_BOUNCER->GONE transition running
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.1f,
                )
            )
            runCurrent()
            assertThat(alpha).isIn(Range.closedOpen(0f, 1f))

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.9f,
                )
            )
            runCurrent()
            assertThat(alpha).isIn(Range.closedOpen(0f, 1f))

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = PRIMARY_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.FINISHED,
                    value = 1f,
                )
            )
            runCurrent()
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    @BrokenWithSceneContainer(330311871)
    fun alpha_isZero_fromAlternateBouncerToGoneWhileCommunalSceneVisible() =
        testScope.runTest {
            val viewState = ViewStateAccessor()
            val alpha by
                collectLastValue(underTest.keyguardAlpha(viewState, testScope.backgroundScope))

            showAlternateBouncer()
            showCommunalScene()

            // ALTERNATE_BOUNCER->GONE transition is started
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.STARTED,
                    value = 0f,
                )
            )
            runCurrent()

            // ALTERNATE_BOUNCER->GONE transition running
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.1f,
                )
            )
            runCurrent()
            assertThat(alpha).isEqualTo(0f)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.9f,
                )
            )
            runCurrent()
            assertThat(alpha).isEqualTo(0f)

            hideCommunalScene()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.FINISHED,
                    value = 1f,
                )
            )
            runCurrent()
            // Resets to 1f after communal scene is hidden
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    @BrokenWithSceneContainer(330311871)
    fun alpha_fromAlternateBouncerToGoneWhenCommunalSceneNotVisible() =
        testScope.runTest {
            val viewState = ViewStateAccessor()
            val alpha by
                collectLastValue(underTest.keyguardAlpha(viewState, testScope.backgroundScope))

            showAlternateBouncer()
            hideCommunalScene()

            // ALTERNATE_BOUNCER->GONE transition is started
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            // ALTERNATE_BOUNCER->GONE transition running
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.1f,
                )
            )
            runCurrent()
            assertThat(alpha).isIn(Range.closedOpen(0f, 1f))

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.RUNNING,
                    value = 0.9f,
                )
            )
            runCurrent()
            assertThat(alpha).isIn(Range.closedOpen(0f, 1f))

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = ALTERNATE_BOUNCER,
                    to = GONE,
                    transitionState = TransitionState.FINISHED,
                    value = 1f,
                )
            )
            runCurrent()
            assertThat(alpha).isEqualTo(1f)
        }

    private suspend fun TestScope.showLockscreen() {
        shadeTestUtil.setQsExpansion(0f)
        shadeTestUtil.setLockscreenShadeExpansion(0f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(from = AOD, to = LOCKSCREEN, testScope)
    }

    private suspend fun TestScope.showDream() {
        shadeTestUtil.setQsExpansion(0f)
        shadeTestUtil.setLockscreenShadeExpansion(0f)
        runCurrent()
        keyguardRepository.setDreaming(true)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(
            from = LOCKSCREEN,
            to = DREAMING,
            testScope,
        )
    }

    private suspend fun TestScope.showLockscreenWithShadeExpanded() {
        shadeTestUtil.setQsExpansion(0f)
        shadeTestUtil.setLockscreenShadeExpansion(1f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(from = AOD, to = LOCKSCREEN, testScope)
    }

    private suspend fun TestScope.showLockscreenWithQSExpanded() {
        shadeTestUtil.setLockscreenShadeExpansion(0f)
        shadeTestUtil.setQsExpansion(1f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(from = AOD, to = LOCKSCREEN, testScope)
    }

    private suspend fun TestScope.showPrimaryBouncer() {
        shadeTestUtil.setQsExpansion(0f)
        shadeTestUtil.setLockscreenShadeExpansion(0f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        runCurrent()
        kosmos.keyguardBouncerRepository.setPrimaryShow(true)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(
            from = GLANCEABLE_HUB,
            to = PRIMARY_BOUNCER,
            testScope,
        )
    }

    private suspend fun TestScope.showAlternateBouncer() {
        shadeTestUtil.setQsExpansion(0f)
        shadeTestUtil.setLockscreenShadeExpansion(0f)
        runCurrent()
        keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
        runCurrent()
        kosmos.keyguardBouncerRepository.setPrimaryShow(false)
        runCurrent()
        keyguardTransitionRepository.sendTransitionSteps(
            from = GLANCEABLE_HUB,
            to = ALTERNATE_BOUNCER,
            testScope,
        )
    }

    private fun TestScope.showCommunalScene() {
        val transitionState =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(CommunalScenes.Communal)
            )
        communalSceneRepository.setTransitionState(transitionState)
        runCurrent()
    }

    private fun TestScope.hideCommunalScene() {
        val transitionState =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(CommunalScenes.Blank)
            )
        communalSceneRepository.setTransitionState(transitionState)
        runCurrent()
    }
}
