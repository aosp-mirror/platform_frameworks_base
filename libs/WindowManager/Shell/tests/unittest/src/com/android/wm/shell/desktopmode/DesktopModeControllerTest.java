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

package com.android.wm.shell.desktopmode;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOW_CONFIG_BOUNDS;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.wm.shell.desktopmode.DesktopTestHelpers.createFreeformTask;
import static com.android.wm.shell.desktopmode.DesktopTestHelpers.createFullscreenTask;
import static com.android.wm.shell.desktopmode.DesktopTestHelpers.createHomeTask;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.RunningTaskInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;
import android.window.DisplayAreaInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransaction.Change;
import android.window.WindowContainerTransaction.HierarchyOp;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DesktopModeControllerTest extends ShellTestCase {

    @Mock
    private ShellController mShellController;
    @Mock
    private ShellTaskOrganizer mShellTaskOrganizer;
    @Mock
    private RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    @Mock
    private ShellExecutor mTestExecutor;
    @Mock
    private Handler mMockHandler;
    @Mock
    private Transitions mTransitions;
    private DesktopModeController mController;
    private DesktopModeTaskRepository mDesktopModeTaskRepository;
    private ShellInit mShellInit;
    private StaticMockitoSession mMockitoSession;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession().mockStatic(DesktopModeStatus.class).startMocking();
        when(DesktopModeStatus.isProto1Enabled()).thenReturn(true);
        when(DesktopModeStatus.isActive(any())).thenReturn(true);

        mShellInit = Mockito.spy(new ShellInit(mTestExecutor));

        mDesktopModeTaskRepository = new DesktopModeTaskRepository();

        mController = createController();

        when(mShellTaskOrganizer.getRunningTasks(anyInt())).thenReturn(new ArrayList<>());

        mShellInit.init();
        clearInvocations(mShellTaskOrganizer);
        clearInvocations(mRootTaskDisplayAreaOrganizer);
        clearInvocations(mTransitions);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void instantiate_addInitCallback() {
        verify(mShellInit).addInitCallback(any(), any());
    }

    @Test
    public void instantiate_flagOff_doNotAddInitCallback() {
        when(DesktopModeStatus.isProto1Enabled()).thenReturn(false);
        clearInvocations(mShellInit);

        createController();

        verify(mShellInit, never()).addInitCallback(any(), any());
    }

    @Test
    public void testDesktopModeEnabled_rootTdaSetToFreeform() {
        DisplayAreaInfo displayAreaInfo = createMockDisplayArea();

        mController.updateDesktopModeActive(true);
        WindowContainerTransaction wct = getDesktopModeSwitchTransaction();

        // 1 change: Root TDA windowing mode
        assertThat(wct.getChanges().size()).isEqualTo(1);
        // Verify WCT has a change for setting windowing mode to freeform
        Change change = wct.getChanges().get(displayAreaInfo.token.asBinder());
        assertThat(change).isNotNull();
        assertThat(change.getWindowingMode()).isEqualTo(WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testDesktopModeDisabled_rootTdaSetToFullscreen() {
        DisplayAreaInfo displayAreaInfo = createMockDisplayArea();

        mController.updateDesktopModeActive(false);
        WindowContainerTransaction wct = getDesktopModeSwitchTransaction();

        // 1 change: Root TDA windowing mode
        assertThat(wct.getChanges().size()).isEqualTo(1);
        // Verify WCT has a change for setting windowing mode to fullscreen
        Change change = wct.getChanges().get(displayAreaInfo.token.asBinder());
        assertThat(change).isNotNull();
        assertThat(change.getWindowingMode()).isEqualTo(WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testDesktopModeEnabled_windowingModeCleared() {
        createMockDisplayArea();
        RunningTaskInfo freeformTask = createFreeformTask();
        RunningTaskInfo fullscreenTask = createFullscreenTask();
        RunningTaskInfo homeTask = createHomeTask();
        when(mShellTaskOrganizer.getRunningTasks(anyInt())).thenReturn(new ArrayList<>(
                Arrays.asList(freeformTask, fullscreenTask, homeTask)));

        mController.updateDesktopModeActive(true);
        WindowContainerTransaction wct = getDesktopModeSwitchTransaction();

        // 2 changes: Root TDA windowing mode and 1 task
        assertThat(wct.getChanges().size()).isEqualTo(2);
        // No changes for tasks that are not standard or freeform
        assertThat(wct.getChanges().get(fullscreenTask.token.asBinder())).isNull();
        assertThat(wct.getChanges().get(homeTask.token.asBinder())).isNull();
        // Standard freeform task has windowing mode cleared
        Change change = wct.getChanges().get(freeformTask.token.asBinder());
        assertThat(change).isNotNull();
        assertThat(change.getWindowingMode()).isEqualTo(WINDOWING_MODE_UNDEFINED);
    }

    @Test
    public void testDesktopModeDisabled_windowingModeAndBoundsCleared() {
        createMockDisplayArea();
        RunningTaskInfo freeformTask = createFreeformTask();
        RunningTaskInfo fullscreenTask = createFullscreenTask();
        RunningTaskInfo homeTask = createHomeTask();
        when(mShellTaskOrganizer.getRunningTasks(anyInt())).thenReturn(new ArrayList<>(
                Arrays.asList(freeformTask, fullscreenTask, homeTask)));

        mController.updateDesktopModeActive(false);
        WindowContainerTransaction wct = getDesktopModeSwitchTransaction();

        // 3 changes: Root TDA windowing mode and 2 tasks
        assertThat(wct.getChanges().size()).isEqualTo(3);
        // No changes to home task
        assertThat(wct.getChanges().get(homeTask.token.asBinder())).isNull();
        // Standard tasks have bounds cleared
        assertThatBoundsCleared(wct.getChanges().get(freeformTask.token.asBinder()));
        assertThatBoundsCleared(wct.getChanges().get(fullscreenTask.token.asBinder()));
        // Freeform standard tasks have windowing mode cleared
        assertThat(wct.getChanges().get(
                freeformTask.token.asBinder()).getWindowingMode()).isEqualTo(
                WINDOWING_MODE_UNDEFINED);
    }

    @Test
    public void testDesktopModeEnabled_homeTaskBehindVisibleTask() {
        createMockDisplayArea();
        RunningTaskInfo fullscreenTask1 = createFullscreenTask();
        fullscreenTask1.isVisible = true;
        RunningTaskInfo fullscreenTask2 = createFullscreenTask();
        fullscreenTask2.isVisible = false;
        RunningTaskInfo homeTask = createHomeTask();
        when(mShellTaskOrganizer.getRunningTasks(anyInt())).thenReturn(new ArrayList<>(
                Arrays.asList(fullscreenTask1, fullscreenTask2, homeTask)));

        mController.updateDesktopModeActive(true);
        WindowContainerTransaction wct = getDesktopModeSwitchTransaction();

        // Check that there are hierarchy changes for home task and visible task
        assertThat(wct.getHierarchyOps()).hasSize(2);
        // First show home task
        HierarchyOp op1 = wct.getHierarchyOps().get(0);
        assertThat(op1.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op1.getContainer()).isEqualTo(homeTask.token.asBinder());

        // Then visible task on top of it
        HierarchyOp op2 = wct.getHierarchyOps().get(1);
        assertThat(op2.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op2.getContainer()).isEqualTo(fullscreenTask1.token.asBinder());
    }

    @Test
    public void testShowDesktopApps_allAppsInvisible_bringsToFront() {
        // Set up two active tasks on desktop, task2 is on top of task1.
        RunningTaskInfo freeformTask1 = createFreeformTask();
        mDesktopModeTaskRepository.addActiveTask(freeformTask1.taskId);
        mDesktopModeTaskRepository.addOrMoveFreeformTaskToTop(freeformTask1.taskId);
        mDesktopModeTaskRepository.updateVisibleFreeformTasks(
                freeformTask1.taskId, false /* visible */);
        RunningTaskInfo freeformTask2 = createFreeformTask();
        mDesktopModeTaskRepository.addActiveTask(freeformTask2.taskId);
        mDesktopModeTaskRepository.addOrMoveFreeformTaskToTop(freeformTask2.taskId);
        mDesktopModeTaskRepository.updateVisibleFreeformTasks(
                freeformTask2.taskId, false /* visible */);
        when(mShellTaskOrganizer.getRunningTaskInfo(freeformTask1.taskId)).thenReturn(
                freeformTask1);
        when(mShellTaskOrganizer.getRunningTaskInfo(freeformTask2.taskId)).thenReturn(
                freeformTask2);

        // Run show desktop apps logic
        mController.showDesktopApps();

        final WindowContainerTransaction wct = getBringAppsToFrontTransaction();
        // Check wct has reorder calls
        assertThat(wct.getHierarchyOps()).hasSize(2);

        // Task 1 appeared first, must be first reorder to top.
        HierarchyOp op1 = wct.getHierarchyOps().get(0);
        assertThat(op1.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op1.getContainer()).isEqualTo(freeformTask1.token.asBinder());

        // Task 2 appeared last, must be last reorder to top.
        HierarchyOp op2 = wct.getHierarchyOps().get(1);
        assertThat(op2.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op2.getContainer()).isEqualTo(freeformTask2.token.asBinder());
    }

    @Test
    public void testShowDesktopApps_appsAlreadyVisible_doesNothing() {
        final RunningTaskInfo task1 = createFreeformTask();
        mDesktopModeTaskRepository.addActiveTask(task1.taskId);
        mDesktopModeTaskRepository.addOrMoveFreeformTaskToTop(task1.taskId);
        mDesktopModeTaskRepository.updateVisibleFreeformTasks(task1.taskId, true /* visible */);
        when(mShellTaskOrganizer.getRunningTaskInfo(task1.taskId)).thenReturn(task1);
        final RunningTaskInfo task2 = createFreeformTask();
        mDesktopModeTaskRepository.addActiveTask(task2.taskId);
        mDesktopModeTaskRepository.addOrMoveFreeformTaskToTop(task2.taskId);
        mDesktopModeTaskRepository.updateVisibleFreeformTasks(task2.taskId, true /* visible */);
        when(mShellTaskOrganizer.getRunningTaskInfo(task2.taskId)).thenReturn(task2);

        mController.showDesktopApps();

        final WindowContainerTransaction wct = getBringAppsToFrontTransaction();
        // No reordering needed.
        assertThat(wct.getHierarchyOps()).isEmpty();
    }

    @Test
    public void testShowDesktopApps_someAppsInvisible_reordersAll() {
        final RunningTaskInfo task1 = createFreeformTask();
        mDesktopModeTaskRepository.addActiveTask(task1.taskId);
        mDesktopModeTaskRepository.addOrMoveFreeformTaskToTop(task1.taskId);
        mDesktopModeTaskRepository.updateVisibleFreeformTasks(task1.taskId, false /* visible */);
        when(mShellTaskOrganizer.getRunningTaskInfo(task1.taskId)).thenReturn(task1);
        final RunningTaskInfo task2 = createFreeformTask();
        mDesktopModeTaskRepository.addActiveTask(task2.taskId);
        mDesktopModeTaskRepository.addOrMoveFreeformTaskToTop(task2.taskId);
        mDesktopModeTaskRepository.updateVisibleFreeformTasks(task2.taskId, true /* visible */);
        when(mShellTaskOrganizer.getRunningTaskInfo(task2.taskId)).thenReturn(task2);

        mController.showDesktopApps();

        final WindowContainerTransaction wct = getBringAppsToFrontTransaction();
        // Both tasks should be reordered to top, even if one was already visible.
        assertThat(wct.getHierarchyOps()).hasSize(2);
        final HierarchyOp op1 = wct.getHierarchyOps().get(0);
        assertThat(op1.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op1.getContainer()).isEqualTo(task1.token.asBinder());
        final HierarchyOp op2 = wct.getHierarchyOps().get(1);
        assertThat(op2.getType()).isEqualTo(HIERARCHY_OP_TYPE_REORDER);
        assertThat(op2.getContainer()).isEqualTo(task2.token.asBinder());
    }

    @Test
    public void testHandleTransitionRequest_desktopModeNotActive_returnsNull() {
        when(DesktopModeStatus.isActive(any())).thenReturn(false);
        WindowContainerTransaction wct = mController.handleRequest(
                new Binder(),
                new TransitionRequestInfo(TRANSIT_OPEN, null /* trigger */, null /* remote */));
        assertThat(wct).isNull();
    }

    @Test
    public void testHandleTransitionRequest_unsupportedTransit_returnsNull() {
        WindowContainerTransaction wct = mController.handleRequest(
                new Binder(),
                new TransitionRequestInfo(TRANSIT_CLOSE, null /* trigger */, null /* remote */));
        assertThat(wct).isNull();
    }

    @Test
    public void testHandleTransitionRequest_notFreeform_returnsNull() {
        RunningTaskInfo trigger = new RunningTaskInfo();
        trigger.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        WindowContainerTransaction wct = mController.handleRequest(
                new Binder(),
                new TransitionRequestInfo(TRANSIT_TO_FRONT, trigger, null /* remote */));
        assertThat(wct).isNull();
    }

    @Test
    public void testHandleTransitionRequest_taskOpen_returnsWct() {
        RunningTaskInfo trigger = new RunningTaskInfo();
        trigger.token = new MockToken().token();
        trigger.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        WindowContainerTransaction wct = mController.handleRequest(
                mock(IBinder.class),
                new TransitionRequestInfo(TRANSIT_OPEN, trigger, null /* remote */));
        assertThat(wct).isNotNull();
    }

    @Test
    public void testHandleTransitionRequest_taskToFront_returnsWct() {
        RunningTaskInfo trigger = new RunningTaskInfo();
        trigger.token = new MockToken().token();
        trigger.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        WindowContainerTransaction wct = mController.handleRequest(
                mock(IBinder.class),
                new TransitionRequestInfo(TRANSIT_TO_FRONT, trigger, null /* remote */));
        assertThat(wct).isNotNull();
    }

    private DesktopModeController createController() {
        return new DesktopModeController(mContext, mShellInit, mShellController,
                mShellTaskOrganizer, mRootTaskDisplayAreaOrganizer, mTransitions,
                mDesktopModeTaskRepository, mMockHandler, new TestShellExecutor());
    }

    private DisplayAreaInfo createMockDisplayArea() {
        DisplayAreaInfo displayAreaInfo = new DisplayAreaInfo(new MockToken().token(),
                mContext.getDisplayId(), 0);
        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(mContext.getDisplayId()))
                .thenReturn(displayAreaInfo);
        return displayAreaInfo;
    }

    private WindowContainerTransaction getDesktopModeSwitchTransaction() {
        ArgumentCaptor<WindowContainerTransaction> arg = ArgumentCaptor.forClass(
                WindowContainerTransaction.class);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            verify(mTransitions).startTransition(eq(TRANSIT_CHANGE), arg.capture(), any());
        } else {
            verify(mRootTaskDisplayAreaOrganizer).applyTransaction(arg.capture());
        }
        return arg.getValue();
    }

    private WindowContainerTransaction getBringAppsToFrontTransaction() {
        final ArgumentCaptor<WindowContainerTransaction> arg = ArgumentCaptor.forClass(
                WindowContainerTransaction.class);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            verify(mTransitions).startTransition(eq(TRANSIT_TO_FRONT), arg.capture(), any());
        } else {
            verify(mShellTaskOrganizer).applyTransaction(arg.capture());
        }
        return arg.getValue();
    }

    private void assertThatBoundsCleared(Change change) {
        assertThat((change.getWindowSetMask() & WINDOW_CONFIG_BOUNDS) != 0).isTrue();
        assertThat(change.getConfiguration().windowConfiguration.getBounds().isEmpty()).isTrue();
    }

}
