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
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackInfo;
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
    public void testUnregisterOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer).taskAppeared(any());
        assertTrue(stack.isControlledByTaskOrganizer());

        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        verify(organizer).taskVanished(any());
        assertFalse(stack.isControlledByTaskOrganizer());
    }

    @Test
    public void testUnregisterOrganizerReturnsRegistrationToPrevious() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ActivityStack stack2 = createTaskStackOnDisplay(mDisplayContent);
        final Task task2 = createTaskInStack(stack2, 0 /* userId */);
        final ActivityStack stack3 = createTaskStackOnDisplay(mDisplayContent);
        final Task task3 = createTaskInStack(stack3, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);

        // First organizer is registered, verify a task appears when changing windowing mode
        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer, times(1)).taskAppeared(any());
        assertTrue(stack.isControlledByTaskOrganizer());

        // Now we replace the registration and1 verify the new organizer receives tasks
        // newly entering the windowing mode.
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        stack2.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer2).taskAppeared(any());
        assertTrue(stack2.isControlledByTaskOrganizer());

        // Now we unregister the second one, the first one should automatically be reregistered
        // so we verify that it's now seeing changes.
        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer2);

        stack3.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer, times(2)).taskAppeared(any());
        assertTrue(stack3.isControlledByTaskOrganizer());
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
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t, null);
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
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t, null);
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
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t, null);
        assertFalse(task.isFocusable());
    }

    @Test
    public void testOverrideConfigSize() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        t.setBounds(task.mRemoteToken, new Rect(10, 10, 100, 100));
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t, null);
        final int origScreenWDp = task.getConfiguration().screenHeightDp;
        final int origScreenHDp = task.getConfiguration().screenHeightDp;
        t = new WindowContainerTransaction();
        // verify that setting config overrides on parent restricts children.
        t.setScreenSizeDp(stack.mRemoteToken, origScreenWDp, origScreenHDp);
        t.setBounds(task.mRemoteToken, new Rect(10, 10, 150, 200));
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t, null);
        assertEquals(origScreenHDp, task.getConfiguration().screenHeightDp);
        t = new WindowContainerTransaction();
        t.setScreenSizeDp(stack.mRemoteToken, Configuration.SCREEN_WIDTH_DP_UNDEFINED,
                Configuration.SCREEN_HEIGHT_DP_UNDEFINED);
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(t, null);
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
        info1 = tiles.get(0).getTaskInfo();
        assertEquals(ACTIVITY_TYPE_STANDARD, info1.topActivityType);

        // Children inherit configuration
        Rect newSize = new Rect(10, 10, 300, 300);
        Configuration c = new Configuration(tile1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(newSize);
        doNothing().when(stack).adjustForMinimalTaskDimensions(any(), any());
        tile1.onRequestedOverrideConfigurationChanged(c);
        assertEquals(newSize, stack.getBounds());

        tile1.removeChild(stack);
        assertEquals(mDisplayContent.getWindowingMode(), stack.getWindowingMode());
        tiles = getTaskTiles(mDisplayContent);
        info1 = tiles.get(0).getTaskInfo();
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
            public void taskVanished(RunningTaskInfo container) { }

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

    @Test
    public void testHierarchyTransaction() {
        final ArrayMap<IBinder, RunningTaskInfo> lastReportedTiles = new ArrayMap<>();
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void taskAppeared(RunningTaskInfo taskInfo) { }

            @Override
            public void taskVanished(RunningTaskInfo container) { }

            @Override
            public void transactionReady(int id, SurfaceControl.Transaction t) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                lastReportedTiles.put(info.token.asBinder(), info);
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(
                listener, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        RunningTaskInfo info1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent.mDisplayId, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
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
        wct.reparent(stack.mRemoteToken, info1.token, true /* onTop */);
        wct.reparent(stack2.mRemoteToken, info2.token, true /* onTop */);
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(wct,
                null /* organizer */);
        assertFalse(lastReportedTiles.isEmpty());
        assertEquals(ACTIVITY_TYPE_STANDARD,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info2.token.asBinder()).topActivityType);

        lastReportedTiles.clear();
        wct = new WindowContainerTransaction();
        wct.reparent(stack2.mRemoteToken, info1.token, false /* onTop */);
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(wct,
                null /* organizer */);
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
        wct.reorder(stack2.mRemoteToken, true /* onTop */);
        mWm.mAtmService.mTaskOrganizerController.applyContainerTransaction(wct,
                null /* organizer */);
        // Home should now be on top. No change occurs in second tile, so not reported
        assertEquals(1, lastReportedTiles.size());
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);
    }

    private List<TaskTile> getTaskTiles(DisplayContent dc) {
        ArrayList<TaskTile> out = new ArrayList<>();
        for (int i = dc.getStackCount() - 1; i >= 0; --i) {
            final TaskTile t = dc.getStackAt(i).asTile();
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    @Test
    public void testTrivialBLASTCallback() throws RemoteException {
        final ActivityStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stackController1, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        BLASTSyncEngine bse = new BLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener transactionListener =
            mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        bse.addToSyncSet(id, task);
        bse.setReady(id);
        // Since this task has no windows the sync is trivial and completes immediately.
        verify(transactionListener)
            .transactionReady(anyInt(), any());
    }

    @Test
    public void testBLASTCallbackWithWindow() {
        final ActivityStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stackController1, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer();
        final WindowState w = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window");

        BLASTSyncEngine bse = new BLASTSyncEngine();

        BLASTSyncEngine.TransactionReadyListener transactionListener =
            mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        bse.addToSyncSet(id, task);
        bse.setReady(id);
        // Since we have a window we have to wait for it to draw to finish sync.
        verify(transactionListener, never())
            .transactionReady(anyInt(), any());
        w.finishDrawing(null);
        verify(transactionListener)
            .transactionReady(anyInt(), any());
    }

    class StubOrganizer extends ITaskOrganizer.Stub {
        RunningTaskInfo mInfo;

        @Override
        public void taskAppeared(RunningTaskInfo info) {
            mInfo = info;
        }
        @Override
        public void taskVanished(RunningTaskInfo info) {
        }
        @Override
        public void transactionReady(int id, SurfaceControl.Transaction t) {
        }
        @Override
        public void onTaskInfoChanged(RunningTaskInfo info) {
        }
    };

    private ActivityRecord makePipableActivity() {
        final ActivityRecord record = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        record.info.flags |= ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
        spyOn(record);
        doReturn(true).when(record).checkEnterPictureInPictureState(any(), anyBoolean());
        return record;
    }

    @Test
    public void testEnterPipParams() {
        final StubOrganizer o = new StubOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o, WINDOWING_MODE_PINNED);
        final ActivityRecord record = makePipableActivity();

        final PictureInPictureParams p =
            new PictureInPictureParams.Builder().setAspectRatio(new Rational(1, 2)).build();
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
        final PictureInPictureParams p =
            new PictureInPictureParams.Builder().setAspectRatio(new Rational(1, 2)).build();
        assertTrue(mWm.mAtmService.enterPictureInPictureMode(record.token, p));
        waitUntilHandlersIdle();
        assertNotNull(o.mInfo);
        assertNotNull(o.mInfo.pictureInPictureParams);

        final PictureInPictureParams p2 =
            new PictureInPictureParams.Builder().setAspectRatio(new Rational(3, 4)).build();
        mWm.mAtmService.setPictureInPictureParams(record.token, p2);
        waitUntilHandlersIdle();
        assertNotNull(o.mChangedInfo);
        assertNotNull(o.mChangedInfo.pictureInPictureParams);
        final Rational ratio = o.mChangedInfo.pictureInPictureParams.getAspectRatioRational();
        assertEquals(3, ratio.getNumerator());
        assertEquals(4, ratio.getDenominator());
    }
}
