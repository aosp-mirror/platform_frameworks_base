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

import android.app.WindowConfiguration.WINDOW_CONFIG_BOUNDS
import android.graphics.Rect
import android.os.Binder
import android.os.IBinder
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.Surface
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TransitionFlags
import android.view.WindowManager.TransitionType
import android.window.TransitionInfo
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFreeformTask
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [DesktopFullImmersiveTransitionHandler].
 *
 * Usage: atest WMShellUnitTests:DesktopFullImmersiveTransitionHandlerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopFullImmersiveTransitionHandlerTest : ShellTestCase() {

    @JvmField @Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var mockTransitions: Transitions
    private lateinit var desktopRepository: DesktopRepository
    @Mock private lateinit var mockDisplayController: DisplayController
    @Mock private lateinit var mockShellTaskOrganizer: ShellTaskOrganizer
    @Mock private lateinit var mockDisplayLayout: DisplayLayout
    private val transactionSupplier = { SurfaceControl.Transaction() }

    private lateinit var immersiveHandler: DesktopFullImmersiveTransitionHandler

    @Before
    fun setUp() {
        desktopRepository = DesktopRepository(
            context, ShellInit(TestShellExecutor()), mock(), mock()
        )
        whenever(mockDisplayController.getDisplayLayout(DEFAULT_DISPLAY))
            .thenReturn(mockDisplayLayout)
        whenever(mockDisplayLayout.getStableBounds(any())).thenAnswer { invocation ->
            (invocation.getArgument(0) as Rect).set(STABLE_BOUNDS)
        }
        immersiveHandler = DesktopFullImmersiveTransitionHandler(
            transitions = mockTransitions,
            desktopRepository = desktopRepository,
            displayController = mockDisplayController,
            shellTaskOrganizer = mockShellTaskOrganizer,
            transactionSupplier = transactionSupplier,
        )
    }

    @Test
    fun enterImmersive_transitionReady_updatesRepository() {
        val task = createFreeformTask()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler)))
            .thenReturn(mockBinder)
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = false
        )

        immersiveHandler.moveTaskToImmersive(task)
        immersiveHandler.onTransitionReady(
            transition = mockBinder,
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply {
                        taskInfo = task
                    }
                )
            )
        )

        assertThat(desktopRepository.isTaskInFullImmersiveState(task.taskId)).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTORE_TO_PREVIOUS_SIZE_FROM_DESKTOP_IMMERSIVE)
    fun enterImmersive_savesPreImmersiveBounds() {
        val task = createFreeformTask()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler)))
            .thenReturn(mockBinder)
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = false
        )
        assertThat(desktopRepository.removeBoundsBeforeFullImmersive(task.taskId)).isNull()

        immersiveHandler.moveTaskToImmersive(task)
        immersiveHandler.onTransitionReady(
            transition = mockBinder,
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply {
                        taskInfo = task
                    }
                )
            )
        )

        assertThat(desktopRepository.removeBoundsBeforeFullImmersive(task.taskId)).isNotNull()
    }

    @Test
    fun exitImmersive_transitionReady_updatesRepository() {
        val task = createFreeformTask()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler)))
            .thenReturn(mockBinder)
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.moveTaskToNonImmersive(task)
        immersiveHandler.onTransitionReady(
            transition = mockBinder,
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply {
                        taskInfo = task
                    }
                )
            )
        )

        assertThat(desktopRepository.isTaskInFullImmersiveState(task.taskId)).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTORE_TO_PREVIOUS_SIZE_FROM_DESKTOP_IMMERSIVE)
    fun exitImmersive_onTransitionReady_removesBoundsBeforeImmersive() {
        val task = createFreeformTask()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler)))
            .thenReturn(mockBinder)
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = true
        )
        desktopRepository.saveBoundsBeforeFullImmersive(task.taskId, Rect(100, 100, 600, 600))

        immersiveHandler.moveTaskToNonImmersive(task)
        immersiveHandler.onTransitionReady(
            transition = mockBinder,
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply {
                        taskInfo = task
                    }
                )
            )
        )

        assertThat(desktopRepository.removeBoundsBeforeMaximize(task.taskId)).isNull()
    }

    @Test
    fun onTransitionReady_displayRotation_exitsImmersive() {
        val task = createFreeformTask()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.onTransitionReady(
            transition = mock(IBinder::class.java),
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply {
                        taskInfo = task
                        setRotation(/* start= */ Surface.ROTATION_0, /* end= */ Surface.ROTATION_90)
                    }
                )
            )
        )

        assertThat(desktopRepository.isTaskInFullImmersiveState(task.taskId)).isFalse()
    }

    @Test
    fun enterImmersive_inProgress_ignores() {
        val task = createFreeformTask()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler)))
            .thenReturn(mockBinder)

        immersiveHandler.moveTaskToImmersive(task)
        immersiveHandler.moveTaskToImmersive(task)

        verify(mockTransitions, times(1))
            .startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler))
    }

    @Test
    fun exitImmersive_inProgress_ignores() {
        val task = createFreeformTask()
        val mockBinder = mock(IBinder::class.java)
        whenever(mockTransitions.startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler)))
            .thenReturn(mockBinder)

        immersiveHandler.moveTaskToNonImmersive(task)
        immersiveHandler.moveTaskToNonImmersive(task)

        verify(mockTransitions, times(1))
            .startTransition(eq(TRANSIT_CHANGE), any(), eq(immersiveHandler))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_inImmersive_addsPendingExit() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.exitImmersiveIfApplicable(transition, wct, DEFAULT_DISPLAY)

        assertThat(immersiveHandler.pendingExternalExitTransitions.any { exit ->
            exit.transition == transition && exit.displayId == DEFAULT_DISPLAY
                    && exit.taskId == task.taskId
        }).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_notInImmersive_doesNotAddPendingExit() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = false
        )

        immersiveHandler.exitImmersiveIfApplicable(transition, wct, DEFAULT_DISPLAY)

        assertThat(immersiveHandler.pendingExternalExitTransitions.any { exit ->
            exit.transition == transition && exit.displayId == DEFAULT_DISPLAY
                    && exit.taskId == task.taskId
        }).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_byDisplay_inImmersive_changesTaskBounds() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.exitImmersiveIfApplicable(transition, wct, DEFAULT_DISPLAY)

        assertThat(wct.hasBoundsChange(task.token)).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_byDisplay_notInImmersive_doesNotChangeTaskBounds() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = false
        )

        immersiveHandler.exitImmersiveIfApplicable(transition, wct, DEFAULT_DISPLAY)

        assertThat(wct.hasBoundsChange(task.token)).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_byTask_inImmersive_changesTaskBounds() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.exitImmersiveIfApplicable(wct = wct, taskInfo = task)

        assertThat(wct.hasBoundsChange(task.token)).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_byTask_notInImmersive_doesNotChangeTaskBounds() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = false
        )

        immersiveHandler.exitImmersiveIfApplicable(wct, task)

        assertThat(wct.hasBoundsChange(task.token)).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_byTask_inImmersive_addsPendingExitOnRun() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.exitImmersiveIfApplicable(wct, task)?.invoke(transition)

        assertThat(immersiveHandler.pendingExternalExitTransitions.any { exit ->
            exit.transition == transition && exit.displayId == DEFAULT_DISPLAY
                    && exit.taskId == task.taskId
        }).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersiveIfApplicable_byTask_notInImmersive_doesNotAddPendingExitOnRun() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = false
        )

        immersiveHandler.exitImmersiveIfApplicable(wct, task)?.invoke(transition)

        assertThat(immersiveHandler.pendingExternalExitTransitions.any { exit ->
            exit.transition == transition && exit.displayId == DEFAULT_DISPLAY
                    && exit.taskId == task.taskId
        }).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun onTransitionReady_pendingExit_removesPendingExit() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )
        immersiveHandler.exitImmersiveIfApplicable(transition, wct, DEFAULT_DISPLAY)

        immersiveHandler.onTransitionReady(
            transition = transition,
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply { taskInfo = task }
                )
            )
        )

        assertThat(immersiveHandler.pendingExternalExitTransitions.any { exit ->
            exit.transition == transition && exit.displayId == DEFAULT_DISPLAY
                    && exit.taskId == task.taskId
        }).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun onTransitionReady_pendingExit_updatesRepository() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )
        immersiveHandler.exitImmersiveIfApplicable(transition, wct, DEFAULT_DISPLAY)

        immersiveHandler.onTransitionReady(
            transition = transition,
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply { taskInfo = task }
                )
            )
        )

        assertThat(desktopRepository.isTaskInFullImmersiveState(task.taskId)).isFalse()
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP,
        Flags.FLAG_ENABLE_RESTORE_TO_PREVIOUS_SIZE_FROM_DESKTOP_IMMERSIVE
    )
    fun onTransitionReady_pendingExit_removesBoundsBeforeImmersive() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        val transition = Binder()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )
        desktopRepository.saveBoundsBeforeFullImmersive(task.taskId, Rect(100, 100, 600, 600))
        immersiveHandler.exitImmersiveIfApplicable(transition, wct, DEFAULT_DISPLAY)

        immersiveHandler.onTransitionReady(
            transition = transition,
            info = createTransitionInfo(
                changes = listOf(
                    TransitionInfo.Change(task.token, SurfaceControl()).apply { taskInfo = task }
                )
            )
        )

        assertThat(desktopRepository.removeBoundsBeforeMaximize(task.taskId)).isNull()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    @DisableFlags(Flags.FLAG_ENABLE_RESTORE_TO_PREVIOUS_SIZE_FROM_DESKTOP_IMMERSIVE)
    fun exitImmersiveIfApplicable_changesBoundsToMaximize() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.exitImmersiveIfApplicable(wct = wct, taskInfo = task)

        assertThat(
            wct.hasBoundsChange(task.token, calculateMaximizeBounds(mockDisplayLayout, task))
        ).isTrue()
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP,
        Flags.FLAG_ENABLE_RESTORE_TO_PREVIOUS_SIZE_FROM_DESKTOP_IMMERSIVE
    )
    fun exitImmersiveIfApplicable_preImmersiveBoundsSaved_changesBoundsToPreImmersiveBounds() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )
        val preImmersiveBounds = Rect(100, 100, 500, 500)
        desktopRepository.saveBoundsBeforeFullImmersive(task.taskId, preImmersiveBounds)

        immersiveHandler.exitImmersiveIfApplicable(wct = wct, taskInfo = task)

        assertThat(
            wct.hasBoundsChange(task.token, preImmersiveBounds)
        ).isTrue()
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP,
        Flags.FLAG_ENABLE_RESTORE_TO_PREVIOUS_SIZE_FROM_DESKTOP_IMMERSIVE,
        Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    )
    fun exitImmersiveIfApplicable_preImmersiveBoundsNotSaved_changesBoundsToInitialBounds() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = DEFAULT_DISPLAY,
            taskId = task.taskId,
            immersive = true
        )

        immersiveHandler.exitImmersiveIfApplicable(wct = wct, taskInfo = task)

        assertThat(
            wct.hasBoundsChange(task.token, calculateInitialBounds(mockDisplayLayout, task))
        ).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun exitImmersive_pendingExit_doesNotExitAgain() {
        val task = createFreeformTask()
        whenever(mockShellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        val wct = WindowContainerTransaction()
        desktopRepository.setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = true
        )
        immersiveHandler.exitImmersiveIfApplicable(wct, task)?.invoke(Binder())

        immersiveHandler.moveTaskToNonImmersive(task)

        verify(mockTransitions, never()).startTransition(any(), any(), any())
    }

    private fun createTransitionInfo(
        @TransitionType type: Int = TRANSIT_CHANGE,
        @TransitionFlags flags: Int = 0,
        changes: List<TransitionInfo.Change> = emptyList()
    ): TransitionInfo = TransitionInfo(type, flags).apply {
        changes.forEach { change -> addChange(change) }
    }

    private fun WindowContainerTransaction.hasBoundsChange(token: WindowContainerToken): Boolean =
        this.changes.any { change ->
            change.key == token.asBinder()
                    && (change.value.windowSetMask and WINDOW_CONFIG_BOUNDS) != 0
        }

    private fun WindowContainerTransaction.hasBoundsChange(
        token: WindowContainerToken,
        bounds: Rect,
    ): Boolean = this.changes.any { change ->
        change.key == token.asBinder()
                && (change.value.windowSetMask and WINDOW_CONFIG_BOUNDS) != 0
                && change.value.configuration.windowConfiguration.bounds == bounds
    }

    companion object {
        private val STABLE_BOUNDS = Rect(0, 100, 2000, 1900)
    }
}
