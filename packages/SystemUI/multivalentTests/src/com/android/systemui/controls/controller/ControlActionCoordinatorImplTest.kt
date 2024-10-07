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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import android.view.HapticFeedbackConstants
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.settings.ControlsSettingsDialogManager
import com.android.systemui.controls.settings.FakeControlsSettingsRepository
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.wm.shell.taskview.TaskViewFactory
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.util.Optional

@SmallTest
@RunWith(AndroidJUnit4::class)
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
    private lateinit var controlsSettingsDialogManager: ControlsSettingsDialogManager

    companion object {
        fun <T> any(): T = Mockito.any<T>()

        private val ID = "id"
    }

    private lateinit var coordinator: ControlActionCoordinatorImpl
    private lateinit var action: ControlActionCoordinatorImpl.Action
    private lateinit var controlsSettingsRepository: FakeControlsSettingsRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controlsSettingsRepository = FakeControlsSettingsRepository()
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(true)
        controlsSettingsRepository.setCanShowControlsInLockscreen(true)

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
                controlsSettingsRepository,
        ))
        coordinator.activityContext = mContext

        `when`(cvh.cws.ci.controlId).thenReturn(ID)
        `when`(cvh.cws.control?.isAuthRequired()).thenReturn(true)

        action = spy(coordinator.Action(ID, {}, false, true))
        doReturn(action).`when`(coordinator).createAction(any(), any(), anyBoolean(), anyBoolean())
    }

    @Test
    fun testToggleRunsWhenUnlocked() {
        `when`(keyguardStateController.isShowing()).thenReturn(false)

        coordinator.toggle(cvh, "", true)
        verify(coordinator).bouncerOrRun(action)
        verify(action).invoke()
    }

    @Test
    fun testToggleDoesNotRunWhenLockedThenRunsWhenUnlocked() {
        `when`(keyguardStateController.isShowing()).thenReturn(true)
        `when`(keyguardStateController.isUnlocked()).thenReturn(false)

        coordinator.toggle(cvh, "", true)
        verify(coordinator).bouncerOrRun(action)
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
        action = spy(coordinator.Action(ID, {}, false, false))
        doReturn(action).`when`(coordinator).createAction(any(), any(), anyBoolean(), anyBoolean())

        `when`(keyguardStateController.isShowing()).thenReturn(true)

        coordinator.toggle(cvh, "", true)

        verify(coordinator).bouncerOrRun(action)
        verify(action).invoke()
    }

    @Test
    fun testToggleWhenLockedDoesNotTriggerDialog_featureFlagEnabled() {
        action = spy(coordinator.Action(ID, {}, false, false))
        doReturn(action).`when`(coordinator).createAction(any(), any(), anyBoolean(), anyBoolean())

        `when`(keyguardStateController.isShowing()).thenReturn(true)
        `when`(keyguardStateController.isUnlocked()).thenReturn(false)
        doNothing().`when`(controlsSettingsDialogManager).maybeShowDialog(any(), any())

        coordinator.toggle(cvh, "", true)

        verify(coordinator).bouncerOrRun(action)
        verify(controlsSettingsDialogManager, never()).maybeShowDialog(any(), any())
    }

    @Test
    fun testToggleDoesNotRunsWhenLockedAndAuthRequired() {
        action = spy(coordinator.Action(ID, {}, false, true))
        doReturn(action).`when`(coordinator).createAction(any(), any(), anyBoolean(), anyBoolean())

        `when`(keyguardStateController.isShowing()).thenReturn(true)
        `when`(keyguardStateController.isUnlocked()).thenReturn(false)

        coordinator.toggle(cvh, "", true)

        verify(coordinator).bouncerOrRun(action)
        verify(action, never()).invoke()
    }

    @Test
    fun testNullControl() {
        `when`(cvh.cws.control).thenReturn(null)

        `when`(keyguardStateController.isShowing()).thenReturn(true)

        coordinator.toggle(cvh, "", true)

        verify(coordinator).bouncerOrRun(action)
        verify(action, never()).invoke()
    }

    @Test
    fun drag_isEdge_performsSegmentTickHaptics() {
        coordinator.drag(cvh, true)

        verify(vibratorHelper).performHapticFeedback(
            any(),
            eq(HapticFeedbackConstants.SEGMENT_TICK)
        )
    }

    @Test
    fun drag_isNotEdge_performsFrequentTickHaptics() {
        coordinator.drag(cvh, false)

        verify(vibratorHelper).performHapticFeedback(
            any(),
            eq(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        )
    }
}
