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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.ITaskOrganizer;
import android.view.IWindowContainer;
import android.view.SurfaceControl;
import android.view.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link ITaskOrganizer} and {@link android.app.ITaskOrganizerController}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskOrganizerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskOrganizerTests extends WindowTestsBase {
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

    @Test
    public void testAppearVanish() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        task.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any());

        task.removeImmediately();
        verify(organizer).taskVanished(any());
    }

    @Test
    public void testSwapOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_PINNED);

        task.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any());
        task.setTaskOrganizer(organizer2);
        verify(organizer).taskVanished(any());
        verify(organizer2).taskAppeared(any());
    }

    @Test
    public void testSwapWindowingModes() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_PINNED);
 
        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer).taskAppeared(any());
        stack.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer).taskVanished(any());
        verify(organizer2).taskAppeared(any());
    }

    @Test
    public void testClearOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        stack.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any());
        assertTrue(stack.isControlledByTaskOrganizer());

        stack.setTaskOrganizer(null);
        verify(organizer).taskVanished(any());
        assertFalse(stack.isControlledByTaskOrganizer());
    }

    @Test
    public void testRegisterTaskOrganizerStackWindowingModeChanges() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_PINNED);

        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final Task task2 = createTaskInStack(stack, 0 /* userId */);
        stack.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer, times(1)).taskAppeared(any());

        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        verify(organizer, times(1)).taskVanished(any());
    }

    @Test
    public void testTaskTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(task.mRemoteToken, new Rect(10, 10, 100, 100));
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t);
        assertEquals(newBounds, task.getBounds());
    }

    @Test
    public void testStackTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        StackInfo info =
                mWm.mAtmService.getStackInfo(WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertEquals(stack.mRemoteToken, info.stackToken);
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(info.stackToken, new Rect(10, 10, 100, 100));
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t);
        assertEquals(newBounds, stack.getBounds());
    }

    @Test
    public void testContainerChanges() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertTrue(task.isFocusable());
        t.setFocusable(stack.mRemoteToken, false);
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t);
        assertFalse(task.isFocusable());
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
        List<TaskTile> infos = getTaskTiles(dc);
        assertEquals(2, infos.size());

        assertTrue(mWm.mAtmService.mTaskOrganizerController.deleteRootTask(info1.token));
        infos = getTaskTiles(dc);
        assertEquals(1, infos.size());
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, infos.get(0).getWindowingMode());
    }

    @Test
    public void testTileAddRemoveChild() {
        RunningTaskInfo info1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent.mDisplayId, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);

        final ActivityStack stack = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        assertEquals(mDisplayContent.getWindowingMode(), stack.getWindowingMode());
        TaskTile tile1 = TaskTile.forToken(info1.token.asBinder());
        tile1.addChild(stack, 0 /* index */);
        assertEquals(info1.configuration.windowConfiguration.getWindowingMode(),
                stack.getWindowingMode());

        // Info should reflect new membership
        List<TaskTile> tiles = getTaskTiles(mDisplayContent);
        info1 = new RunningTaskInfo();
        tiles.get(0).fillTaskInfo(info1);
        assertEquals(ACTIVITY_TYPE_STANDARD, info1.topActivityType);

        // Children inherit configuration
        Rect newSize = new Rect(10, 10, 300, 300);
        Configuration c = new Configuration(tile1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(newSize);
        tile1.onRequestedOverrideConfigurationChanged(c);
        assertEquals(newSize, stack.getBounds());

        tile1.removeChild(stack);
        assertEquals(mDisplayContent.getWindowingMode(), stack.getWindowingMode());
        info1 = new RunningTaskInfo();
        tiles = getTaskTiles(mDisplayContent);
        tiles.get(0).fillTaskInfo(info1);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info1.topActivityType);
    }

    @Test
    public void testTaskInfoCallback() {
        final ArrayList<RunningTaskInfo> lastReportedTiles = new ArrayList<>();
        final boolean[] called = {false};
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void taskAppeared(RunningTaskInfo taskInfo) { }

            @Override
            public void taskVanished(IWindowContainer container) { }

            @Override
            public void transactionReady(int id, SurfaceControl.Transaction t) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) throws RemoteException {
                lastReportedTiles.add(info);
                called[0] = true;
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
        TaskTile tile1 = TaskTile.forToken(info1.token.asBinder());
        tile1.addChild(stack, 0 /* index */);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        final ActivityStack stack2 = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, mDisplayContent);
        tile1.addChild(stack2, 0 /* index */);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_HOME, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        mDisplayContent.positionStackAtTop(stack, false /* includingParents */);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        tile1.removeAllChildren();
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, lastReportedTiles.get(0).topActivityType);
    }

    private List<TaskTile> getTaskTiles(DisplayContent dc) {
        ArrayList<TaskTile> out = new ArrayList<>();
        for (int i = dc.getStackCount() - 1; i >= 0; --i) {
            final Task t = dc.getStackAt(i);
            if (t instanceof TaskTile) {
                out.add((TaskTile) t);
            }
        }
        return out;
    }
}
