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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.WindowManagerLockscreenVisibilityManager
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissTransitionInteractor
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class WindowManagerLockscreenVisibilityManagerTest : SysuiTestCase() {
    private lateinit var underTest: WindowManagerLockscreenVisibilityManager
    private lateinit var executor: FakeExecutor

    @Mock private lateinit var activityTaskManagerService: IActivityTaskManager
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardSurfaceBehindAnimator: KeyguardSurfaceBehindParamsApplier
    @Mock
    private lateinit var keyguardDismissTransitionInteractor: KeyguardDismissTransitionInteractor

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
            )
    }

    @Test
    fun testLockscreenVisible_andAodVisible() {
        underTest.setLockscreenShown(true)
        underTest.setAodVisible(true)

        verify(activityTaskManagerService).setLockScreenShown(true, false)
        verify(activityTaskManagerService).setLockScreenShown(true, true)
        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    fun testGoingAway_whenLockscreenVisible_thenSurfaceMadeVisible() {
        underTest.setLockscreenShown(true)
        underTest.setAodVisible(true)

        verify(activityTaskManagerService).setLockScreenShown(true, false)
        verify(activityTaskManagerService).setLockScreenShown(true, true)
        verifyNoMoreInteractions(activityTaskManagerService)

        underTest.setSurfaceBehindVisibility(true)

        verify(activityTaskManagerService).keyguardGoingAway(anyInt())
        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    fun testSurfaceVisible_whenLockscreenNotShowing_doesNotTriggerGoingAway() {
        underTest.setLockscreenShown(false)
        underTest.setAodVisible(false)

        verify(activityTaskManagerService).setLockScreenShown(false, false)
        verifyNoMoreInteractions(activityTaskManagerService)

        underTest.setSurfaceBehindVisibility(true)

        verifyNoMoreInteractions(activityTaskManagerService)
    }

    @Test
    fun testAodVisible_noLockscreenShownCallYet_doesNotShowLockscreenUntilLater() {
        underTest.setAodVisible(false)
        verifyNoMoreInteractions(activityTaskManagerService)

        underTest.setLockscreenShown(true)
        verify(activityTaskManagerService).setLockScreenShown(true, false)
        verifyNoMoreInteractions(activityTaskManagerService)
    }
}
