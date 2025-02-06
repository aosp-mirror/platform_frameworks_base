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

package com.android.systemui.statusbar.notification

import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.domain.interactor.pulseExpansionInteractor
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyFloat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class NotificationWakeUpCoordinatorTest(flags: FlagsParameterization) : SysuiTestCase() {

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val dumpManager: DumpManager = mock()
    private val headsUpManager: HeadsUpManager = mock()
    private val statusBarStateController: StatusBarStateController = mock()
    private val bypassController: KeyguardBypassController = mock()
    private val dozeParameters: DozeParameters = mock()
    private val screenOffAnimationController: ScreenOffAnimationController = mock()
    private val logger = NotificationWakeUpCoordinatorLogger(logcatLogBuffer())
    private val stackScrollerController: NotificationStackScrollLayoutController = mock()
    private val wakeUpListener: NotificationWakeUpCoordinator.WakeUpListener = mock()

    private lateinit var notificationWakeUpCoordinator: NotificationWakeUpCoordinator
    private lateinit var statusBarStateCallback: StatusBarStateController.StateListener
    private lateinit var bypassChangeCallback: KeyguardBypassController.OnBypassStateChangedListener

    private var bypassEnabled: Boolean = false
    private var statusBarState: Int = StatusBarState.KEYGUARD

    private fun eased(dozeAmount: Float) =
        notificationWakeUpCoordinator.dozeAmountInterpolator.getInterpolation(dozeAmount)

    private fun setBypassEnabled(enabled: Boolean) {
        bypassEnabled = enabled
        bypassChangeCallback.onBypassStateChanged(enabled)
    }

    private fun setStatusBarState(state: Int) {
        statusBarState = state
        statusBarStateCallback.onStateChanged(state)
    }

    private fun setDozeAmount(dozeAmount: Float) {
        statusBarStateCallback.onDozeAmountChanged(dozeAmount, dozeAmount)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setup() {
        whenever(bypassController.bypassEnabled).then { bypassEnabled }
        whenever(statusBarStateController.state).then { statusBarState }
        notificationWakeUpCoordinator =
            NotificationWakeUpCoordinator(
                kosmos.applicationCoroutineScope,
                dumpManager,
                headsUpManager,
                statusBarStateController,
                bypassController,
                dozeParameters,
                screenOffAnimationController,
                logger,
                kosmos.notificationsKeyguardInteractor,
                kosmos.communalInteractor,
                kosmos.pulseExpansionInteractor,
            )
        statusBarStateCallback = withArgCaptor {
            verify(statusBarStateController).addCallback(capture())
        }
        bypassChangeCallback = withArgCaptor {
            verify(bypassController).registerOnBypassStateChangedListener(capture())
        }
        notificationWakeUpCoordinator.setStackScroller(stackScrollerController)
    }

    @Test
    fun setDozeToOneWillFullyHideNotifications() {
        setDozeAmount(1f)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()
    }

    @Test
    fun setDozeToZeroWillFullyShowNotifications() {
        setDozeAmount(0f)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0f, hideAmount = 0f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
    }

    @Test
    fun setDozeToOneThenZeroWillFullyShowNotifications() {
        setDozeToOneWillFullyHideNotifications()
        clearInvocations(stackScrollerController)
        setDozeToZeroWillFullyShowNotifications()
    }

    @Test
    fun setDozeToHalfWillHalfShowNotifications() {
        setDozeAmount(0.5f)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0.5f, hideAmount = 0.5f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
    }

    @Test
    fun setDozeToZeroWithBypassWillFullyHideNotifications() {
        bypassEnabled = true
        setDozeAmount(0f)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()
    }

    @Test
    fun disablingBypassWillShowNotifications() {
        setDozeToZeroWithBypassWillFullyHideNotifications()
        clearInvocations(stackScrollerController)
        setBypassEnabled(false)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0f, hideAmount = 0f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
    }

    @Test
    @DisableSceneContainer
    fun setDozeToZeroWhenCommunalShowingWillFullyHideNotifications() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(CommunalScenes.Communal)
                )
            kosmos.communalSceneRepository.setTransitionState(transitionState)
            runCurrent()
            setDozeAmount(0f)
            verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
            assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun setDozeToZeroWhenCommunalShowingWillFullyHideNotifications_withSceneContainer() =
        testScope.runTest {
            kosmos.setSceneTransition(Idle(Scenes.Communal))
            setDozeAmount(0f)
            verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
            assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun closingCommunalWillShowNotifications() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(CommunalScenes.Communal)
                )
            kosmos.communalSceneRepository.setTransitionState(transitionState)
            runCurrent()
            setDozeAmount(0f)
            verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
            assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()

            transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Blank)
            runCurrent()
            verifyStackScrollerDozeAndHideAmount(dozeAmount = 0f, hideAmount = 0f)
            assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun closingCommunalWillShowNotifications_withSceneContainer() =
        testScope.runTest {
            kosmos.setSceneTransition(Idle(Scenes.Communal))
            setDozeAmount(0f)
            verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
            assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()

            kosmos.setSceneTransition(Idle(CommunalScenes.Blank))
            verifyStackScrollerDozeAndHideAmount(dozeAmount = 0f, hideAmount = 0f)
            assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
        }

    @Test
    fun switchingToShadeWithBypassEnabledWillShowNotifications() {
        setDozeToZeroWithBypassWillFullyHideNotifications()
        clearInvocations(stackScrollerController)
        setStatusBarState(StatusBarState.SHADE)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0f, hideAmount = 0f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
        assertThat(notificationWakeUpCoordinator.statusBarState).isEqualTo(StatusBarState.SHADE)
    }

    @Test
    fun verifyWakeUpListenerCallbacksWhenDozing() {
        // prime internal state as dozing, then add the listener
        setDozeAmount(1f)
        notificationWakeUpCoordinator.addListener(wakeUpListener)

        setDozeAmount(0.5f)
        verify(wakeUpListener).onFullyHiddenChanged(eq(false))
        verifyNoMoreInteractions(wakeUpListener)
        clearInvocations(wakeUpListener)

        setDozeAmount(0f)
        verifyNoMoreInteractions(wakeUpListener)

        setDozeAmount(0.5f)
        verifyNoMoreInteractions(wakeUpListener)

        setDozeAmount(1f)
        verify(wakeUpListener).onFullyHiddenChanged(eq(true))
        verifyNoMoreInteractions(wakeUpListener)
    }

    private fun verifyStackScrollerDozeAndHideAmount(dozeAmount: Float, hideAmount: Float) {
        // First verify that we did in-fact receive the correct values
        verify(stackScrollerController).setDozeAmount(eased(dozeAmount))
        verify(stackScrollerController).setHideAmount(hideAmount, eased(hideAmount))
        // Now verify that there was just this ONE call to each of these methods
        verify(stackScrollerController).setDozeAmount(anyFloat())
        verify(stackScrollerController).setHideAmount(anyFloat(), anyFloat())
        // clear for next check
        clearInvocations(stackScrollerController)
    }
}
