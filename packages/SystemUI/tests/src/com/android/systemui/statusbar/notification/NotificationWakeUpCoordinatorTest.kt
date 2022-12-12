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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class NotificationWakeUpCoordinatorTest : SysuiTestCase() {

    private val dumpManager: DumpManager = mock()
    private val headsUpManager: HeadsUpManager = mock()
    private val statusBarStateController: StatusBarStateController = mock()
    private val bypassController: KeyguardBypassController = mock()
    private val dozeParameters: DozeParameters = mock()
    private val screenOffAnimationController: ScreenOffAnimationController = mock()
    private val logger: NotificationWakeUpCoordinatorLogger = mock()
    private val stackScrollerController: NotificationStackScrollLayoutController = mock()

    private lateinit var notificationWakeUpCoordinator: NotificationWakeUpCoordinator
    private lateinit var statusBarStateCallback: StatusBarStateController.StateListener
    private lateinit var bypassChangeCallback: KeyguardBypassController.OnBypassStateChangedListener

    private var bypassEnabled: Boolean = false
    private var statusBarState: Int = StatusBarState.KEYGUARD
    private var dozeAmount: Float = 0f

    private fun setBypassEnabled(enabled: Boolean) {
        bypassEnabled = enabled
        bypassChangeCallback.onBypassStateChanged(enabled)
    }

    private fun setStatusBarState(state: Int) {
        statusBarState = state
        statusBarStateCallback.onStateChanged(state)
    }

    private fun setDozeAmount(dozeAmount: Float) {
        this.dozeAmount = dozeAmount
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
        verifyStackScrollerDozeAndHideAmount(dozeAmount = 01f, hideAmount = 1f)
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

    private fun verifyStackScrollerDozeAndHideAmount(dozeAmount: Float, hideAmount: Float) {
        // First verify that we did in-fact receive the correct values
        verify(stackScrollerController).setDozeAmount(dozeAmount)
        verify(stackScrollerController).setHideAmount(hideAmount, hideAmount)
        // Now verify that there was just this ONE call to each of these methods
        verify(stackScrollerController).setDozeAmount(anyFloat())
        verify(stackScrollerController).setHideAmount(anyFloat(), anyFloat())
    }
}
