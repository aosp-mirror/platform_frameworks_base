/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.content.res.Configuration.SCREENLAYOUT_SIZE_NORMAL
import android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE
import android.os.Binder
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManager
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.DisplayAreaInfo
import android.window.RemoteTransition
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.wm.shell.MockToken
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.transition.TestRemoteTransition
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.LaunchAdjacentController
import com.android.wm.shell.common.MultiInstanceHelper
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.split.SplitScreenConstants
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFreeformTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFullscreenTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createHomeTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createSplitScreenTask
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.OneShotRemoteHandler
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS
import com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_DESKTOP_MODE
import com.android.wm.shell.transition.Transitions.TransitionHandler
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isA
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopTasksControllerTest : ShellTestCase() {

    @Mock lateinit var testExecutor: ShellExecutor
    @Mock lateinit var shellCommandHandler: ShellCommandHandler
    @Mock lateinit var shellController: ShellController
    @Mock lateinit var displayController: DisplayController
    @Mock lateinit var shellTaskOrganizer: ShellTaskOrganizer
    @Mock lateinit var syncQueue: SyncTransactionQueue
    @Mock lateinit var rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock lateinit var transitions: Transitions
    @Mock lateinit var exitDesktopTransitionHandler: ExitDesktopTaskTransitionHandler
    @Mock lateinit var enterDesktopTransitionHandler: EnterDesktopTaskTransitionHandler
    @Mock lateinit var mToggleResizeDesktopTaskTransitionHandler:
            ToggleResizeDesktopTaskTransitionHandler
    @Mock lateinit var dragToDesktopTransitionHandler: DragToDesktopTransitionHandler
    @Mock lateinit var launchAdjacentController: LaunchAdjacentController
    @Mock lateinit var desktopModeWindowDecoration: DesktopModeWindowDecoration
    @Mock lateinit var splitScreenController: SplitScreenController
    @Mock lateinit var recentsTransitionHandler: RecentsTransitionHandler
    @Mock lateinit var dragAndDropController: DragAndDropController
    @Mock lateinit var multiInstanceHelper: MultiInstanceHelper

    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var controller: DesktopTasksController
    private lateinit var shellInit: ShellInit
    private lateinit var desktopModeTaskRepository: DesktopModeTaskRepository
    private lateinit var recentsTransitionStateListener: RecentsTransitionStateListener

    private val shellExecutor = TestShellExecutor()
    // Mock running tasks are registered here so we can get the list from mock shell task organizer
    private val runningTasks = mutableListOf<RunningTaskInfo>()

    @Before
    fun setUp() {
        mockitoSession = mockitoSession().spyStatic(DesktopModeStatus::class.java).startMocking()
        whenever(DesktopModeStatus.isEnabled()).thenReturn(true)

        shellInit = Mockito.spy(ShellInit(testExecutor))
        desktopModeTaskRepository = DesktopModeTaskRepository()

        whenever(shellTaskOrganizer.getRunningTasks(anyInt())).thenAnswer { runningTasks }
        whenever(transitions.startTransition(anyInt(), any(), isNull())).thenAnswer { Binder() }

        controller = createController()
        controller.setSplitScreenController(splitScreenController)

        shellInit.init()

        val captor = ArgumentCaptor.forClass(RecentsTransitionStateListener::class.java)
        verify(recentsTransitionHandler).addTransitionStateListener(captor.capture())
        recentsTransitionStateListener = captor.value
    }

    private fun createController(): DesktopTasksController {
        return DesktopTasksController(
            context,
            shellInit,
            shellCommandHandler,
            shellController,
            displayController,
            shellTaskOrganizer,
            syncQueue,
            rootTaskDisplayAreaOrganizer,
            dragAndDropController,
            transitions,
            enterDesktopTransitionHandler,
            exitDesktopTransitionHandler,
            mToggleResizeDesktopTaskTransitionHandler,
            dragToDesktopTransitionHandler,
            desktopModeTaskRepository,
            launchAdjacentController,
            recentsTransitionHandler,
            multiInstanceHelper,
            shellExecutor
        )
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()

        runningTasks.clear()
    }

    @Test
    fun instantiate_addInitCallback() {
        verify(shellInit).addInitCallback(any(), any<DesktopTasksController>())
    }

    @Test
    fun instantiate_flagOff_doNotAddInitCallback() {
        whenever(DesktopModeStatus.isEnabled()).thenReturn(false)
        clearInvocations(shellInit)

        createController()

        verify(shellInit, never()).addInitCallback(any(), any<DesktopTasksController>())
    }

    @Test
    fun showDesktopApps_allAppsInvisible_bringsToFront() {
        val homeTask = setUpHomeTask()
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        markTaskHidden(task1)
        markTaskHidden(task2)

        controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

        val wct =
            getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
        assertThat(wct.hierarchyOps).hasSize(3)
        // Expect order to be from bottom: home, task1, task2
        wct.assertReorderAt(index = 0, homeTask)
        wct.assertReorderAt(index = 1, task1)
        wct.assertReorderAt(index = 2, task2)
    }

    @Test
    fun showDesktopApps_appsAlreadyVisible_bringsToFront() {
        val homeTask = setUpHomeTask()
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        markTaskVisible(task1)
        markTaskVisible(task2)

        controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

        val wct =
            getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
        assertThat(wct.hierarchyOps).hasSize(3)
        // Expect order to be from bottom: home, task1, task2
        wct.assertReorderAt(index = 0, homeTask)
        wct.assertReorderAt(index = 1, task1)
        wct.assertReorderAt(index = 2, task2)
    }

    @Test
    fun showDesktopApps_someAppsInvisible_reordersAll() {
        val homeTask = setUpHomeTask()
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        markTaskHidden(task1)
        markTaskVisible(task2)

        controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

        val wct =
            getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
        assertThat(wct.hierarchyOps).hasSize(3)
        // Expect order to be from bottom: home, task1, task2
        wct.assertReorderAt(index = 0, homeTask)
        wct.assertReorderAt(index = 1, task1)
        wct.assertReorderAt(index = 2, task2)
    }

    @Test
    fun showDesktopApps_noActiveTasks_reorderHomeToTop() {
        val homeTask = setUpHomeTask()

        controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

        val wct =
            getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
        assertThat(wct.hierarchyOps).hasSize(1)
        wct.assertReorderAt(index = 0, homeTask)
    }

    @Test
    fun showDesktopApps_twoDisplays_bringsToFrontOnlyOneDisplay() {
        val homeTaskDefaultDisplay = setUpHomeTask(DEFAULT_DISPLAY)
        val taskDefaultDisplay = setUpFreeformTask(DEFAULT_DISPLAY)
        setUpHomeTask(SECOND_DISPLAY)
        val taskSecondDisplay = setUpFreeformTask(SECOND_DISPLAY)
        markTaskHidden(taskDefaultDisplay)
        markTaskHidden(taskSecondDisplay)

        controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

        val wct =
            getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
        assertThat(wct.hierarchyOps).hasSize(2)
        // Expect order to be from bottom: home, task
        wct.assertReorderAt(index = 0, homeTaskDefaultDisplay)
        wct.assertReorderAt(index = 1, taskDefaultDisplay)
    }

    @Test
    fun getVisibleTaskCount_noTasks_returnsZero() {
        assertThat(controller.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)
    }

    @Test
    fun getVisibleTaskCount_twoTasks_bothVisible_returnsTwo() {
        setUpHomeTask()
        setUpFreeformTask().also(::markTaskVisible)
        setUpFreeformTask().also(::markTaskVisible)
        assertThat(controller.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(2)
    }

    @Test
    fun getVisibleTaskCount_twoTasks_oneVisible_returnsOne() {
        setUpHomeTask()
        setUpFreeformTask().also(::markTaskVisible)
        setUpFreeformTask().also(::markTaskHidden)
        assertThat(controller.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(1)
    }

    @Test
    fun getVisibleTaskCount_twoTasksVisibleOnDifferentDisplays_returnsOne() {
        setUpHomeTask()
        setUpFreeformTask(DEFAULT_DISPLAY).also(::markTaskVisible)
        setUpFreeformTask(SECOND_DISPLAY).also(::markTaskVisible)
        assertThat(controller.getVisibleTaskCount(SECOND_DISPLAY)).isEqualTo(1)
    }

    @Test
    fun moveToDesktop_displayFullscreen_windowingModeSetToFreeform() {
        val task = setUpFullscreenTask()
        task.configuration.windowConfiguration.displayWindowingMode = WINDOWING_MODE_FULLSCREEN
        controller.moveToDesktop(task)
        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
    }

    @Test
    fun moveToDesktop_displayFreeform_windowingModeSetToUndefined() {
        val task = setUpFullscreenTask()
        task.configuration.windowConfiguration.displayWindowingMode = WINDOWING_MODE_FREEFORM
        controller.moveToDesktop(task)
        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_UNDEFINED)
    }

    @Test
    fun moveToDesktop_nonExistentTask_doesNothing() {
        controller.moveToDesktop(999)
        verifyWCTNotExecuted()
    }

    @Test
    fun moveToDesktop_screenSizeBelowXLarge_doesNothing() {
        val task = setUpFullscreenTask()

        // Update screen layout to be below minimum size
        task.configuration.screenLayout = SCREENLAYOUT_SIZE_NORMAL

        controller.moveToDesktop(task)
        verifyWCTNotExecuted()
    }

    @Test
    fun moveToDesktop_screenSizeBelowXLarge_displayRestrictionsOverridden_taskIsMovedToDesktop() {
        val task = setUpFullscreenTask()

        // Update screen layout to be below minimum size
        task.configuration.screenLayout = SCREENLAYOUT_SIZE_NORMAL

        // Simulate enforce display restrictions system property overridden to false
        whenever(DesktopModeStatus.enforceDisplayRestrictions()).thenReturn(false)

        controller.moveToDesktop(task)

        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
    }

    @Test
    fun moveToDesktop_screenSizeXLarge_taskIsMovedToDesktop() {
        val task = setUpFullscreenTask()

        controller.moveToDesktop(task)

        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
    }

    @Test
    fun moveToDesktop_otherFreeformTasksBroughtToFront() {
        val homeTask = setUpHomeTask()
        val freeformTask = setUpFreeformTask()
        val fullscreenTask = setUpFullscreenTask()
        markTaskHidden(freeformTask)

        controller.moveToDesktop(fullscreenTask)

        with(getLatestMoveToDesktopWct()) {
            // Operations should include home task, freeform task
            assertThat(hierarchyOps).hasSize(3)
            assertReorderSequence(homeTask, freeformTask, fullscreenTask)
            assertThat(changes[fullscreenTask.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FREEFORM)
        }
    }

    @Test
    fun moveToDesktop_onlyFreeformTasksFromCurrentDisplayBroughtToFront() {
        setUpHomeTask(displayId = DEFAULT_DISPLAY)
        val freeformTaskDefault = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
        val fullscreenTaskDefault = setUpFullscreenTask(displayId = DEFAULT_DISPLAY)
        markTaskHidden(freeformTaskDefault)

        val homeTaskSecond = setUpHomeTask(displayId = SECOND_DISPLAY)
        val freeformTaskSecond = setUpFreeformTask(displayId = SECOND_DISPLAY)
        markTaskHidden(freeformTaskSecond)

        controller.moveToDesktop(fullscreenTaskDefault)

        with(getLatestMoveToDesktopWct()) {
            // Check that hierarchy operations do not include tasks from second display
            assertThat(hierarchyOps.map { it.container })
                .doesNotContain(homeTaskSecond.token.asBinder())
            assertThat(hierarchyOps.map { it.container })
                .doesNotContain(freeformTaskSecond.token.asBinder())
        }
    }

    @Test
    fun moveToDesktop_splitTaskExitsSplit() {
        val task = setUpSplitScreenTask()
        controller.moveToDesktop(task)
        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
        verify(splitScreenController).prepareExitSplitScreen(any(), anyInt(),
            eq(SplitScreenController.EXIT_REASON_DESKTOP_MODE)
        )
    }

    @Test
    fun moveToDesktop_fullscreenTaskDoesNotExitSplit() {
        val task = setUpFullscreenTask()
        controller.moveToDesktop(task)
        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
        verify(splitScreenController, never()).prepareExitSplitScreen(any(), anyInt(),
            eq(SplitScreenController.EXIT_REASON_DESKTOP_MODE)
        )
    }

    @Test
    fun moveToFullscreen_displayFullscreen_windowingModeSetToUndefined() {
        val task = setUpFreeformTask()
        task.configuration.windowConfiguration.displayWindowingMode = WINDOWING_MODE_FULLSCREEN
        controller.moveToFullscreen(task.taskId)
        val wct = getLatestExitDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_UNDEFINED)
    }

    @Test
    fun moveToFullscreen_displayFreeform_windowingModeSetToFullscreen() {
        val task = setUpFreeformTask()
        task.configuration.windowConfiguration.displayWindowingMode = WINDOWING_MODE_FREEFORM
        controller.moveToFullscreen(task.taskId)
        val wct = getLatestExitDesktopWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FULLSCREEN)
    }

    @Test
    fun moveToFullscreen_nonExistentTask_doesNothing() {
        controller.moveToFullscreen(999)
        verifyWCTNotExecuted()
    }

    @Test
    fun moveToFullscreen_secondDisplayTaskHasFreeform_secondDisplayNotAffected() {
        val taskDefaultDisplay = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
        val taskSecondDisplay = setUpFreeformTask(displayId = SECOND_DISPLAY)

        controller.moveToFullscreen(taskDefaultDisplay.taskId)

        with(getLatestExitDesktopWct()) {
            assertThat(changes.keys).contains(taskDefaultDisplay.token.asBinder())
            assertThat(changes.keys).doesNotContain(taskSecondDisplay.token.asBinder())
        }
    }

    @Test
    fun moveTaskToFront_postsWctWithReorderOp() {
        val task1 = setUpFreeformTask()
        setUpFreeformTask()

        controller.moveTaskToFront(task1)

        val wct = getLatestWct(type = TRANSIT_TO_FRONT)
        assertThat(wct.hierarchyOps).hasSize(1)
        wct.assertReorderAt(index = 0, task1)
    }

    @Test
    fun moveToNextDisplay_noOtherDisplays() {
        whenever(rootTaskDisplayAreaOrganizer.displayIds).thenReturn(intArrayOf(DEFAULT_DISPLAY))
        val task = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
        controller.moveToNextDisplay(task.taskId)
        verifyWCTNotExecuted()
    }

    @Test
    fun moveToNextDisplay_moveFromFirstToSecondDisplay() {
        // Set up two display ids
        whenever(rootTaskDisplayAreaOrganizer.displayIds)
                .thenReturn(intArrayOf(DEFAULT_DISPLAY, SECOND_DISPLAY))
        // Create a mock for the target display area: second display
        val secondDisplayArea = DisplayAreaInfo(MockToken().token(), SECOND_DISPLAY, 0)
        whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(SECOND_DISPLAY))
                .thenReturn(secondDisplayArea)

        val task = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
        controller.moveToNextDisplay(task.taskId)
        with(getLatestWct(type = TRANSIT_CHANGE)) {
            assertThat(hierarchyOps).hasSize(1)
            assertThat(hierarchyOps[0].container).isEqualTo(task.token.asBinder())
            assertThat(hierarchyOps[0].isReparent).isTrue()
            assertThat(hierarchyOps[0].newParent).isEqualTo(secondDisplayArea.token.asBinder())
            assertThat(hierarchyOps[0].toTop).isTrue()
        }
    }

    @Test
    fun moveToNextDisplay_moveFromSecondToFirstDisplay() {
        // Set up two display ids
        whenever(rootTaskDisplayAreaOrganizer.displayIds)
            .thenReturn(intArrayOf(DEFAULT_DISPLAY, SECOND_DISPLAY))
        // Create a mock for the target display area: default display
        val defaultDisplayArea = DisplayAreaInfo(MockToken().token(), DEFAULT_DISPLAY, 0)
        whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY))
                .thenReturn(defaultDisplayArea)

        val task = setUpFreeformTask(displayId = SECOND_DISPLAY)
        controller.moveToNextDisplay(task.taskId)

        with(getLatestWct(type = TRANSIT_CHANGE)) {
            assertThat(hierarchyOps).hasSize(1)
            assertThat(hierarchyOps[0].container).isEqualTo(task.token.asBinder())
            assertThat(hierarchyOps[0].isReparent).isTrue()
            assertThat(hierarchyOps[0].newParent).isEqualTo(defaultDisplayArea.token.asBinder())
            assertThat(hierarchyOps[0].toTop).isTrue()
        }
    }

    @Test
    fun getTaskWindowingMode() {
        val fullscreenTask = setUpFullscreenTask()
        val freeformTask = setUpFreeformTask()

        assertThat(controller.getTaskWindowingMode(fullscreenTask.taskId))
            .isEqualTo(WINDOWING_MODE_FULLSCREEN)
        assertThat(controller.getTaskWindowingMode(freeformTask.taskId))
            .isEqualTo(WINDOWING_MODE_FREEFORM)
        assertThat(controller.getTaskWindowingMode(999)).isEqualTo(WINDOWING_MODE_UNDEFINED)
    }

    @Test
    fun handleRequest_fullscreenTask_freeformVisible_returnSwitchToFreeformWCT() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val freeformTask = setUpFreeformTask()
        markTaskVisible(freeformTask)
        val fullscreenTask = createFullscreenTask()

        val result = controller.handleRequest(Binder(), createTransition(fullscreenTask))
        assertThat(result?.changes?.get(fullscreenTask.token.asBinder())?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
    }

    @Test
    fun handleRequest_fullscreenTask_freeformNotVisible_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val freeformTask = setUpFreeformTask()
        markTaskHidden(freeformTask)
        val fullscreenTask = createFullscreenTask()
        assertThat(controller.handleRequest(Binder(), createTransition(fullscreenTask))).isNull()
    }

    @Test
    fun handleRequest_fullscreenTask_noOtherTasks_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val fullscreenTask = createFullscreenTask()
        assertThat(controller.handleRequest(Binder(), createTransition(fullscreenTask))).isNull()
    }

    @Test
    fun handleRequest_fullscreenTask_freeformTaskOnOtherDisplay_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val fullscreenTaskDefaultDisplay = createFullscreenTask(displayId = DEFAULT_DISPLAY)
        createFreeformTask(displayId = SECOND_DISPLAY)

        val result =
            controller.handleRequest(Binder(), createTransition(fullscreenTaskDefaultDisplay))
        assertThat(result).isNull()
    }

    @Test
    fun handleRequest_fullscreenTask_desktopStashed_returnWCTWithAllAppsBroughtToFront() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)
        whenever(DesktopModeStatus.isStashingEnabled()).thenReturn(true)

        val stashedFreeformTask = setUpFreeformTask(DEFAULT_DISPLAY)
        markTaskHidden(stashedFreeformTask)

        val fullscreenTask = createFullscreenTask(DEFAULT_DISPLAY)

        controller.stashDesktopApps(DEFAULT_DISPLAY)

        val result = controller.handleRequest(Binder(), createTransition(fullscreenTask))
        assertThat(result).isNotNull()
        result!!.assertReorderSequence(stashedFreeformTask, fullscreenTask)
        assertThat(result.changes[fullscreenTask.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FREEFORM)

        // Stashed state should be cleared
        assertThat(desktopModeTaskRepository.isStashed(DEFAULT_DISPLAY)).isFalse()
    }

    @Test
    fun handleRequest_freeformTask_freeformVisible_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val freeformTask1 = setUpFreeformTask()
        markTaskVisible(freeformTask1)

        val freeformTask2 = createFreeformTask()
        assertThat(controller.handleRequest(Binder(), createTransition(freeformTask2))).isNull()
    }

    @Test
    fun handleRequest_freeformTask_freeformNotVisible_returnSwitchToFullscreenWCT() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val freeformTask1 = setUpFreeformTask()
        markTaskHidden(freeformTask1)

        val freeformTask2 = createFreeformTask()
        val result =
            controller.handleRequest(
                Binder(),
                createTransition(freeformTask2, type = TRANSIT_TO_FRONT)
            )
        assertThat(result?.changes?.get(freeformTask2.token.asBinder())?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FULLSCREEN)
    }

    @Test
    fun handleRequest_freeformTask_noOtherTasks_returnSwitchToFullscreenWCT() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val task = createFreeformTask()
        val result = controller.handleRequest(Binder(), createTransition(task))
        assertThat(result?.changes?.get(task.token.asBinder())?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FULLSCREEN)
    }

    @Test
    fun handleRequest_freeformTask_freeformOnOtherDisplayOnly_returnSwitchToFullscreenWCT() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val taskDefaultDisplay = createFreeformTask(displayId = DEFAULT_DISPLAY)
        createFreeformTask(displayId = SECOND_DISPLAY)

        val result = controller.handleRequest(Binder(), createTransition(taskDefaultDisplay))
        assertThat(result?.changes?.get(taskDefaultDisplay.token.asBinder())?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FULLSCREEN)
    }

    @Test
    fun handleRequest_freeformTask_desktopStashed_returnWCTWithAllAppsBroughtToFront() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)
        whenever(DesktopModeStatus.isStashingEnabled()).thenReturn(true)

        val stashedFreeformTask = setUpFreeformTask(DEFAULT_DISPLAY)
        markTaskHidden(stashedFreeformTask)

        val freeformTask = createFreeformTask(DEFAULT_DISPLAY)

        controller.stashDesktopApps(DEFAULT_DISPLAY)

        val result = controller.handleRequest(Binder(), createTransition(freeformTask))
        assertThat(result).isNotNull()
        result?.assertReorderSequence(stashedFreeformTask, freeformTask)

        // Stashed state should be cleared
        assertThat(desktopModeTaskRepository.isStashed(DEFAULT_DISPLAY)).isFalse()
    }

    @Test
    fun handleRequest_notOpenOrToFrontTransition_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val task =
            TestRunningTaskInfoBuilder()
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .build()
        val transition = createTransition(task = task, type = WindowManager.TRANSIT_CLOSE)
        val result = controller.handleRequest(Binder(), transition)
        assertThat(result).isNull()
    }

    @Test
    fun handleRequest_noTriggerTask_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)
        assertThat(controller.handleRequest(Binder(), createTransition(task = null))).isNull()
    }

    @Test
    fun handleRequest_triggerTaskNotStandard_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)
        val task = TestRunningTaskInfoBuilder().setActivityType(ACTIVITY_TYPE_HOME).build()
        assertThat(controller.handleRequest(Binder(), createTransition(task))).isNull()
    }

    @Test
    fun handleRequest_triggerTaskNotFullscreenOrFreeform_returnNull() {
        assumeTrue(ENABLE_SHELL_TRANSITIONS)

        val task =
            TestRunningTaskInfoBuilder()
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW)
                .build()
        assertThat(controller.handleRequest(Binder(), createTransition(task))).isNull()
    }

    @Test
    fun handleRequest_recentsAnimationRunning_returnNull() {
        // Set up a visible freeform task so a fullscreen task should be converted to freeform
        val freeformTask = setUpFreeformTask()
        markTaskVisible(freeformTask)

        // Mark recents animation running
        recentsTransitionStateListener.onAnimationStateChanged(true)

        // Open a fullscreen task, check that it does not result in a WCT with changes to it
        val fullscreenTask = createFullscreenTask()
        assertThat(controller.handleRequest(Binder(), createTransition(fullscreenTask))).isNull()
    }

    @Test
    fun stashDesktopApps_stateUpdates() {
        whenever(DesktopModeStatus.isStashingEnabled()).thenReturn(true)

        controller.stashDesktopApps(DEFAULT_DISPLAY)

        assertThat(desktopModeTaskRepository.isStashed(DEFAULT_DISPLAY)).isTrue()
        assertThat(desktopModeTaskRepository.isStashed(SECOND_DISPLAY)).isFalse()
    }

    @Test
    fun hideStashedDesktopApps_stateUpdates() {
        whenever(DesktopModeStatus.isStashingEnabled()).thenReturn(true)

        desktopModeTaskRepository.setStashed(DEFAULT_DISPLAY, true)
        desktopModeTaskRepository.setStashed(SECOND_DISPLAY, true)
        controller.hideStashedDesktopApps(DEFAULT_DISPLAY)

        assertThat(desktopModeTaskRepository.isStashed(DEFAULT_DISPLAY)).isFalse()
        // Check that second display is not affected
        assertThat(desktopModeTaskRepository.isStashed(SECOND_DISPLAY)).isTrue()
    }

    @Test
    fun desktopTasksVisibilityChange_visible_setLaunchAdjacentDisabled() {
        val task = setUpFreeformTask()
        clearInvocations(launchAdjacentController)

        markTaskVisible(task)
        shellExecutor.flushAll()
        verify(launchAdjacentController).launchAdjacentEnabled = false
    }

    @Test
    fun desktopTasksVisibilityChange_invisible_setLaunchAdjacentEnabled() {
        val task = setUpFreeformTask()
        markTaskVisible(task)
        clearInvocations(launchAdjacentController)

        markTaskHidden(task)
        shellExecutor.flushAll()
        verify(launchAdjacentController).launchAdjacentEnabled = true
    }
    @Test
    fun enterDesktop_fullscreenTaskIsMovedToDesktop() {
        val task1 = setUpFullscreenTask()
        val task2 = setUpFullscreenTask()
        val task3 = setUpFullscreenTask()

        task1.isFocused = true
        task2.isFocused = false
        task3.isFocused = false

        controller.enterDesktop(DEFAULT_DISPLAY)

        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task1.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FREEFORM)
    }

    @Test
    fun enterDesktop_splitScreenTaskIsMovedToDesktop() {
        val task1 = setUpSplitScreenTask()
        val task2 = setUpFullscreenTask()
        val task3 = setUpFullscreenTask()
        val task4 = setUpSplitScreenTask()

        task1.isFocused = true
        task2.isFocused = false
        task3.isFocused = false
        task4.isFocused = true

        task4.parentTaskId = task1.taskId

        controller.enterDesktop(DEFAULT_DISPLAY)

        val wct = getLatestMoveToDesktopWct()
        assertThat(wct.changes[task4.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FREEFORM)
        verify(splitScreenController).prepareExitSplitScreen(any(), anyInt(),
            eq(SplitScreenController.EXIT_REASON_DESKTOP_MODE)
        )
    }

    @Test
    fun moveFocusedTaskToFullscreen() {
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        val task3 = setUpFreeformTask()

        task1.isFocused = false
        task2.isFocused = true
        task3.isFocused = false

        controller.enterFullscreen(DEFAULT_DISPLAY)

        val wct = getLatestExitDesktopWct()
        assertThat(wct.changes[task2.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FULLSCREEN)
    }

    fun enterSplit_freeformTaskIsMovedToSplit() {
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        val task3 = setUpFreeformTask()

        task1.isFocused = false
        task2.isFocused = true
        task3.isFocused = false

        controller.enterSplit(DEFAULT_DISPLAY, false)

        verify(splitScreenController).requestEnterSplitSelect(task2, any(),
            SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT,
            task2.configuration.windowConfiguration.bounds)
    }

    private fun setUpFreeformTask(displayId: Int = DEFAULT_DISPLAY): RunningTaskInfo {
        val task = createFreeformTask(displayId)
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        desktopModeTaskRepository.addActiveTask(displayId, task.taskId)
        desktopModeTaskRepository.addOrMoveFreeformTaskToTop(task.taskId)
        runningTasks.add(task)
        return task
    }

    private fun setUpHomeTask(displayId: Int = DEFAULT_DISPLAY): RunningTaskInfo {
        val task = createHomeTask(displayId)
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        runningTasks.add(task)
        return task
    }

    private fun setUpFullscreenTask(displayId: Int = DEFAULT_DISPLAY): RunningTaskInfo {
        val task = createFullscreenTask(displayId)
        task.configuration.screenLayout = SCREENLAYOUT_SIZE_XLARGE
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        runningTasks.add(task)
        return task
    }

    private fun setUpSplitScreenTask(displayId: Int = DEFAULT_DISPLAY): RunningTaskInfo {
        val task = createSplitScreenTask(displayId)
        task.configuration.screenLayout = SCREENLAYOUT_SIZE_XLARGE
        whenever(splitScreenController.isTaskInSplitScreen(task.taskId)).thenReturn(true)
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        runningTasks.add(task)
        return task
    }

    private fun markTaskVisible(task: RunningTaskInfo) {
        desktopModeTaskRepository.updateVisibleFreeformTasks(
            task.displayId,
            task.taskId,
            visible = true
        )
    }

    private fun markTaskHidden(task: RunningTaskInfo) {
        desktopModeTaskRepository.updateVisibleFreeformTasks(
            task.displayId,
            task.taskId,
            visible = false
        )
    }

    private fun getLatestWct(
            @WindowManager.TransitionType type: Int = TRANSIT_OPEN,
            handlerClass: Class<out TransitionHandler>? = null
    ): WindowContainerTransaction {
        val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
        if (ENABLE_SHELL_TRANSITIONS) {
            if (handlerClass == null) {
                verify(transitions).startTransition(eq(type), arg.capture(), isNull())
            } else {
                verify(transitions).startTransition(eq(type), arg.capture(), isA(handlerClass))
            }
        } else {
            verify(shellTaskOrganizer).applyTransaction(arg.capture())
        }
        return arg.value
    }

    private fun getLatestMoveToDesktopWct(): WindowContainerTransaction {
        val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
        if (ENABLE_SHELL_TRANSITIONS) {
            verify(enterDesktopTransitionHandler).moveToDesktop(arg.capture())
        } else {
            verify(shellTaskOrganizer).applyTransaction(arg.capture())
        }
        return arg.value
    }

    private fun getLatestExitDesktopWct(): WindowContainerTransaction {
        val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
        if (ENABLE_SHELL_TRANSITIONS) {
            verify(exitDesktopTransitionHandler)
                    .startTransition(eq(TRANSIT_EXIT_DESKTOP_MODE), arg.capture(), any(), any())
        } else {
            verify(shellTaskOrganizer).applyTransaction(arg.capture())
        }
        return arg.value
    }

    private fun verifyWCTNotExecuted() {
        if (ENABLE_SHELL_TRANSITIONS) {
            verify(transitions, never()).startTransition(anyInt(), any(), isNull())
        } else {
            verify(shellTaskOrganizer, never()).applyTransaction(any())
        }
    }

    private fun createTransition(
        task: RunningTaskInfo?,
        @WindowManager.TransitionType type: Int = TRANSIT_OPEN
    ): TransitionRequestInfo {
        return TransitionRequestInfo(type, task, null /* remoteTransition */)
    }

    companion object {
        const val SECOND_DISPLAY = 2
    }
}

private fun WindowContainerTransaction.assertReorderAt(index: Int, task: RunningTaskInfo) {
    assertWithMessage("WCT does not have a hierarchy operation at index $index")
        .that(hierarchyOps.size)
        .isGreaterThan(index)
    val op = hierarchyOps[index]
    assertThat(op.type).isEqualTo(HIERARCHY_OP_TYPE_REORDER)
    assertThat(op.container).isEqualTo(task.token.asBinder())
}

private fun WindowContainerTransaction.assertReorderSequence(vararg tasks: RunningTaskInfo) {
    for (i in tasks.indices) {
        assertReorderAt(i, tasks[i])
    }
}
