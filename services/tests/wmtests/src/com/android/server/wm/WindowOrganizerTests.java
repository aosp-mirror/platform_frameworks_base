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

import static android.app.ActivityManager.START_CANCELED;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.InsetsState.ITYPE_TOP_GENERIC_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.BLASTSyncEngine.METHOD_BLAST;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowContainer.SYNC_STATE_READY;
import static com.android.server.wm.WindowState.BLAST_TIMEOUT_DURATION;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IRequestFinishCallback;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.Rational;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.IWindowContainerTransactionCallback;
import android.window.StartingWindowInfo;
import android.window.StartingWindowRemovalInfo;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.server.wm.TaskOrganizerController.PendingTaskEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
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

    private ITaskOrganizer createMockOrganizer() {
        final ITaskOrganizer organizer = mock(ITaskOrganizer.class);
        when(organizer.asBinder()).thenReturn(new Binder());
        return organizer;
    }

    private ITaskOrganizer registerMockOrganizer(ArrayList<TaskAppearedInfo> existingTasks) {
        final ITaskOrganizer organizer = createMockOrganizer();
        ParceledListSlice<TaskAppearedInfo> tasks =
                mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(organizer);
        if (existingTasks != null) {
            existingTasks.addAll(tasks.getList());
        }
        return organizer;
    }

    private ITaskOrganizer registerMockOrganizer() {
        return registerMockOrganizer(null);
    }

    Task createTask(Task rootTask, boolean fakeDraw) {
        final Task task = createTaskInRootTask(rootTask, 0);

        if (fakeDraw) {
            task.setHasBeenVisible(true);
        }
        return task;
    }

    Task createTask(Task rootTask) {
        // Fake draw notifications for most of our tests.
        return createTask(rootTask, true);
    }

    Task createRootTask() {
        return createTask(mDisplayContent);
    }

    @Before
    public void setUp() {
        // We defer callbacks since we need to adjust task surface visibility, but for these tests,
        // just run the callbacks synchronously
        mWm.mAtmService.mTaskOrganizerController.setDeferTaskOrgCallbacksConsumer((r) -> r.run());
    }

    @Test
    public void testAppearVanish() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        rootTask.removeImmediately();
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer).onTaskVanished(any());
    }

    @Test
    public void testAppearWaitsForVisibility() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask, false);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        rootTask.setHasBeenVisible(true);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        assertTrue(rootTask.getHasBeenVisible());

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        rootTask.removeImmediately();
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer).onTaskVanished(any());
    }

    @Test
    public void testNoVanishedIfNoAppear() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask, false /* hasBeenVisible */);

        // In this test we skip making the Task visible, and verify
        // that even though a TaskOrganizer is set remove doesn't emit
        // a vanish callback, because we never emitted appear.
        rootTask.setTaskOrganizer(organizer);
        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        rootTask.removeImmediately();
        verify(organizer, never()).onTaskVanished(any());
    }

    @Test
    public void testTaskNoDraw() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask, false /* fakeDraw */);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(rootTask.isOrganized());

        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(0)).onTaskVanished(any());
        assertFalse(rootTask.isOrganized());
    }

    @Test
    public void testClearOrganizer() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(rootTask.isOrganized());

        rootTask.setTaskOrganizer(null);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer).onTaskVanished(any());
        assertFalse(rootTask.isOrganized());
    }

    @Test
    public void testRemoveWithOrganizerRemovesTask() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        rootTask.mRemoveWithTaskOrganizer = true;

        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(rootTask.isOrganized());

        spyOn(mWm.mAtmService);
        rootTask.setTaskOrganizer(null);
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(mWm.mAtmService).removeTask(eq(rootTask.mTaskId));
    }

    @Test
    public void testNoRemoveWithOrganizerNoRemoveTask() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        rootTask.mRemoveWithTaskOrganizer = false;

        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(rootTask.isOrganized());

        spyOn(mWm.mAtmService);
        rootTask.setTaskOrganizer(null);
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(mWm.mAtmService, never()).removeTask(eq(rootTask.mTaskId));
    }

    @Test
    public void testUnregisterOrganizer() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(rootTask.isOrganized());

        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer, times(0)).onTaskVanished(any());
        assertFalse(rootTask.isOrganized());
    }

    @Test
    public void testUnregisterOrganizerReturnsRegistrationToPrevious() throws RemoteException {
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final Task rootTask2 = createRootTask();
        final Task task2 = createTask(rootTask2);
        final Task rootTask3 = createRootTask();
        final Task task3 = createTask(rootTask3);
        final ArrayList<TaskAppearedInfo> existingTasks = new ArrayList<>();
        final ITaskOrganizer organizer = registerMockOrganizer(existingTasks);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        // verify that tasks are returned and taskAppeared is not called
        assertContainsTasks(existingTasks, rootTask, rootTask2, rootTask3);
        verify(organizer, times(0)).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer, times(0)).onTaskVanished(any());
        assertTrue(rootTask.isOrganized());

        // Now we replace the registration and verify the new organizer receives existing tasks
        final ArrayList<TaskAppearedInfo> existingTasks2 = new ArrayList<>();
        final ITaskOrganizer organizer2 = registerMockOrganizer(existingTasks2);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        assertContainsTasks(existingTasks2, rootTask, rootTask2, rootTask3);
        verify(organizer2, times(0)).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer2, times(0)).onTaskVanished(any());
        // Removed tasks from the original organizer
        assertTaskVanished(organizer, true /* expectVanished */, rootTask, rootTask2, rootTask3);
        assertTrue(rootTask2.isOrganized());

        // Now we unregister the second one, the first one should automatically be reregistered
        // so we verify that it's now seeing changes.
        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer2);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(3))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        verify(organizer2, times(0)).onTaskVanished(any());
    }

    @Test
    public void testUnregisterOrganizer_removesTasksCreatedByIt() throws RemoteException {
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final Task rootTask2 = createRootTask();
        rootTask2.mCreatedByOrganizer = true;
        final Task task2 = createTask(rootTask2);
        final ArrayList<TaskAppearedInfo> existingTasks = new ArrayList<>();
        final ITaskOrganizer organizer = registerMockOrganizer(existingTasks);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        // verify that tasks are returned and taskAppeared is called only for rootTask2 since it
        // is the one created by this organizer.
        assertContainsTasks(existingTasks, rootTask);
        verify(organizer, times(1)).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer, times(0)).onTaskVanished(any());
        assertTrue(rootTask.isOrganized());

        // Now we replace the registration and verify the new organizer receives existing tasks
        final ArrayList<TaskAppearedInfo> existingTasks2 = new ArrayList<>();
        final ITaskOrganizer organizer2 = registerMockOrganizer(existingTasks2);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        assertContainsTasks(existingTasks2, rootTask);
        verify(organizer2, never()).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer2, times(0)).onTaskVanished(any());
        // The non-CreatedByOrganizer task is removed from the original organizer.
        assertTaskVanished(organizer, true /* expectVanished */, rootTask);
        assertEquals(organizer2, rootTask.mTaskOrganizer);
        // The CreatedByOrganizer task should be still organized by the original organizer.
        assertEquals(organizer, rootTask2.mTaskOrganizer);

        clearInvocations(organizer);
        // Now we unregister the second one, the first one should automatically be reregistered
        // so we verify that it's now seeing changes.
        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer2);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        verify(organizer, times(2))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        // Unregister the first one. The CreatedByOrganizer task created by it must be removed.
        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        assertFalse(rootTask2.isAttached());
        assertFalse(task2.isAttached());
        // Normal task should keep.
        assertTrue(task.isAttached());
        verify(organizer2, times(0)).onTaskVanished(any());
    }

    @Test
    public void testOrganizerDeathReturnsRegistrationToPrevious() throws RemoteException {
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final Task rootTask2 = createRootTask();
        final Task task2 = createTask(rootTask2);
        final Task rootTask3 = createRootTask();
        final Task task3 = createTask(rootTask3);
        final ArrayList<TaskAppearedInfo> existingTasks = new ArrayList<>();
        final ITaskOrganizer organizer = registerMockOrganizer(existingTasks);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        // verify that tasks are returned and taskAppeared is not called
        assertContainsTasks(existingTasks, rootTask, rootTask2, rootTask3);
        verify(organizer, times(0)).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer, times(0)).onTaskVanished(any());
        assertTrue(rootTask.isOrganized());

        // Now we replace the registration and verify the new organizer receives existing tasks
        final ArrayList<TaskAppearedInfo> existingTasks2 = new ArrayList<>();
        final ITaskOrganizer organizer2 = registerMockOrganizer(existingTasks2);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        assertContainsTasks(existingTasks2, rootTask, rootTask2, rootTask3);
        verify(organizer2, times(0)).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer2, times(0)).onTaskVanished(any());
        // Removed tasks from the original organizer
        assertTaskVanished(organizer, true /* expectVanished */, rootTask, rootTask2, rootTask3);
        assertTrue(rootTask2.isOrganized());

        // Trigger binderDied for second one, the first one should automatically be reregistered
        // so we verify that it's now seeing changes.
        mWm.mAtmService.mTaskOrganizerController.getTaskOrganizerState(organizer2.asBinder())
                .getDeathRecipient().binderDied();

        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(3))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        verify(organizer2, times(0)).onTaskVanished(any());
    }

    @Test
    public void testRegisterTaskOrganizerWithExistingTasks() throws RemoteException {
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final Task rootTask2 = createRootTask();
        final Task task2 = createTask(rootTask2);
        ArrayList<TaskAppearedInfo> existingTasks = new ArrayList<>();
        final ITaskOrganizer organizer = registerMockOrganizer(existingTasks);
        assertContainsTasks(existingTasks, rootTask, rootTask2);

        // Verify we don't get onTaskAppeared if we are returned the tasks
        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
    }

    @Test
    public void testRegisterTaskOrganizerWithExistingTasks_noSurfaceControl()
            throws RemoteException {
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final Task rootTask2 = createRootTask();
        final Task task2 = createTask(rootTask2);
        rootTask2.setSurfaceControl(null);
        ArrayList<TaskAppearedInfo> existingTasks = new ArrayList<>();
        final ITaskOrganizer organizer = registerMockOrganizer(existingTasks);
        assertContainsTasks(existingTasks, rootTask);

        // Verify we don't get onTaskAppeared if we are returned the tasks
        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
    }

    @Test
    public void testTaskTransaction() {
        removeGlobalMinSizeRestriction();
        final Task rootTask = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = rootTask.getTopMostTask();
        testTransaction(task);
    }

    @Test
    public void testRootTaskTransaction() {
        removeGlobalMinSizeRestriction();
        final Task rootTask = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        RootTaskInfo info =
                mWm.mAtmService.getRootTaskInfo(WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        assertEquals(rootTask.mRemoteToken.toWindowContainerToken(), info.token);
        testTransaction(rootTask);
    }

    @Test
    public void testDisplayAreaTransaction() {
        removeGlobalMinSizeRestriction();
        final DisplayArea displayArea = mDisplayContent.getDefaultTaskDisplayArea();
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
        final Task rootTask = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        testSetWindowingMode(rootTask);

        final DisplayArea displayArea = mDisplayContent.getDefaultTaskDisplayArea();
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
        final Task rootTask = record.getRootTask();
        final WindowContainerTransaction t = new WindowContainerTransaction();

        t.setWindowingMode(rootTask.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_PINNED);
        t.setActivityWindowingMode(
                rootTask.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_FULLSCREEN);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        assertEquals(WINDOWING_MODE_FULLSCREEN, record.getWindowingMode());
        // Get the root task from the PIP activity record again, since the PIP root task may have
        // changed when the activity entered PIP mode.
        final Task pipRootTask = record.getRootTask();
        assertEquals(WINDOWING_MODE_PINNED, pipRootTask.getWindowingMode());
    }

    @Test
    public void testContainerFocusableChanges() {
        removeGlobalMinSizeRestriction();
        final Task rootTask = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = rootTask.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertTrue(task.isFocusable());
        t.setFocusable(rootTask.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertFalse(task.isFocusable());
        t.setFocusable(rootTask.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertTrue(task.isFocusable());
    }

    @Test
    public void testContainerHiddenChanges() {
        removeGlobalMinSizeRestriction();
        final Task rootTask = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertTrue(rootTask.shouldBeVisible(null));
        t.setHidden(rootTask.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertFalse(rootTask.shouldBeVisible(null));
        t.setHidden(rootTask.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertTrue(rootTask.shouldBeVisible(null));
    }

    @Test
    public void testContainerTranslucentChanges() {
        removeGlobalMinSizeRestriction();
        final Task rootTask = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(rootTask).build();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertFalse(rootTask.isTranslucent(activity));
        t.setForceTranslucent(rootTask.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertTrue(rootTask.isTranslucent(activity));
        t.setForceTranslucent(rootTask.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertFalse(rootTask.isTranslucent(activity));
    }

    @Test
    public void testSetIgnoreOrientationRequest_taskDisplayArea() {
        removeGlobalMinSizeRestriction();
        final TaskDisplayArea taskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final Task rootTask = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(rootTask).build();
        taskDisplayArea.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplayContent.setFocusedApp(activity);
        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        // TDA returns UNSET when ignoreOrientationRequest == true
        // DC is UNSPECIFIED when child returns UNSET
        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSET);
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);

        WindowContainerTransaction t = new WindowContainerTransaction();
        t.setIgnoreOrientationRequest(
                taskDisplayArea.mRemoteToken.toWindowContainerToken(),
                false /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // TDA returns app request orientation when ignoreOrientationRequest == false
        // DC uses the same as TDA returns when it is not UNSET.
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);

        t.setIgnoreOrientationRequest(
                taskDisplayArea.mRemoteToken.toWindowContainerToken(),
                true /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // TDA returns UNSET when ignoreOrientationRequest == true
        // DC is UNSPECIFIED when child returns UNSET
        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSET);
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Test
    public void testSetIgnoreOrientationRequest_displayContent() {
        removeGlobalMinSizeRestriction();
        final TaskDisplayArea taskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final Task rootTask = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(rootTask).build();
        mDisplayContent.setFocusedApp(activity);
        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        // DC uses the orientation request from app
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);

        WindowContainerTransaction t = new WindowContainerTransaction();
        t.setIgnoreOrientationRequest(
                mDisplayContent.mRemoteToken.toWindowContainerToken(),
                true /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // DC returns UNSPECIFIED when ignoreOrientationRequest == true
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);

        t.setIgnoreOrientationRequest(
                mDisplayContent.mRemoteToken.toWindowContainerToken(),
                false /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // DC uses the orientation request from app after mIgnoreOrientationRequest is set to false
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Test
    public void testOverrideConfigSize() {
        removeGlobalMinSizeRestriction();
        final Task rootTask = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = rootTask.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        final int origScreenWDp = task.getConfiguration().screenHeightDp;
        final int origScreenHDp = task.getConfiguration().screenHeightDp;
        t = new WindowContainerTransaction();
        // verify that setting config overrides on parent restricts children.
        t.setScreenSizeDp(rootTask.mRemoteToken
                .toWindowContainerToken(), origScreenWDp, origScreenHDp / 2);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(origScreenHDp / 2, task.getConfiguration().screenHeightDp);
        t = new WindowContainerTransaction();
        t.setScreenSizeDp(rootTask.mRemoteToken.toWindowContainerToken(), SCREEN_WIDTH_DP_UNDEFINED,
                SCREEN_HEIGHT_DP_UNDEFINED);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(origScreenHDp, task.getConfiguration().screenHeightDp);
    }

    @Test
    public void testCreateDeleteRootTasks() {
        DisplayContent dc = mWm.mRoot.getDisplayContent(Display.DEFAULT_DISPLAY);

        Task task1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                dc, WINDOWING_MODE_FULLSCREEN, null);
        RunningTaskInfo info1 = task1.getTaskInfo();
        assertEquals(WINDOWING_MODE_FULLSCREEN,
                info1.configuration.windowConfiguration.getWindowingMode());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info1.topActivityType);

        Task task2 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                dc, WINDOWING_MODE_MULTI_WINDOW, null);
        RunningTaskInfo info2 = task2.getTaskInfo();
        assertEquals(WINDOWING_MODE_MULTI_WINDOW,
                info2.configuration.windowConfiguration.getWindowingMode());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info2.topActivityType);

        List<Task> infos = getTasksCreatedByOrganizer(dc);
        assertEquals(2, infos.size());

        assertTrue(mWm.mAtmService.mTaskOrganizerController.deleteRootTask(info1.token));
        infos = getTasksCreatedByOrganizer(dc);
        assertEquals(1, infos.size());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, infos.get(0).getWindowingMode());
    }

    @Test
    public void testSetAdjacentLaunchRoot() {
        DisplayContent dc = mWm.mRoot.getDisplayContent(Display.DEFAULT_DISPLAY);

        final Task task1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                dc, WINDOWING_MODE_MULTI_WINDOW, null);
        final RunningTaskInfo info1 = task1.getTaskInfo();
        final Task task2 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                dc, WINDOWING_MODE_MULTI_WINDOW, null);
        final RunningTaskInfo info2 = task2.getTaskInfo();

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setAdjacentRoots(info1.token, info2.token);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(task1.getAdjacentTaskFragment(), task2);
        assertEquals(task2.getAdjacentTaskFragment(), task1);

        wct = new WindowContainerTransaction();
        wct.setLaunchAdjacentFlagRoot(info1.token);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(dc.getDefaultTaskDisplayArea().mLaunchAdjacentFlagRootTask, task1);

        wct = new WindowContainerTransaction();
        wct.clearAdjacentRoots(info1.token);
        wct.clearLaunchAdjacentFlagRoot(info1.token);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(task1.getAdjacentTaskFragment(), null);
        assertEquals(task2.getAdjacentTaskFragment(), null);
        assertEquals(dc.getDefaultTaskDisplayArea().mLaunchAdjacentFlagRootTask, null);
    }

    @Test
    public void testTileAddRemoveChild() {
        final StubOrganizer listener = new StubOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener);
        Task task = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, null);
        RunningTaskInfo info1 = task.getTaskInfo();

        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD);
        assertEquals(mDisplayContent.getWindowingMode(), rootTask.getWindowingMode());
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(rootTask.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(info1.configuration.windowConfiguration.getWindowingMode(),
                rootTask.getWindowingMode());

        // Info should reflect new membership
        List<Task> infos = getTasksCreatedByOrganizer(mDisplayContent);
        info1 = infos.get(0).getTaskInfo();
        assertEquals(ACTIVITY_TYPE_STANDARD, info1.topActivityType);

        // Children inherit configuration
        Rect newSize = new Rect(10, 10, 300, 300);
        Task task1 = WindowContainer.fromBinder(info1.token.asBinder()).asTask();
        Configuration c = new Configuration(task1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(newSize);
        doNothing().when(rootTask).adjustForMinimalTaskDimensions(any(), any(), any());
        task1.onRequestedOverrideConfigurationChanged(c);
        assertEquals(newSize, rootTask.getBounds());

        wct = new WindowContainerTransaction();
        wct.reparent(rootTask.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(mDisplayContent.getWindowingMode(), rootTask.getWindowingMode());
        infos = getTasksCreatedByOrganizer(mDisplayContent);
        info1 = infos.get(0).getTaskInfo();
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info1.topActivityType);
    }

    @Test
    public void testAddRectInsetsProvider() {
        final Task rootTask = createTask(mDisplayContent);

        final Task navigationBarInsetsReceiverTask = createTaskInRootTask(rootTask, 0);
        navigationBarInsetsReceiverTask.getConfiguration().windowConfiguration.setBounds(new Rect(
                0, 200, 1080, 700));

        final Rect navigationBarInsetsProviderRect = new Rect(0, 0, 1080, 200);

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.addRectInsetsProvider(navigationBarInsetsReceiverTask.mRemoteToken
                        .toWindowContainerToken(), navigationBarInsetsProviderRect,
                new int[]{ITYPE_TOP_GENERIC_OVERLAY});
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);

        assertThat(navigationBarInsetsReceiverTask.mLocalInsetsSourceProviders
                .valueAt(0).getSource().getId()).isEqualTo(ITYPE_TOP_GENERIC_OVERLAY);
    }

    @Test
    public void testRemoveInsetsProvider() {
        final Task rootTask = createTask(mDisplayContent);

        final Task navigationBarInsetsReceiverTask = createTaskInRootTask(rootTask, 0);
        navigationBarInsetsReceiverTask.getConfiguration().windowConfiguration.setBounds(new Rect(
                0, 200, 1080, 700));

        final Rect navigationBarInsetsProviderRect = new Rect(0, 0, 1080, 200);

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.addRectInsetsProvider(navigationBarInsetsReceiverTask.mRemoteToken
                        .toWindowContainerToken(), navigationBarInsetsProviderRect,
                new int[]{ITYPE_TOP_GENERIC_OVERLAY});
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);

        final WindowContainerTransaction wct2 = new WindowContainerTransaction();
        wct2.removeInsetsProvider(navigationBarInsetsReceiverTask.mRemoteToken
                .toWindowContainerToken(), new int[]{ITYPE_TOP_GENERIC_OVERLAY});
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct2);

        assertThat(navigationBarInsetsReceiverTask.mLocalInsetsSourceProviders.size()).isEqualTo(0);
    }

    @Test
    public void testTaskInfoCallback() {
        final ArrayList<RunningTaskInfo> lastReportedTiles = new ArrayList<>();
        final boolean[] called = {false};
        final StubOrganizer listener = new StubOrganizer() {
            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                lastReportedTiles.add(info);
                called[0] = true;
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener);
        Task task = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, null);
        RunningTaskInfo info1 = task.getTaskInfo();
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        lastReportedTiles.clear();
        called[0] = false;

        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD);
        Task task1 = WindowContainer.fromBinder(info1.token.asBinder()).asTask();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(rootTask.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        final Task rootTask2 = mDisplayContent.getDefaultTaskDisplayArea().getRootHomeTask();
        wct = new WindowContainerTransaction();
        wct.reparent(rootTask2.mRemoteToken.toWindowContainerToken(),
                info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_HOME, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        task1.positionChildAt(POSITION_TOP, rootTask, false /* includingParents */);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        wct = new WindowContainerTransaction();
        wct.reparent(rootTask.mRemoteToken.toWindowContainerToken(),
                null, true /* onTop */);
        wct.reparent(rootTask2.mRemoteToken.toWindowContainerToken(),
                null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, lastReportedTiles.get(0).topActivityType);
    }

    @Test
    public void testHierarchyTransaction() {
        final ArrayMap<IBinder, RunningTaskInfo> lastReportedTiles = new ArrayMap<>();
        final StubOrganizer listener = new StubOrganizer() {
            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                lastReportedTiles.put(info.token.asBinder(), info);
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener);

        Task task1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, null);
        RunningTaskInfo info1 = task1.getTaskInfo();
        Task task2 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, null);
        RunningTaskInfo info2 = task2.getTaskInfo();
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();

        // 2 + 1 (home) = 3
        final int initialRootTaskCount = mWm.mAtmService.mTaskOrganizerController.getRootTasks(
                mDisplayContent.mDisplayId, null /* activityTypes */).size();
        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD);

        // Check getRootTasks works
        List<RunningTaskInfo> roots = mWm.mAtmService.mTaskOrganizerController.getRootTasks(
                mDisplayContent.mDisplayId, null /* activityTypes */);
        assertEquals(initialRootTaskCount + 1, roots.size());

        lastReportedTiles.clear();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(rootTask.mRemoteToken.toWindowContainerToken(),
                info1.token, true /* onTop */);
        final Task rootTask2 = mDisplayContent.getDefaultTaskDisplayArea().getRootHomeTask();
        wct.reparent(rootTask2.mRemoteToken.toWindowContainerToken(),
                info2.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertFalse(lastReportedTiles.isEmpty());
        assertEquals(ACTIVITY_TYPE_STANDARD,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info2.token.asBinder()).topActivityType);

        lastReportedTiles.clear();
        wct = new WindowContainerTransaction();
        wct.reparent(rootTask2.mRemoteToken.toWindowContainerToken(),
                info1.token, false /* onTop */);
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
        // Home (rootTask2) was moved into task1, so only remain 2 roots: task1 and task2.
        assertEquals(initialRootTaskCount - 1, roots.size());

        lastReportedTiles.clear();
        wct = new WindowContainerTransaction();
        wct.reorder(rootTask2.mRemoteToken.toWindowContainerToken(), true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        // Home should now be on top. No change occurs in second tile, so not reported
        assertEquals(1, lastReportedTiles.size());
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);

        // This just needs to not crash (ie. it should be possible to reparent to display twice)
        wct = new WindowContainerTransaction();
        wct.reparent(rootTask2.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        wct = new WindowContainerTransaction();
        wct.reparent(rootTask2.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
    }

    private List<Task> getTasksCreatedByOrganizer(DisplayContent dc) {
        final ArrayList<Task> out = new ArrayList<>();
        dc.forAllRootTasks(task -> {
            if (task.mCreatedByOrganizer) {
                out.add(task);
            }
        });
        return out;
    }

    @Test
    public void testBLASTCallbackWithActivityChildren() {
        final Task rootTaskController1 = createRootTask();
        final Task task = createTask(rootTaskController1);
        final WindowState w = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window");

        w.mActivityRecord.setVisibleRequested(true);
        w.mActivityRecord.setVisible(true);

        BLASTSyncEngine bse = new BLASTSyncEngine(mWm);

        BLASTSyncEngine.TransactionReadyListener transactionListener =
                mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener, BLAST_TIMEOUT_DURATION, "", METHOD_BLAST);
        bse.addToSyncSet(id, task);
        bse.setReady(id);
        bse.onSurfacePlacement();

        // Even though w is invisible (and thus activity isn't waiting on it), activity will
        // continue to wait until it has at-least 1 visible window.
        // Since we have a child window we still shouldn't be done.
        verify(transactionListener, never()).onTransactionReady(anyInt(), any());

        makeWindowVisible(w);
        bse.onSurfacePlacement();
        w.immediatelyNotifyBlastSync();
        bse.onSurfacePlacement();

        verify(transactionListener).onTransactionReady(anyInt(), any());
    }

    static class StubOrganizer extends ITaskOrganizer.Stub {
        RunningTaskInfo mInfo;

        @Override
        public void addStartingWindow(StartingWindowInfo info, IBinder appToken) { }
        @Override
        public void removeStartingWindow(StartingWindowRemovalInfo removalInfo) { }
        @Override
        public void copySplashScreenView(int taskId) { }
        @Override
        public void onTaskAppeared(RunningTaskInfo info, SurfaceControl leash) {
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
        @Override
        public void onImeDrawnOnTask(int taskId) throws RemoteException {
        }
        @Override
        public void onAppSplashScreenViewRemoved(int taskId) {
        }
    };

    private ActivityRecord makePipableActivity() {
        final ActivityRecord record = createActivityRecordWithParentTask(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        record.info.flags |= ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
        record.setPictureInPictureParams(new PictureInPictureParams.Builder()
                .setAutoEnterEnabled(true).build());
        spyOn(record);
        doReturn(true).when(record).checkEnterPictureInPictureState(any(), anyBoolean());

        record.getTask().setHasBeenVisible(true);
        return record;
    }

    @Test
    public void testEnterPipParams() {
        final StubOrganizer o = new StubOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o);
        final ActivityRecord record = makePipableActivity();

        final PictureInPictureParams p = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 2)).build();
        assertTrue(mWm.mAtmService.mActivityClientController.enterPictureInPictureMode(
                record.token, p));
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
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o);

        final ActivityRecord record = makePipableActivity();
        final PictureInPictureParams p = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 2)).build();
        assertTrue(mWm.mAtmService.mActivityClientController.enterPictureInPictureMode(
                record.token, p));
        waitUntilHandlersIdle();
        assertNotNull(o.mInfo);
        assertNotNull(o.mInfo.pictureInPictureParams);

        final PictureInPictureParams p2 = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(3, 4)).build();
        mWm.mAtmService.mActivityClientController.setPictureInPictureParams(record.token, p2);
        waitUntilHandlersIdle();
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        assertNotNull(o.mChangedInfo);
        assertNotNull(o.mChangedInfo.pictureInPictureParams);
        final Rational ratio = o.mChangedInfo.pictureInPictureParams.getAspectRatio();
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
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o);

        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final ActivityRecord record = createActivityRecord(rootTask.mDisplayContent, task);

        rootTask.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        record.setTaskDescription(new ActivityManager.TaskDescription("TestDescription"));
        waitUntilHandlersIdle();
        assertEquals("TestDescription", o.mChangedInfo.taskDescription.getLabel());
    }

    @Test
    public void testPreventDuplicateAppear() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask, false /* fakeDraw */);

        rootTask.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        rootTask.setTaskOrganizer(organizer);
        // setHasBeenVisible was already called once by the set-up code.
        rootTask.setHasBeenVisible(true);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(1))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        rootTask.setTaskOrganizer(null);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(1)).onTaskVanished(any());
        rootTask.setTaskOrganizer(organizer);
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(2))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        rootTask.removeImmediately();
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(2)).onTaskVanished(any());
    }

    @Test
    public void testInterceptBackPressedOnTaskRoot() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final ActivityRecord activity = createActivityRecord(rootTask.mDisplayContent, task);
        final Task rootTask2 = createRootTask();
        final Task task2 = createTask(rootTask2);
        final ActivityRecord activity2 = createActivityRecord(rootTask.mDisplayContent, task2);

        assertTrue(rootTask.isOrganized());
        assertTrue(rootTask2.isOrganized());

        // Verify a back pressed does not call the organizer
        mWm.mAtmService.mActivityClientController.onBackPressed(activity.token,
                new IRequestFinishCallback.Default());
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, never()).onBackPressedOnTaskRoot(any());

        // Enable intercepting back
        mWm.mAtmService.mTaskOrganizerController.setInterceptBackPressedOnTaskRoot(
                rootTask.mRemoteToken.toWindowContainerToken(), true);

        // Verify now that the back press does call the organizer
        mWm.mAtmService.mActivityClientController.onBackPressed(activity.token,
                new IRequestFinishCallback.Default());
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(1)).onBackPressedOnTaskRoot(any());

        // Disable intercepting back
        mWm.mAtmService.mTaskOrganizerController.setInterceptBackPressedOnTaskRoot(
                rootTask.mRemoteToken.toWindowContainerToken(), false);

        // Verify now that the back press no longer calls the organizer
        mWm.mAtmService.mActivityClientController.onBackPressed(activity.token,
                new IRequestFinishCallback.Default());
        // Ensure events dispatch to organizer.
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer, times(1)).onBackPressedOnTaskRoot(any());
    }

    @Test
    public void testBLASTCallbackWithWindows() throws Exception {
        final Task rootTaskController = createRootTask();
        final Task task = createTask(rootTaskController);
        final WindowState w1 = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window 1");
        final WindowState w2 = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window 2");
        makeWindowVisible(w1);
        makeWindowVisible(w2);

        IWindowContainerTransactionCallback mockCallback =
                mock(IWindowContainerTransactionCallback.class);
        int id = mWm.mAtmService.mWindowOrganizerController.startSyncWithOrganizer(mockCallback);

        mWm.mAtmService.mWindowOrganizerController.addToSyncSet(id, task);
        mWm.mAtmService.mWindowOrganizerController.setSyncReady(id);

        // Since we have a window we have to wait for it to draw to finish sync.
        verify(mockCallback, never()).onTransactionReady(anyInt(), any());
        assertTrue(w1.useBLASTSync());
        assertTrue(w2.useBLASTSync());

        // Make second (bottom) ready. If we started with the top, since activities fillsParent
        // by default, the sync would be considered finished.
        w2.immediatelyNotifyBlastSync();
        mWm.mSyncEngine.onSurfacePlacement();
        verify(mockCallback, never()).onTransactionReady(anyInt(), any());

        assertEquals(SYNC_STATE_READY, w2.mSyncState);
        // Even though one Window finished drawing, both windows should still be using blast sync
        assertTrue(w1.useBLASTSync());
        assertTrue(w2.useBLASTSync());

        // A drawn window can complete the sync state automatically.
        w1.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        mWm.mSyncEngine.onSurfacePlacement();
        verify(mockCallback).onTransactionReady(anyInt(), any());
        assertFalse(w1.useBLASTSync());
        assertFalse(w2.useBLASTSync());
    }

    @Test
    public void testDisplayAreaHiddenTransaction() {
        removeGlobalMinSizeRestriction();

        WindowContainerTransaction trx = new WindowContainerTransaction();

        TaskDisplayArea taskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();

        trx.setHidden(taskDisplayArea.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(trx);

        taskDisplayArea.forAllTasks(daTask -> {
            assertTrue(daTask.isForceHidden());
        });

        trx.setHidden(taskDisplayArea.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(trx);

        taskDisplayArea.forAllTasks(daTask -> {
            assertFalse(daTask.isForceHidden());
        });
    }

    @Test
    public void testReparentToOrganizedTask() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        Task rootTask = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, null);
        final Task task1 = createRootTask();
        final Task task2 = createTask(rootTask, false /* fakeDraw */);
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(task1.mRemoteToken.toWindowContainerToken(),
                rootTask.mRemoteToken.toWindowContainerToken(), true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(task1.isOrganized());
        assertTrue(task2.isOrganized());
    }

    @Test
    public void testAppearDeferThenInfoChange() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();

        // Assume layout defer
        mWm.mWindowPlacerLocked.deferLayout();

        final Task task = createTask(rootTask);
        final ActivityRecord record = createActivityRecord(rootTask.mDisplayContent, task);

        rootTask.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        record.setTaskDescription(new ActivityManager.TaskDescription("TestDescription"));
        waitUntilHandlersIdle();

        ArrayList<PendingTaskEvent> pendingEvents = getTaskPendingEvent(organizer, rootTask);
        assertEquals(1, pendingEvents.size());
        assertEquals(PendingTaskEvent.EVENT_APPEARED, pendingEvents.get(0).mEventType);
        assertEquals("TestDescription",
                pendingEvents.get(0).mTask.getTaskInfo().taskDescription.getLabel());
    }

    @Test
    public void testAppearDeferThenVanish() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();

        // Assume layout defer
        mWm.mWindowPlacerLocked.deferLayout();

        final Task task = createTask(rootTask);

        rootTask.removeImmediately();
        waitUntilHandlersIdle();

        ArrayList<PendingTaskEvent> pendingEvents = getTaskPendingEvent(organizer, rootTask);
        assertEquals(0, pendingEvents.size());
    }

    @Test
    public void testInfoChangeDeferMultiple() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final ActivityRecord record = createActivityRecord(rootTask.mDisplayContent, task);

        // Assume layout defer
        mWm.mWindowPlacerLocked.deferLayout();

        rootTask.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        record.setTaskDescription(new ActivityManager.TaskDescription("TestDescription"));
        waitUntilHandlersIdle();

        ArrayList<PendingTaskEvent> pendingEvents = getTaskPendingEvent(organizer, rootTask);
        assertEquals(1, pendingEvents.size());
        assertEquals(PendingTaskEvent.EVENT_INFO_CHANGED, pendingEvents.get(0).mEventType);
        assertEquals("TestDescription",
                pendingEvents.get(0).mTask.getTaskInfo().taskDescription.getLabel());

        record.setTaskDescription(new ActivityManager.TaskDescription("TestDescription2"));
        waitUntilHandlersIdle();

        pendingEvents = getTaskPendingEvent(organizer, rootTask);
        assertEquals(1, pendingEvents.size());
        assertEquals(PendingTaskEvent.EVENT_INFO_CHANGED, pendingEvents.get(0).mEventType);
        assertEquals("TestDescription2",
                pendingEvents.get(0).mTask.getTaskInfo().taskDescription.getLabel());
    }

    @Test
    public void testInfoChangDeferThenVanish() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final ActivityRecord record = createActivityRecord(rootTask.mDisplayContent, task);

        // Assume layout defer
        mWm.mWindowPlacerLocked.deferLayout();

        rootTask.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        record.setTaskDescription(new ActivityManager.TaskDescription("TestDescription"));

        rootTask.removeImmediately();
        waitUntilHandlersIdle();

        ArrayList<PendingTaskEvent> pendingEvents = getTaskPendingEvent(organizer, rootTask);
        assertEquals(1, pendingEvents.size());
        assertEquals(PendingTaskEvent.EVENT_VANISHED, pendingEvents.get(0).mEventType);
        assertEquals("TestDescription",
                pendingEvents.get(0).mTask.getTaskInfo().taskDescription.getLabel());
    }

    @Test
    public void testVanishDeferThenInfoChange() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final ActivityRecord record = createActivityRecord(rootTask.mDisplayContent, task);

        // Assume layout defer
        mWm.mWindowPlacerLocked.deferLayout();

        rootTask.removeImmediately();
        rootTask.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        waitUntilHandlersIdle();

        ArrayList<PendingTaskEvent> pendingEvents = getTaskPendingEvent(organizer, rootTask);
        assertEquals(1, pendingEvents.size());
        assertEquals(PendingTaskEvent.EVENT_VANISHED, pendingEvents.get(0).mEventType);
    }

    @Test
    public void testVanishDeferThenBackOnRoot() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final ActivityRecord record = createActivityRecord(rootTask.mDisplayContent, task);

        // Assume layout defer
        mWm.mWindowPlacerLocked.deferLayout();

        rootTask.removeImmediately();
        mWm.mAtmService.mActivityClientController.onBackPressed(record.token,
                new IRequestFinishCallback.Default());
        waitUntilHandlersIdle();

        ArrayList<PendingTaskEvent> pendingEvents = getTaskPendingEvent(organizer, rootTask);
        assertEquals(1, pendingEvents.size());
        assertEquals(PendingTaskEvent.EVENT_VANISHED, pendingEvents.get(0).mEventType);
    }

    private ArrayList<PendingTaskEvent> getTaskPendingEvent(ITaskOrganizer organizer, Task task) {
        ArrayList<PendingTaskEvent> total =
                mWm.mAtmService.mTaskOrganizerController
                        .getTaskOrganizerPendingEvents(organizer.asBinder())
                        .getPendingEventList();
        ArrayList<PendingTaskEvent> result = new ArrayList();

        for (int i = 0; i < total.size(); i++) {
            PendingTaskEvent entry = total.get(i);
            if (entry.mTask.mTaskId == task.mTaskId) {
                result.add(entry);
            }
        }

        return result;
    }

    @Test
    public void testReparentNonResizableTaskToSplitScreen() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(SCREEN_ORIENTATION_LANDSCAPE)
                .build();
        final Task rootTask = activity.getRootTask();
        rootTask.setResizeMode(activity.info.resizeMode);
        final Task splitPrimaryRootTask = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, null);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(rootTask.mRemoteToken.toWindowContainerToken(),
                splitPrimaryRootTask.mRemoteToken.toWindowContainerToken(), true /* onTop */);

        // Can't reparent non-resizable to split screen
        mAtm.mSupportsNonResizableMultiWindow = -1;
        mAtm.mWindowOrganizerController.applyTransaction(wct);

        assertEquals(rootTask, activity.getRootTask());

        // Allow reparent non-resizable to split screen
        mAtm.mSupportsNonResizableMultiWindow = 1;
        mAtm.mWindowOrganizerController.applyTransaction(wct);

        assertEquals(splitPrimaryRootTask, activity.getRootTask());
    }

    @Test
    public void testSizeCompatModeChangedOnFirstOrganizedTask() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task rootTask = createRootTask();
        final Task task = createTask(rootTask);
        final ActivityRecord activity = createActivityRecord(rootTask.mDisplayContent, task);
        final ArgumentCaptor<RunningTaskInfo> infoCaptor =
                ArgumentCaptor.forClass(RunningTaskInfo.class);

        assertTrue(rootTask.isOrganized());

        spyOn(activity);
        doReturn(true).when(activity).inSizeCompatMode();
        doReturn(true).when(activity).isState(RESUMED);

        // Ensure task info show top activity in size compat.
        rootTask.onSizeCompatActivityChanged();
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer).onTaskInfoChanged(infoCaptor.capture());
        RunningTaskInfo info = infoCaptor.getValue();
        assertEquals(rootTask.mTaskId, info.taskId);
        assertTrue(info.topActivityInSizeCompat);

        // Ensure task info show top activity that is not in foreground as not in size compat.
        clearInvocations(organizer);
        doReturn(false).when(activity).isState(RESUMED);
        rootTask.onSizeCompatActivityChanged();
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer).onTaskInfoChanged(infoCaptor.capture());
        info = infoCaptor.getValue();
        assertEquals(rootTask.mTaskId, info.taskId);
        assertFalse(info.topActivityInSizeCompat);

        // Ensure task info show non size compat top activity as not in size compat.
        clearInvocations(organizer);
        doReturn(true).when(activity).isState(RESUMED);
        doReturn(false).when(activity).inSizeCompatMode();
        rootTask.onSizeCompatActivityChanged();
        mWm.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        verify(organizer).onTaskInfoChanged(infoCaptor.capture());
        info = infoCaptor.getValue();
        assertEquals(rootTask.mTaskId, info.taskId);
        assertFalse(info.topActivityInSizeCompat);
    }

    @Test
    public void testStartTasksInTransaction() {
        WindowContainerTransaction wct = new WindowContainerTransaction();
        ActivityOptions testOptions = ActivityOptions.makeBasic();
        testOptions.setTransientLaunch();
        wct.startTask(1, null /* options */);
        wct.startTask(2, testOptions.toBundle());
        spyOn(mWm.mAtmService.mTaskSupervisor);
        doReturn(START_CANCELED).when(mWm.mAtmService.mTaskSupervisor).startActivityFromRecents(
                anyInt(), anyInt(), anyInt(), any());
        clearInvocations(mWm.mAtmService);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);

        verify(mWm.mAtmService.mTaskSupervisor, times(1)).startActivityFromRecents(
                anyInt(), anyInt(), eq(1), any());

        final ArgumentCaptor<SafeActivityOptions> optionsCaptor =
                ArgumentCaptor.forClass(SafeActivityOptions.class);
        verify(mWm.mAtmService.mTaskSupervisor, times(1)).startActivityFromRecents(
                anyInt(), anyInt(), eq(2), optionsCaptor.capture());
        assertTrue(optionsCaptor.getValue().getOriginalOptions().getTransientLaunch());
    }

    @Test
    public void testResumeTopsWhenLeavingPinned() {
        final ActivityRecord record = makePipableActivity();
        final Task rootTask = record.getRootTask();

        clearInvocations(mWm.mAtmService.mRootWindowContainer);
        final WindowContainerTransaction t = new WindowContainerTransaction();
        WindowContainerToken wct = rootTask.mRemoteToken.toWindowContainerToken();
        t.setWindowingMode(wct, WINDOWING_MODE_PINNED);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        verify(mWm.mAtmService.mRootWindowContainer).resumeFocusedTasksTopActivities();

        clearInvocations(mWm.mAtmService.mRootWindowContainer);
        // The token for the PIP root task may have changed when the task entered PIP mode, so do
        // not reuse the one from above.
        final WindowContainerToken newToken =
                record.getRootTask().mRemoteToken.toWindowContainerToken();
        t.setWindowingMode(newToken, WINDOWING_MODE_FULLSCREEN);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        verify(mWm.mAtmService.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    /**
     * Verifies that task vanished is called for a specific task.
     */
    private void assertTaskVanished(ITaskOrganizer organizer, boolean expectVanished, Task... tasks)
            throws RemoteException {
        ArgumentCaptor<RunningTaskInfo> arg = ArgumentCaptor.forClass(RunningTaskInfo.class);
        verify(organizer, atLeastOnce()).onTaskVanished(arg.capture());
        List<RunningTaskInfo> taskInfos = arg.getAllValues();

        HashSet<Integer> vanishedTaskIds = new HashSet<>();
        for (int i = 0; i < taskInfos.size(); i++) {
            vanishedTaskIds.add(taskInfos.get(i).taskId);
        }
        HashSet<Integer> taskIds = new HashSet<>();
        for (int i = 0; i < tasks.length; i++) {
            taskIds.add(tasks[i].mTaskId);
        }

        assertTrue(expectVanished
                ? vanishedTaskIds.containsAll(taskIds)
                : !vanishedTaskIds.removeAll(taskIds));
    }

    private void assertContainsTasks(List<TaskAppearedInfo> taskInfos, Task... expectedTasks) {
        HashSet<Integer> taskIds = new HashSet<>();
        for (int i = 0; i < taskInfos.size(); i++) {
            taskIds.add(taskInfos.get(i).getTaskInfo().taskId);
        }
        for (int i = 0; i < expectedTasks.length; i++) {
            assertTrue(taskIds.contains(expectedTasks[i].mTaskId));
        }
    }
}
