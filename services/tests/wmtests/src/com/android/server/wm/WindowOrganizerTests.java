/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.DisplayArea.Type.ABOVE_TASKS;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackInfo;
import android.app.IRequestFinishCallback;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.Rational;
import android.view.Display;
import android.window.ITaskOrganizer;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link ITaskOrganizer} and {@link android.window.ITaskOrganizerController}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowOrganizerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowOrganizerTests extends WindowTestsBase {
    private ITaskOrganizer registerMockOrganizer(int windowingMode) {
        final ITaskOrganizer organizer = mock(ITaskOrganizer.class);
        when(organizer.asBinder()).thenReturn(new Binder());

        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(
                organizer, windowingMode);

        return organizer;
    }

    private ITaskOrganizer registerMockOrganizer() {
        return registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
    }

    Task createTask(ActivityStack stack, boolean fakeDraw) {
        final Task task = createTaskInStack(stack, 0);

        if (fakeDraw) {
            task.setHasBeenVisible(true);
        }
        return task;
    }

    Task createTask(ActivityStack stack) {
        // Fake draw notifications for most of our tests.
        return createTask(stack, true);
    }

    ActivityStack createStack() {
        return createTaskStackOnDisplay(mDisplayContent);
    }

    @Before
    public void setUp() {
        // We defer callbacks since we need to adjust task surface visibility, but for these tests,
        // just run the callbacks synchronously
        mWm.mAtmService.mTaskOrganizerController.setDeferTaskOrgCallbacksConsumer((r) -> r.run());
    }

    @Test
    public void testAppearVanish() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ITaskOrganizer organizer = registerMockOrganizer();

        task.setTaskOrganizer(organizer);
        verify(organizer).onTaskAppeared(any());

        task.removeImmediately();
        verify(organizer).onTaskVanished(any());
    }

    @Test
    public void testAppearWaitsForVisibility() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack, false);
        final ITaskOrganizer organizer = registerMockOrganizer();

        task.setTaskOrganizer(organizer);

        verify(organizer, never()).onTaskAppeared(any());
        task.setHasBeenVisible(true);
        assertTrue(stack.getHasBeenVisible());

        verify(organizer).onTaskAppeared(any());

        task.removeImmediately();
        verify(organizer).onTaskVanished(any());
    }

    @Test
    public void testNoVanishedIfNoAppear() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack, false /* hasBeenVisible */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        // In this test we skip making the Task visible, and verify
        // that even though a TaskOrganizer is set remove doesn't emit
        // a vanish callback, because we never emitted appear.
        task.setTaskOrganizer(organizer);
        verify(organizer, never()).onTaskAppeared(any());
        task.removeImmediately();
        verify(organizer, never()).onTaskVanished(any());
    }

    @Test
    public void testSwapOrganizer() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_PINNED);

        task.setTaskOrganizer(organizer);
        verify(organizer).onTaskAppeared(any());
        task.setTaskOrganizer(organizer2);
        verify(organizer).onTaskVanished(any());
        verify(organizer2).onTaskAppeared(any());
    }

    @Test
    public void testSwapWindowingModes() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_PINNED);

        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer).onTaskAppeared(any());
        stack.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer).onTaskVanished(any());
        verify(organizer2).onTaskAppeared(any());
    }

    @Test
    public void testTaskNoDraw() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack, false /* fakeDraw */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer, never()).onTaskAppeared(any());
        assertTrue(stack.isOrganized());

        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        verify(organizer, never()).onTaskVanished(any());
        assertFalse(stack.isOrganized());
    }

    @Test
    public void testClearOrganizer() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ITaskOrganizer organizer = registerMockOrganizer();

        stack.setTaskOrganizer(organizer);
        verify(organizer).onTaskAppeared(any());
        assertTrue(stack.isOrganized());

        stack.setTaskOrganizer(null);
        verify(organizer).onTaskVanished(any());
        assertFalse(stack.isOrganized());
    }

    @Test
    public void testUnregisterOrganizer() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ITaskOrganizer organizer = registerMockOrganizer();

        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer).onTaskAppeared(any());
        assertTrue(stack.isOrganized());

        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        verify(organizer).onTaskVanished(any());
        assertFalse(stack.isOrganized());
    }

    @Test
    public void testUnregisterOrganizerReturnsRegistrationToPrevious() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ActivityStack stack2 = createStack();
        final Task task2 = createTask(stack2);
        final ActivityStack stack3 = createStack();
        final Task task3 = createTask(stack3);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);

        // First organizer is registered, verify a task appears when changing windowing mode
        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer, times(1)).onTaskAppeared(any());
        assertTrue(stack.isOrganized());

        // Now we replace the registration and1 verify the new organizer receives tasks
        // newly entering the windowing mode.
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        stack2.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        // One each for task and task2
        verify(organizer2, times(2)).onTaskAppeared(any());
        verify(organizer2, times(0)).onTaskVanished(any());
        // One for task
        verify(organizer).onTaskVanished(any());
        assertTrue(stack2.isOrganized());

        // Now we unregister the second one, the first one should automatically be reregistered
        // so we verify that it's now seeing changes.
        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer2);
        verify(organizer, times(3)).onTaskAppeared(any());
        verify(organizer2, times(2)).onTaskVanished(any());

        stack3.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer, times(4)).onTaskAppeared(any());
        verify(organizer2, times(2)).onTaskVanished(any());
        assertTrue(stack3.isOrganized());
    }

    @Test
    public void testRegisterTaskOrganizerStackWindowingModeChanges() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_PINNED);

        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final Task task2 = createTask(stack);
        stack.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer, times(1)).onTaskAppeared(any());

        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        verify(organizer, times(1)).onTaskVanished(any());
    }

    @Test
    public void testRegisterTaskOrganizerWithExistingTasks() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        stack.setWindowingMode(WINDOWING_MODE_PINNED);

        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_PINNED);
        verify(organizer, times(1)).onTaskAppeared(any());
    }

    @Test
    public void testTaskTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        testTransaction(task);
    }

    @Test
    public void testStackTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        StackInfo info =
                mWm.mAtmService.getStackInfo(WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        assertEquals(stack.mRemoteToken.toWindowContainerToken(), info.stackToken);
        testTransaction(stack);
    }

    @Test
    public void testDisplayAreaTransaction() {
        removeGlobalMinSizeRestriction();
        final DisplayArea displayArea = new DisplayArea<>(mWm, ABOVE_TASKS, "DisplayArea");
        testTransaction(displayArea);
    }

    private void testTransaction(WindowContainer wc) {
        WindowContainerTransaction t = new WindowContainerTransaction();
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(wc.mRemoteToken.toWindowContainerToken(), new Rect(10, 10, 100, 100));
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(newBounds, wc.getBounds());
    }

    @Test
    public void testSetWindowingMode() {
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        testSetWindowingMode(stack);

        final DisplayArea displayArea = new DisplayArea<>(mWm, ABOVE_TASKS, "DisplayArea");
        displayArea.setWindowingMode(WINDOWING_MODE_FREEFORM);
        testSetWindowingMode(displayArea);
    }

    private void testSetWindowingMode(WindowContainer wc) {
        final WindowContainerTransaction t = new WindowContainerTransaction();
        t.setWindowingMode(wc.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_FULLSCREEN);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(WINDOWING_MODE_FULLSCREEN, wc.getWindowingMode());
    }

    @Test
    public void testSetActivityWindowingMode() {
        final ActivityRecord record = makePipableActivity();
        final ActivityStack stack = record.getStack();
        final WindowContainerTransaction t = new WindowContainerTransaction();

        t.setWindowingMode(stack.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_PINNED);
        t.setActivityWindowingMode(
                stack.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_FULLSCREEN);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        assertEquals(WINDOWING_MODE_FULLSCREEN, record.getWindowingMode());
        assertEquals(WINDOWING_MODE_PINNED, stack.getWindowingMode());
    }

    @Test
    public void testContainerFocusableChanges() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertTrue(task.isFocusable());
        t.setFocusable(stack.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertFalse(task.isFocusable());
        t.setFocusable(stack.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertTrue(task.isFocusable());
    }

    @Test
    public void testContainerHiddenChanges() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertTrue(stack.shouldBeVisible(null));
        t.setHidden(stack.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertFalse(stack.shouldBeVisible(null));
        t.setHidden(stack.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertTrue(stack.shouldBeVisible(null));
    }

    @Test
    public void testOverrideConfigSize() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        t.setBounds(task.mRemoteToken.toWindowContainerToken(), new Rect(10, 10, 100, 100));
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        final int origScreenWDp = task.getConfiguration().screenHeightDp;
        final int origScreenHDp = task.getConfiguration().screenHeightDp;
        t = new WindowContainerTransaction();
        // verify that setting config overrides on parent restricts children.
        t.setScreenSizeDp(stack.mRemoteToken
                .toWindowContainerToken(), origScreenWDp, origScreenHDp);
        t.setBounds(task.mRemoteToken.toWindowContainerToken(), new Rect(10, 10, 150, 200));
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(origScreenHDp, task.getConfiguration().screenHeightDp);
        t = new WindowContainerTransaction();
        t.setScreenSizeDp(stack.mRemoteToken.toWindowContainerToken(), SCREEN_WIDTH_DP_UNDEFINED,
                SCREEN_HEIGHT_DP_UNDEFINED);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertNotEquals(origScreenHDp, task.getConfiguration().screenHeightDp);
    }

    @Test
    public void testCreateDeleteRootTasks() {
        RunningTaskInfo info1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                Display.DEFAULT_DISPLAY,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                info1.configuration.windowConfiguration.getWindowingMode());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info1.topActivityType);

        RunningTaskInfo info2 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                Display.DEFAULT_DISPLAY,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                info2.configuration.windowConfiguration.getWindowingMode());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info2.topActivityType);

        DisplayContent dc = mWm.mRoot.getDisplayContent(Display.DEFAULT_DISPLAY);
        List<Task> infos = getTasksCreatedByOrganizer(dc);
        assertEquals(2, infos.size());

        assertTrue(mWm.mAtmService.mTaskOrganizerController.deleteRootTask(info1.token));
        infos = getTasksCreatedByOrganizer(dc);
        assertEquals(1, infos.size());
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, infos.get(0).getWindowingMode());
    }

    @Test
    public void testTileAddRemoveChild() {
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void onTaskAppeared(RunningTaskInfo taskInfo) { }

            @Override
            public void onTaskVanished(RunningTaskInfo container) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) throws RemoteException {
            }

            @Override
            public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        RunningTaskInfo info1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent.mDisplayId, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);

        final ActivityStack stack = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        assertEquals(mDisplayContent.getWindowingMode(), stack.getWindowingMode());
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(info1.configuration.windowConfiguration.getWindowingMode(),
                stack.getWindowingMode());

        // Info should reflect new membership
        List<Task> infos = getTasksCreatedByOrganizer(mDisplayContent);
        info1 = infos.get(0).getTaskInfo();
        assertEquals(ACTIVITY_TYPE_STANDARD, info1.topActivityType);

        // Children inherit configuration
        Rect newSize = new Rect(10, 10, 300, 300);
        Task task1 = WindowContainer.fromBinder(info1.token.asBinder()).asTask();
        Configuration c = new Configuration(task1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(newSize);
        doNothing().when(stack).adjustForMinimalTaskDimensions(any(), any());
        task1.onRequestedOverrideConfigurationChanged(c);
        assertEquals(newSize, stack.getBounds());

        wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(mDisplayContent.getWindowingMode(), stack.getWindowingMode());
        infos = getTasksCreatedByOrganizer(mDisplayContent);
        info1 = infos.get(0).getTaskInfo();
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info1.topActivityType);
    }

    @Test
    public void testTaskInfoCallback() {
        final ArrayList<RunningTaskInfo> lastReportedTiles = new ArrayList<>();
        final boolean[] called = {false};
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void onTaskAppeared(RunningTaskInfo taskInfo) { }

            @Override
            public void onTaskVanished(RunningTaskInfo container) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) throws RemoteException {
                lastReportedTiles.add(info);
                called[0] = true;
            }

            @Override
            public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        RunningTaskInfo info1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent.mDisplayId, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        lastReportedTiles.clear();
        called[0] = false;

        final ActivityStack stack = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        Task task1 = WindowContainer.fromBinder(info1.token.asBinder()).asTask();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        final ActivityStack stack2 = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, mDisplayContent);
        wct = new WindowContainerTransaction();
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_HOME, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        task1.positionChildAt(POSITION_TOP, stack, false /* includingParents */);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, lastReportedTiles.get(0).topActivityType);
    }

    @Test
    public void testHierarchyTransaction() {
        final ArrayMap<IBinder, RunningTaskInfo> lastReportedTiles = new ArrayMap<>();
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void onTaskAppeared(RunningTaskInfo taskInfo) { }

            @Override
            public void onTaskVanished(RunningTaskInfo container) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                lastReportedTiles.put(info.token.asBinder(), info);
            }

            @Override
            public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(
                listener, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(
                listener, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        RunningTaskInfo info1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent.mDisplayId, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        RunningTaskInfo info2 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent.mDisplayId, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);

        final int initialRootTaskCount = mWm.mAtmService.mTaskOrganizerController.getRootTasks(
                mDisplayContent.mDisplayId, null /* activityTypes */).size();

        final ActivityStack stack = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final ActivityStack stack2 = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, mDisplayContent);

        // Check getRootTasks works
        List<RunningTaskInfo> roots = mWm.mAtmService.mTaskOrganizerController.getRootTasks(
                mDisplayContent.mDisplayId, null /* activityTypes */);
        assertEquals(initialRootTaskCount + 2, roots.size());

        lastReportedTiles.clear();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), info2.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertFalse(lastReportedTiles.isEmpty());
        assertEquals(ACTIVITY_TYPE_STANDARD,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info2.token.asBinder()).topActivityType);

        lastReportedTiles.clear();
        wct = new WindowContainerTransaction();
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), info1.token, false /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertFalse(lastReportedTiles.isEmpty());
        // Standard should still be on top of tile 1, so no change there
        assertFalse(lastReportedTiles.containsKey(info1.token.asBinder()));
        // But tile 2 has no children, so should become undefined
        assertEquals(ACTIVITY_TYPE_UNDEFINED,
                lastReportedTiles.get(info2.token.asBinder()).topActivityType);

        // Check the getChildren call
        List<RunningTaskInfo> children =
                mWm.mAtmService.mTaskOrganizerController.getChildTasks(info1.token,
                        null /* activityTypes */);
        assertEquals(2, children.size());
        children = mWm.mAtmService.mTaskOrganizerController.getChildTasks(info2.token,
                null /* activityTypes */);
        assertEquals(0, children.size());

        // Check that getRootTasks doesn't include children of tiles
        roots = mWm.mAtmService.mTaskOrganizerController.getRootTasks(mDisplayContent.mDisplayId,
                null /* activityTypes */);
        assertEquals(initialRootTaskCount, roots.size());

        lastReportedTiles.clear();
        wct = new WindowContainerTransaction();
        wct.reorder(stack2.mRemoteToken.toWindowContainerToken(), true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        // Home should now be on top. No change occurs in second tile, so not reported
        assertEquals(1, lastReportedTiles.size());
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);
    }

    private List<Task> getTasksCreatedByOrganizer(DisplayContent dc) {
        ArrayList<Task> out = new ArrayList<>();
        for (int tdaNdx = dc.getTaskDisplayAreaCount() - 1; tdaNdx >= 0; --tdaNdx) {
            final TaskDisplayArea taskDisplayArea = dc.getTaskDisplayAreaAt(tdaNdx);
            for (int sNdx = taskDisplayArea.getStackCount() - 1; sNdx >= 0; --sNdx) {
                final Task t = taskDisplayArea.getStackAt(sNdx);
                if (t.mCreatedByOrganizer) out.add(t);
            }
        }
        return out;
    }

    @Test
    public void testTrivialBLASTCallback() throws RemoteException {
        final ActivityStack stackController1 = createStack();
        final Task task = createTask(stackController1);
        final ITaskOrganizer organizer = registerMockOrganizer();

        spyOn(task);
        doReturn(true).when(task).isVisible();

        BLASTSyncEngine bse = new BLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener transactionListener =
                mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        bse.addToSyncSet(id, task);
        bse.setReady(id);
        // Since this task has no windows the sync is trivial and completes immediately.
        verify(transactionListener)
            .onTransactionReady(anyInt(), any());
    }

    @Test
    public void testOverlappingBLASTCallback() throws RemoteException {
        final ActivityStack stackController1 = createStack();
        final Task task = createTask(stackController1);
        final ITaskOrganizer organizer = registerMockOrganizer();

        spyOn(task);
        doReturn(true).when(task).isVisible();
        final WindowState w = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window");
        makeWindowVisible(w);

        BLASTSyncEngine bse = new BLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener transactionListener =
                mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        assertEquals(true, bse.addToSyncSet(id, task));
        bse.setReady(id);

        int id2 = bse.startSyncSet(transactionListener);
        // We should be rejected from the second sync since we are already
        // in one.
        assertEquals(false, bse.addToSyncSet(id2, task));
        w.finishDrawing(null);
        assertEquals(true, bse.addToSyncSet(id2, task));
        bse.setReady(id2);
    }

    @Test
    public void testBLASTCallbackWithWindow() {
        final ActivityStack stackController1 = createStack();
        final Task task = createTask(stackController1);
        final ITaskOrganizer organizer = registerMockOrganizer();
        final WindowState w = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window");
        makeWindowVisible(w);

        BLASTSyncEngine bse = new BLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener transactionListener =
                mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        bse.addToSyncSet(id, task);
        bse.setReady(id);
        // Since we have a window we have to wait for it to draw to finish sync.
        verify(transactionListener, never())
            .onTransactionReady(anyInt(), any());
        w.finishDrawing(null);
        verify(transactionListener)
            .onTransactionReady(anyInt(), any());
    }

    @Test
    public void testBLASTCallbackWithInvisibleWindow() {
        final ActivityStack stackController1 = createStack();
        final Task task = createTask(stackController1);
        final ITaskOrganizer organizer = registerMockOrganizer();
        final WindowState w = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window");

        BLASTSyncEngine bse = new BLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener transactionListener =
                mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        bse.addToSyncSet(id, task);
        bse.setReady(id);

        // Since the window was invisible, the Task had no visible leaves and the sync should
        // complete as soon as we call setReady.
        verify(transactionListener)
            .onTransactionReady(anyInt(), any());
    }

    @Test
    public void testBLASTCallbackWithChildWindow() {
        final ActivityStack stackController1 = createStack();
        final Task task = createTask(stackController1);
        final ITaskOrganizer organizer = registerMockOrganizer();
        final WindowState w = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window");
        final WindowState child = createWindow(w, TYPE_APPLICATION, "Other Window");

        w.mActivityRecord.setVisible(true);
        makeWindowVisible(w, child);

        BLASTSyncEngine bse = new BLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener transactionListener =
                mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        assertEquals(true, bse.addToSyncSet(id, task));
        bse.setReady(id);
        w.finishDrawing(null);

        // Since we have a child window we still shouldn't be done.
        verify(transactionListener, never())
            .onTransactionReady(anyInt(), any());
        reset(transactionListener);

        child.finishDrawing(null);
        // Ah finally! Done
        verify(transactionListener)
                .onTransactionReady(anyInt(), any());
    }

    class StubOrganizer extends ITaskOrganizer.Stub {
        RunningTaskInfo mInfo;

        @Override
        public void onTaskAppeared(RunningTaskInfo info) {
            mInfo = info;
        }
        @Override
        public void onTaskVanished(RunningTaskInfo info) {
        }
        @Override
        public void onTaskInfoChanged(RunningTaskInfo info) {
        }
        @Override
        public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
        }
    };

    private ActivityRecord makePipableActivity() {
        final ActivityRecord record = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        record.info.flags |= ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
        spyOn(record);
        doReturn(true).when(record).checkEnterPictureInPictureState(any(), anyBoolean());

        record.getRootTask().setHasBeenVisible(true);
        return record;
    }

    @Test
    public void testEnterPipParams() {
        final StubOrganizer o = new StubOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o, WINDOWING_MODE_PINNED);
        final ActivityRecord record = makePipableActivity();

        final PictureInPictureParams p = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 2)).build();
        assertTrue(mWm.mAtmService.enterPictureInPictureMode(record.token, p));
        waitUntilHandlersIdle();
        assertNotNull(o.mInfo);
        assertNotNull(o.mInfo.pictureInPictureParams);
    }

    @Test
    public void testChangePipParams() {
        class ChangeSavingOrganizer extends StubOrganizer {
            RunningTaskInfo mChangedInfo;
            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                mChangedInfo = info;
            }
        }
        ChangeSavingOrganizer o = new ChangeSavingOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o, WINDOWING_MODE_PINNED);

        final ActivityRecord record = makePipableActivity();
        final PictureInPictureParams p = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 2)).build();
        assertTrue(mWm.mAtmService.enterPictureInPictureMode(record.token, p));
        waitUntilHandlersIdle();
        assertNotNull(o.mInfo);
        assertNotNull(o.mInfo.pictureInPictureParams);

        final PictureInPictureParams p2 = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(3, 4)).build();
        mWm.mAtmService.setPictureInPictureParams(record.token, p2);
        waitUntilHandlersIdle();
        assertNotNull(o.mChangedInfo);
        assertNotNull(o.mChangedInfo.pictureInPictureParams);
        final Rational ratio = o.mChangedInfo.pictureInPictureParams.getAspectRatioRational();
        assertEquals(3, ratio.getNumerator());
        assertEquals(4, ratio.getDenominator());
    }

    @Test
    public void testChangeTaskDescription() {
        class ChangeSavingOrganizer extends StubOrganizer {
            RunningTaskInfo mChangedInfo;
            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                mChangedInfo = info;
            }
        }
        ChangeSavingOrganizer o = new ChangeSavingOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o,
                WINDOWING_MODE_MULTI_WINDOW);

        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ActivityRecord record = WindowTestUtils.createActivityRecordInTask(
                stack.mDisplayContent, task);

        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        record.setTaskDescription(new ActivityManager.TaskDescription("TestDescription"));
        waitUntilHandlersIdle();
        assertEquals("TestDescription", o.mChangedInfo.taskDescription.getLabel());
    }

    @Test
    public void testPreventDuplicateAppear() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ITaskOrganizer organizer = registerMockOrganizer();

        task.setTaskOrganizer(organizer);
        // setHasBeenVisible was already called once by the set-up code.
        task.setHasBeenVisible(true);
        verify(organizer, times(1)).onTaskAppeared(any());

        task.setTaskOrganizer(null);
        verify(organizer, times(1)).onTaskVanished(any());
        task.setTaskOrganizer(organizer);
        verify(organizer, times(2)).onTaskAppeared(any());

        task.removeImmediately();
        verify(organizer, times(2)).onTaskVanished(any());
    }

    @Test
    public void testInterceptBackPressedOnTaskRoot() throws RemoteException {
        final ActivityStack stack = createStack();
        final Task task = createTask(stack);
        final ActivityRecord activity = WindowTestUtils.createActivityRecordInTask(
                stack.mDisplayContent, task);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);

        // Setup the task to be controlled by the MW mode organizer
        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertTrue(stack.isOrganized());

        // Verify a back pressed does not call the organizer
        mWm.mAtmService.onBackPressedOnTaskRoot(activity.token,
                new IRequestFinishCallback.Default());
        verify(organizer, never()).onBackPressedOnTaskRoot(any());

        // Enable intercepting back
        mWm.mAtmService.mTaskOrganizerController.setInterceptBackPressedOnTaskRoot(organizer,
                true);

        // Verify now that the back press does call the organizer
        mWm.mAtmService.onBackPressedOnTaskRoot(activity.token,
                new IRequestFinishCallback.Default());
        verify(organizer, times(1)).onBackPressedOnTaskRoot(any());
    }
}
