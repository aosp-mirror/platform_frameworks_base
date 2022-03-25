/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.settings.SecureSettings
import com.android.wm.shell.TaskViewFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.Optional

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlActionCoordinatorImplTest : SysuiTestCase() {
    @Mock
    private lateinit var vibratorHelper: VibratorHelper
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var bgExecutor: DelayableExecutor
    @Mock
    private lateinit var uiExecutor: DelayableExecutor
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var broadcastSender: BroadcastSender
    @Mock
    private lateinit var taskViewFactory: Optional<TaskViewFactory>
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var cvh: ControlViewHolder
    @Mock
    private lateinit var metricsLogger: ControlsMetricsLogger
    @Mock
    private lateinit var secureSettings: SecureSettings
    @Mock
    private lateinit var mainHandler: Handler

    companion object {
        fun <T> any(): T = Mockito.any<T>()

        private val ID = "id"
    }

    private lateinit var coordinator: ControlActionCoordinatorImpl
    private lateinit var action: ControlActionCoordinatorImpl.Action

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(secureSettings.getUriFor(Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS))
                .thenReturn(Settings.Secure
                        .getUriFor(Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS))

        coordinator = spy(ControlActionCoordinatorImpl(
                mContext,
                bgExecutor,
                uiExecutor,
                activityStarter,
                broadcastSender,
                keyguardStateController,
                taskViewFactory,
                metricsLogger,
                vibratorHelper,
                secureSettings,
                mainHandler))

        verify(secureSettings).registerContentObserver(any(Uri::class.java),
                anyBoolean(), any(ContentObserver::class.java))

        `when`(cvh.cws.ci.controlId).thenReturn(ID)
        `when`(cvh.cws.control?.isAuthRequired()).thenReturn(true)
        action = spy(coordinator.Action(ID, {}, false))
        doReturn(action).`when`(coordinator).createAction(any(), any(), anyBoolean())
    }

    @Test
    fun testToggleRunsWhenUnlocked() {
        `when`(keyguardStateController.isShowing()).thenReturn(false)

        coordinator.toggle(cvh, "", true)
        verify(coordinator).bouncerOrRun(action, true /*authRequired */)
        verify(action).invoke()
    }

    @Test
    fun testToggleDoesNotRunWhenLockedThenRunsWhenUnlocked() {
        `when`(keyguardStateController.isShowing()).thenReturn(true)
        `when`(keyguardStateController.isUnlocked()).thenReturn(false)

        coordinator.toggle(cvh, "", true)
        verify(coordinator).bouncerOrRun(action, true /*authRequired */)
        verify(activityStarter).dismissKeyguardThenExecute(any(), any(), anyBoolean())
        verify(action, never()).invoke()

        // Simulate a refresh call from a Publisher, which will trigger a call to runPendingAction
        reset(action)
        coordinator.runPendingAction(ID)
        verify(action, never()).invoke()

        `when`(keyguardStateController.isUnlocked()).thenReturn(true)
        reset(action)
        coordinator.runPendingAction(ID)
        verify(action).invoke()
    }

    @Test
    fun testToggleRunsWhenLockedAndAuthNotRequired() {
        `when`(keyguardStateController.isShowing()).thenReturn(true)
        `when`(keyguardStateController.isUnlocked()).thenReturn(false)
        doReturn(false).`when`(coordinator).isAuthRequired(
                any(), anyBoolean())

        coordinator.toggle(cvh, "", true)

        verify(coordinator).bouncerOrRun(action, false /* authRequired */)
        verify(action).invoke()
    }

    @Test
    fun testIsAuthRequired() {
        `when`(cvh.cws.control?.isAuthRequired).thenReturn(true)
        assertThat(coordinator.isAuthRequired(cvh, false)).isTrue()

        `when`(cvh.cws.control?.isAuthRequired).thenReturn(false)
        assertThat(coordinator.isAuthRequired(cvh, false)).isTrue()

        assertThat(coordinator.isAuthRequired(cvh, true)).isFalse()
    }
}
