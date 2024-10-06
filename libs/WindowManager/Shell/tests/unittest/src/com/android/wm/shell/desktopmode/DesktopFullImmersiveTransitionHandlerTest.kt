/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.wm.shell.desktopmode

import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFreeformTask
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [DesktopFullImmersiveTransitionHandler].
 *
 * Usage: atest WMShellUnitTests:DesktopFullImmersiveTransitionHandler
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopFullImmersiveTransitionHandlerTest : ShellTestCase() {

    @Mock private lateinit var mockTransitions: Transitions
    private lateinit var desktopRepository: DesktopRepository
    private val transactionSupplier = { SurfaceControl.Transaction() }

    private lateinit var immersiveHandler: DesktopFullImmersiveTransitionHandler

    @Before
    fun setUp() {
        desktopRepository = DesktopRepository(
            context, ShellInit(TestShellExecutor()), mock(), mock()
        )
        immersiveHandler = DesktopFullImmersiveTransitionHandler(
            transitions = mockTransitions,
            desktopRepository = desktopRepository,
            transactionSupplier = transactionSupplier
        )
    }

    @Test
    fun enterImmersive_transitionReady_updatesRepository() {
        val task = createFreeformTask()
        val wct = WindowContainerTransaction()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(TRANSIT_CHANGE, wct, immersiveHandler))
            .thenReturn(mockBinder)
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = false
        )

        immersiveHandler.enterImmersive(task, wct)
        immersiveHandler.onTransitionReady(mockBinder)

        assertThat(desktopRepository.isTaskInFullImmersiveState(task.taskId)).isTrue()
    }

    @Test
    fun exitImmersive_transitionReady_updatesRepository() {
        val task = createFreeformTask()
        val wct = WindowContainerTransaction()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(TRANSIT_CHANGE, wct, immersiveHandler))
            .thenReturn(mockBinder)
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.exitImmersive(task, wct)
        immersiveHandler.onTransitionReady(mockBinder)

        assertThat(desktopRepository.isTaskInFullImmersiveState(task.taskId)).isFalse()
    }

    @Test
    fun enterImmersive_inProgress_ignores() {
        val task = createFreeformTask()
        val wct = WindowContainerTransaction()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(TRANSIT_CHANGE, wct, immersiveHandler))
            .thenReturn(mockBinder)

        immersiveHandler.enterImmersive(task, wct)
        immersiveHandler.enterImmersive(task, wct)

        verify(mockTransitions, times(1)).startTransition(TRANSIT_CHANGE, wct, immersiveHandler)
    }

    @Test
    fun exitImmersive_inProgress_ignores() {
        val task = createFreeformTask()
        val wct = WindowContainerTransaction()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(TRANSIT_CHANGE, wct, immersiveHandler))
            .thenReturn(mockBinder)

        immersiveHandler.exitImmersive(task, wct)
        immersiveHandler.exitImmersive(task, wct)

        verify(mockTransitions, times(1)).startTransition(TRANSIT_CHANGE, wct, immersiveHandler)
    }
}
