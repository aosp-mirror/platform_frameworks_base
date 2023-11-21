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
package com.android.systemui.statusbar.notification.stack.domain.interactor

import android.content.res.Configuration
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.Surface
import android.view.Surface.ROTATION_0
import android.view.Surface.ROTATION_90
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.domain.interactor.ConfigurationInteractorImpl
import com.android.systemui.common.ui.data.repository.ConfigurationRepositoryImpl
import com.android.systemui.coroutines.collectValues
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState.SCREEN_ON
import com.android.systemui.power.shared.model.WakefulnessState.STARTING_TO_SLEEP
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.unfold.TestUnfoldTransitionProvider
import com.android.systemui.unfold.data.repository.UnfoldTransitionRepositoryImpl
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractorImpl
import com.android.systemui.util.animation.data.repository.FakeAnimationStatusRepository
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
open class HideNotificationsInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()

    private val animationStatus = FakeAnimationStatusRepository()
    private val configurationController = FakeConfigurationController()
    private val unfoldTransitionProgressProvider = TestUnfoldTransitionProvider()
    private val powerRepository = FakePowerRepository()
    private val powerInteractor =
        PowerInteractor(
            repository = powerRepository,
            falsingCollector = mock(),
            screenOffAnimationController = mock(),
            statusBarStateController = mock()
        )

    private val unfoldTransitionRepository =
        UnfoldTransitionRepositoryImpl(Optional.of(unfoldTransitionProgressProvider))
    private val unfoldTransitionInteractor =
        UnfoldTransitionInteractorImpl(unfoldTransitionRepository)

    private val configurationRepository =
        ConfigurationRepositoryImpl(
            configurationController,
            context,
            testScope.backgroundScope,
            mock()
        )
    private val configurationInteractor = ConfigurationInteractorImpl(configurationRepository)

    private lateinit var configuration: Configuration
    private lateinit var underTest: HideNotificationsInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        configuration = context.resources.configuration

        val testableResources = context.getOrCreateTestableResources()
        testableResources.overrideConfiguration(configuration)

        updateDisplay()

        underTest =
            HideNotificationsInteractor(
                unfoldTransitionInteractor,
                configurationInteractor,
                animationStatus,
                powerInteractor
            )
    }

    @Test
    fun displaySwitch_hidesNotifications() =
        testScope.runTest {
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()

            assertThat(values).containsExactly(true).inOrder()
        }

    @Test
    fun displaySwitch_sizeIsTheSame_noChangesToNotifications() =
        testScope.runTest {
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH)
            runCurrent()

            assertThat(values).isEmpty()
        }

    @Test
    fun displaySwitch_sizeIsTheSameAfterRotation_noChangesToNotifications() =
        testScope.runTest {
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(
                width = INITIAL_DISPLAY_HEIGHT,
                height = INITIAL_DISPLAY_WIDTH,
                rotation = ROTATION_90
            )
            runCurrent()

            assertThat(values).isEmpty()
        }

    @Test
    fun displaySwitch_noAnimations_screenTurnedOn_showsNotificationsBack() =
        testScope.runTest {
            givenAnimationsEnabled(false)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()
            powerRepository.setScreenPowerState(SCREEN_ON)
            runCurrent()

            assertThat(values).containsExactly(true, false).inOrder()
        }

    @Test
    fun displaySwitchUnfold_animationsEnabled_screenTurnedOn_doesNotShowNotifications() =
        testScope.runTest {
            givenAnimationsEnabled(true)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()
            powerRepository.setScreenPowerState(SCREEN_ON)
            runCurrent()

            assertThat(values).containsExactly(true).inOrder()
        }

    @Test
    fun displaySwitchFold_animationsEnabled_screenTurnedOn_showsNotifications() =
        testScope.runTest {
            givenAnimationsEnabled(true)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH / 2)
            runCurrent()
            powerRepository.setScreenPowerState(SCREEN_ON)
            runCurrent()

            assertThat(values).containsExactly(true, false).inOrder()
        }

    @Test
    fun displaySwitch_noAnimations_screenGoesToSleep_showsNotificationsBack() =
        testScope.runTest {
            givenAnimationsEnabled(false)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()
            powerRepository.updateWakefulness(STARTING_TO_SLEEP)
            runCurrent()

            assertThat(values).containsExactly(true, false).inOrder()
        }

    @Test
    fun displaySwitch_animationsEnabled_screenGoesToSleep_showsNotificationsBack() =
        testScope.runTest {
            givenAnimationsEnabled(true)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()
            powerRepository.updateWakefulness(STARTING_TO_SLEEP)
            runCurrent()

            assertThat(values).containsExactly(true, false).inOrder()
        }

    @Test
    fun displaySwitch_animationsEnabled_unfoldAnimationNotFinished_notificationsHidden() =
        testScope.runTest {
            givenAnimationsEnabled(true)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()

            assertThat(values).containsExactly(true).inOrder()
        }

    @Test
    fun displaySwitch_animationsEnabled_unfoldAnimationFinishes_showsNotificationsBack() =
        testScope.runTest {
            givenAnimationsEnabled(true)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()
            unfoldTransitionProgressProvider.onTransitionFinished()
            runCurrent()

            assertThat(values).containsExactly(true, false).inOrder()
        }

    @Test
    fun displaySwitch_noEvents_afterTimeout_showsNotificationsBack() =
        testScope.runTest {
            givenAnimationsEnabled(true)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()
            advanceTimeBy(Duration.ofMillis(10_000).toMillis())

            assertThat(values).containsExactly(true, false).inOrder()
        }

    @Test
    fun displaySwitch_noEvents_beforeTimeout_doesNotShowNotifications() =
        testScope.runTest {
            givenAnimationsEnabled(true)
            val values by collectValues(hideNotificationsFlow)

            runCurrent()
            updateDisplay(width = INITIAL_DISPLAY_WIDTH * 2)
            runCurrent()
            advanceTimeBy(Duration.ofMillis(500).toMillis())

            assertThat(values).containsExactly(true).inOrder()
        }

    private val hideNotificationsFlow: Flow<Boolean>
        get() = underTest.shouldHideNotifications

    private fun updateDisplay(
        width: Int = INITIAL_DISPLAY_WIDTH,
        height: Int = INITIAL_DISPLAY_HEIGHT,
        @Surface.Rotation rotation: Int = ROTATION_0
    ) {
        configuration.windowConfiguration.maxBounds.set(Rect(0, 0, width, height))
        configuration.windowConfiguration.displayRotation = rotation

        configurationController.onConfigurationChanged(configuration)
    }

    private fun givenAnimationsEnabled(enabled: Boolean) {
        animationStatus.onAnimationStatusChanged(enabled)
    }

    private companion object {
        private const val INITIAL_DISPLAY_WIDTH = 100
        private const val INITIAL_DISPLAY_HEIGHT = 200
    }
}
