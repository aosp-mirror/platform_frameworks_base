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

package com.android.systemui.keyguard.ui.binder

import android.app.IActivityTaskManager
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.WindowManagerLockscreenVisibilityManager
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissTransitionInteractor
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.android.window.flags.Flags
import com.android.wm.shell.keyguard.KeyguardTransitions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class WindowManagerLockscreenVisibilityManagerTest : SysuiTestCase() {

    @get:Rule val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    private lateinit var underTest: WindowManagerLockscreenVisibilityManager
    private lateinit var executor: FakeExecutor

    @Mock private lateinit var activityTaskManagerService: IActivityTaskManager
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardSurfaceBehindAnimator: KeyguardSurfaceBehindParamsApplier
    @Mock
    private lateinit var keyguardDismissTransitionInteractor: KeyguardDismissTransitionInteractor
    @Mock private lateinit var keyguardTransitions: KeyguardTransitions

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())

        underTest =
            WindowManagerLockscreenVisibilityManager(
                executor = executor,
                activityTaskManagerService = activityTaskManagerService,
                keyguardStateController = keyguardStateController,
                keyguardSurfaceBehindAnimator = keyguardSurfaceBehindAnimator,
                keyguardDismissTransitionInteractor = keyguardDismissTransitionInteractor,
                keyguardTransitions = keyguardTransitions,
            )
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testLockscreenVisible_andAodVisible_without_keyguard_shell_transitions() {
        underTest.setLockscreenShown(true)
        verify(activityTaskManagerService).setLockScreenShown(true, false)
        underTest.setAodVisible(true)
        verify(activityTaskManagerService).setLockScreenShown(true, true)

        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testLockscreenVisible_andAodVisible_with_keyguard_shell_transitions() {
        underTest.setLockscreenShown(true)
        verify(keyguardTransitions).startKeyguardTransition(true, false)
        underTest.setAodVisible(true)
        verify(keyguardTransitions).startKeyguardTransition(true, true)

        verifyNoMoreInteractions(keyguardTransitions)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testGoingAway_whenLockscreenVisible_thenSurfaceMadeVisible_without_keyguard_shell_transitions() {
        underTest.setLockscreenShown(true)
        verify(activityTaskManagerService).setLockScreenShown(true, false)
        underTest.setAodVisible(true)
        verify(activityTaskManagerService).setLockScreenShown(true, true)

        verifyNoMoreInteractions(activityTaskManagerService)

        underTest.setSurfaceBehindVisibility(true)
        verify(activityTaskManagerService).keyguardGoingAway(anyInt())

        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testGoingAway_whenLockscreenVisible_thenSurfaceMadeVisible_with_keyguard_shell_transitions() {
        underTest.setLockscreenShown(true)
        verify(keyguardTransitions).startKeyguardTransition(true, false)
        underTest.setAodVisible(true)
        verify(keyguardTransitions).startKeyguardTransition(true, true)

        verifyNoMoreInteractions(keyguardTransitions)

        underTest.setSurfaceBehindVisibility(true)
        verify(keyguardTransitions).startKeyguardTransition(false, false)

        verifyNoMoreInteractions(keyguardTransitions)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testSurfaceVisible_whenLockscreenNotShowing_doesNotTriggerGoingAway_without_keyguard_shell_transitions() {
        underTest.setLockscreenShown(false)
        underTest.setAodVisible(false)

        verify(activityTaskManagerService).setLockScreenShown(false, false)
        verifyNoMoreInteractions(activityTaskManagerService)

        underTest.setSurfaceBehindVisibility(true)

        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testSurfaceVisible_whenLockscreenNotShowing_doesNotTriggerGoingAway_with_keyguard_shell_transitions() {
        underTest.setLockscreenShown(false)
        underTest.setAodVisible(false)

        verify(keyguardTransitions).startKeyguardTransition(false, false)
        verifyNoMoreInteractions(keyguardTransitions)

        underTest.setSurfaceBehindVisibility(true)

        verifyNoMoreInteractions(keyguardTransitions)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testAodVisible_noLockscreenShownCallYet_doesNotShowLockscreenUntilLater_without_keyguard_shell_transitions() {
        underTest.setAodVisible(false)
        verifyNoMoreInteractions(activityTaskManagerService)

        underTest.setLockscreenShown(true)
        verify(activityTaskManagerService).setLockScreenShown(true, false)
        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun testAodVisible_noLockscreenShownCallYet_doesNotShowLockscreenUntilLater_with_keyguard_shell_transitions() {
        underTest.setAodVisible(false)
        verifyNoMoreInteractions(keyguardTransitions)

        underTest.setLockscreenShown(true)
        verify(keyguardTransitions).startKeyguardTransition(true, false)
        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun setSurfaceBehindVisibility_goesAwayFirst_andIgnoresSecondCall_without_keyguard_shell_transitions() {
        underTest.setLockscreenShown(true)
        underTest.setSurfaceBehindVisibility(true)
        verify(activityTaskManagerService).keyguardGoingAway(0)

        underTest.setSurfaceBehindVisibility(true)
        verifyNoMoreInteractions(keyguardTransitions)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun setSurfaceBehindVisibility_goesAwayFirst_andIgnoresSecondCall_with_keyguard_shell_transitions() {
        underTest.setLockscreenShown(true)
        underTest.setSurfaceBehindVisibility(true)
        verify(keyguardTransitions).startKeyguardTransition(false, false)

        underTest.setSurfaceBehindVisibility(true)
        verifyNoMoreInteractions(keyguardTransitions)
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun setSurfaceBehindVisibility_falseSetsLockscreenVisibility_without_keyguard_shell_transitions() {
        underTest.setSurfaceBehindVisibility(false)
        verify(activityTaskManagerService).setLockScreenShown(eq(true), any())
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENSURE_KEYGUARD_DOES_TRANSITION_STARTING)
    fun setSurfaceBehindVisibility_falseSetsLockscreenVisibility_with_keyguard_shell_transitions() {
        underTest.setSurfaceBehindVisibility(false)
        verify(keyguardTransitions).startKeyguardTransition(eq(true), any())
    }
}
