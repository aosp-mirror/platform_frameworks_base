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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.dump.DumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeViewController.Companion.WAKEUP_ANIMATION_DELAY_MS
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_WAKEUP
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class NotificationWakeUpCoordinatorTest : SysuiTestCase() {

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    private val kosmos = Kosmos()

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

    @Before
    fun setup() {
        whenever(bypassController.bypassEnabled).then { bypassEnabled }
        whenever(statusBarStateController.state).then { statusBarState }
        notificationWakeUpCoordinator =
            NotificationWakeUpCoordinator(
                dumpManager,
                headsUpManager,
                statusBarStateController,
                bypassController,
                dozeParameters,
                screenOffAnimationController,
                logger,
                kosmos.notificationsKeyguardInteractor,
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
    fun switchingToShadeWithBypassEnabledWillShowNotifications() {
        setDozeToZeroWithBypassWillFullyHideNotifications()
        clearInvocations(stackScrollerController)
        setStatusBarState(StatusBarState.SHADE)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0f, hideAmount = 0f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
        assertThat(notificationWakeUpCoordinator.statusBarState).isEqualTo(StatusBarState.SHADE)
    }

    private val delayedDozeDelay = WAKEUP_ANIMATION_DELAY_MS.toLong()
    private val delayedDozeDuration = ANIMATION_DURATION_WAKEUP.toLong()

    @Test
    fun dozeAmountOutputClampsTo1WhenDelayStarts() {
        notificationWakeUpCoordinator.setWakingUp(true, requestDelayedAnimation = true)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()

        // verify further doze amount changes have no effect on output
        setDozeAmount(0.5f)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()
    }

    @Test
    fun verifyDozeAmountOutputTracksDelay() {
        dozeAmountOutputClampsTo1WhenDelayStarts()

        // Animator waiting the delay amount should not yet affect the output
        animatorTestRule.advanceTimeBy(delayedDozeDelay)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()

        // input doze amount change to 0 has no effect
        setDozeAmount(0.0f)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()

        // Advancing the delay to 50% will cause the 50% output
        animatorTestRule.advanceTimeBy(delayedDozeDuration / 2)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0.5f, hideAmount = 0.5f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()

        // Now advance delay to 100% completion; notifications become fully visible
        animatorTestRule.advanceTimeBy(delayedDozeDuration / 2)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0f, hideAmount = 0f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()

        // Now advance delay to 200% completion -- should not invoke anything else
        animatorTestRule.advanceTimeBy(delayedDozeDuration)
        verify(stackScrollerController, never()).setDozeAmount(anyFloat())
        verify(stackScrollerController, never()).setHideAmount(anyFloat(), anyFloat())
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
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

    @Test
    fun verifyWakeUpListenerCallbacksWhenDelayingAnimation() {
        // prime internal state as dozing, then add the listener
        setDozeAmount(1f)
        notificationWakeUpCoordinator.addListener(wakeUpListener)

        // setWakingUp() doesn't do anything yet
        notificationWakeUpCoordinator.setWakingUp(true, requestDelayedAnimation = true)
        verifyNoMoreInteractions(wakeUpListener)

        // verify further doze amount changes have no effect
        setDozeAmount(0.5f)
        verifyNoMoreInteractions(wakeUpListener)

        // advancing to just before the start time should not invoke the listener
        animatorTestRule.advanceTimeBy(delayedDozeDelay - 1)
        verifyNoMoreInteractions(wakeUpListener)

        animatorTestRule.advanceTimeBy(1)
        verify(wakeUpListener).onDelayedDozeAmountAnimationRunning(eq(true))
        verifyNoMoreInteractions(wakeUpListener)
        clearInvocations(wakeUpListener)

        // input doze amount change to 0 has no effect
        setDozeAmount(0.0f)
        verifyNoMoreInteractions(wakeUpListener)

        // Advancing the delay to 50% will cause notifications to no longer be fully hidden
        animatorTestRule.advanceTimeBy(delayedDozeDuration / 2)
        verify(wakeUpListener).onFullyHiddenChanged(eq(false))
        verifyNoMoreInteractions(wakeUpListener)
        clearInvocations(wakeUpListener)

        // Now advance delay to 99.x% completion; notifications become fully visible
        animatorTestRule.advanceTimeBy(delayedDozeDuration / 2 - 1)
        verifyNoMoreInteractions(wakeUpListener)

        // advance to 100%; animation no longer running
        animatorTestRule.advanceTimeBy(1)
        verify(wakeUpListener).onDelayedDozeAmountAnimationRunning(eq(false))
        verifyNoMoreInteractions(wakeUpListener)
        clearInvocations(wakeUpListener)

        // Now advance delay to 200% completion -- should not invoke anything else
        animatorTestRule.advanceTimeBy(delayedDozeDuration)
        verifyNoMoreInteractions(wakeUpListener)
    }

    @Test
    fun verifyDelayedDozeAmountCanBeOverridden() {
        dozeAmountOutputClampsTo1WhenDelayStarts()

        // input doze amount change to 0 has no effect
        setDozeAmount(0.0f)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()

        // Advancing the delay to 50% will cause the 50% output
        animatorTestRule.advanceTimeBy(delayedDozeDelay + delayedDozeDuration / 2)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0.5f, hideAmount = 0.5f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()

        // Enabling bypass and showing keyguard will override back to fully dozing/hidden
        setBypassEnabled(true)
        setStatusBarState(StatusBarState.KEYGUARD)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 1f, hideAmount = 1f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isTrue()
    }

    @Test
    fun verifyRemovingOverrideRestoresOtherwiseCalculatedDozeAmount() {
        verifyDelayedDozeAmountCanBeOverridden()

        // Disabling bypass will return back to the 50% value
        setBypassEnabled(false)
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 0.5f, hideAmount = 0.5f)
        assertThat(notificationWakeUpCoordinator.notificationsFullyHidden).isFalse()
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
