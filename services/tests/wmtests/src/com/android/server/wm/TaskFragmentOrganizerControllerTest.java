/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.testing.Assert.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentOrganizerToken;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:TaskFragmentOrganizerControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskFragmentOrganizerControllerTest extends WindowTestsBase {

    private TaskFragmentOrganizerController mController;
    private TaskFragmentOrganizer mOrganizer;
    private TaskFragmentOrganizerToken mOrganizerToken;
    private ITaskFragmentOrganizer mIOrganizer;
    private TaskFragment mTaskFragment;
    private TaskFragmentInfo mTaskFragmentInfo;
    private IBinder mFragmentToken;
    private WindowContainerTransaction mTransaction;
    private WindowContainerToken mFragmentWindowToken;
    private RemoteAnimationDefinition mDefinition;

    @Before
    public void setup() {
        mController = mAtm.mWindowOrganizerController.mTaskFragmentOrganizerController;
        mOrganizer = new TaskFragmentOrganizer(Runnable::run);
        mOrganizerToken = mOrganizer.getOrganizerToken();
        mIOrganizer = ITaskFragmentOrganizer.Stub.asInterface(mOrganizerToken.asBinder());
        mTaskFragmentInfo = mock(TaskFragmentInfo.class);
        mFragmentToken = new Binder();
        mTaskFragment =
                new TaskFragment(mAtm, mFragmentToken, true /* createdByOrganizer */);
        mTransaction = new WindowContainerTransaction();
        mFragmentWindowToken = mTaskFragment.mRemoteToken.toWindowContainerToken();
        mDefinition = new RemoteAnimationDefinition();

        spyOn(mController);
        spyOn(mOrganizer);
        spyOn(mTaskFragment);
        doReturn(mIOrganizer).when(mTaskFragment).getTaskFragmentOrganizer();
        doReturn(mTaskFragmentInfo).when(mTaskFragment).getTaskFragmentInfo();
        doReturn(new SurfaceControl()).when(mTaskFragment).getSurfaceControl();
        doReturn(mFragmentToken).when(mTaskFragment).getFragmentToken();
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();
    }

    @Test
    public void testCallTaskFragmentCallbackWithoutRegister_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentInfoChanged(
                        mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentParentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                        mTaskFragment));
    }

    @Test
    public void testOnTaskFragmentAppeared() {
        mController.registerOrganizer(mIOrganizer);

        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentAppeared(any());
    }

    @Test
    public void testOnTaskFragmentInfoChanged() {
        mController.registerOrganizer(mIOrganizer);
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        // No callback if the info is not changed.
        doReturn(true).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTaskFragmentInfoChanged(any());

        // Trigger callback if the info is changed.
        doReturn(false).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentInfoChanged(mTaskFragmentInfo);
    }

    @Test
    public void testOnTaskFragmentVanished() {
        mController.registerOrganizer(mIOrganizer);

        mTaskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentVanished(any());
    }

    @Test
    public void testOnTaskFragmentParentInfoChanged() {
        mController.registerOrganizer(mIOrganizer);
        final Task parent = mock(Task.class);
        final Configuration parentConfig = new Configuration();
        parentConfig.smallestScreenWidthDp = 10;
        doReturn(parent).when(mTaskFragment).getParent();
        doReturn(parentConfig).when(parent).getConfiguration();
        doReturn(parent).when(parent).asTask();

        mTaskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentParentInfoChanged(eq(mFragmentToken), any());

        // No extra callback if the info is not changed.
        clearInvocations(mOrganizer);

        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTaskFragmentParentInfoChanged(any(), any());

        // Trigger callback if the info is changed.
        parentConfig.smallestScreenWidthDp = 100;

        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentParentInfoChanged(eq(mFragmentToken), any());
    }

    @Test
    public void testOnTaskFragmentError() throws RemoteException {
        final IBinder errorCallbackToken = new Binder();
        final Throwable exception = new IllegalArgumentException("Test exception");

        mController.registerOrganizer(mIOrganizer);
        mController.onTaskFragmentError(mTaskFragment.getTaskFragmentOrganizer(),
                errorCallbackToken, exception);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentError(eq(errorCallbackToken), eq(exception));
    }

    @Test
    public void testRegisterRemoteAnimations() {
        mController.registerOrganizer(mIOrganizer);
        mController.registerRemoteAnimations(mIOrganizer, mDefinition);

        assertEquals(mDefinition, mController.getRemoteAnimationDefinition(mIOrganizer));

        mController.unregisterRemoteAnimations(mIOrganizer);

        assertNull(mController.getRemoteAnimationDefinition(mIOrganizer));
    }

    @Test
    public void testApplyTransaction_enforceConfigurationChangeOnOrganizedTaskFragment()
            throws RemoteException {
        mOrganizer.applyTransaction(mTransaction);

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.setBounds(mFragmentWindowToken, new Rect(0, 0, 100, 100));

        assertThrows(SecurityException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
            } catch (RemoteException e) {
                fail();
            }
        });

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_reorder() throws RemoteException {
        mOrganizer.applyTransaction(mTransaction);

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.reorder(mFragmentWindowToken, true /* onTop */);

        assertThrows(SecurityException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
            } catch (RemoteException e) {
                fail();
            }
        });

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_deleteTaskFragment()
            throws RemoteException {
        mController.registerOrganizer(mIOrganizer);
        mOrganizer.applyTransaction(mTransaction);
        doReturn(true).when(mTaskFragment).isAttached();

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.deleteTaskFragment(mFragmentWindowToken);

        assertThrows(SecurityException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
            } catch (RemoteException e) {
                fail();
            }
        });

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        clearInvocations(mAtm.mRootWindowContainer);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        // No lifecycle update when the TaskFragment is not recorded.
        verify(mAtm.mRootWindowContainer, never()).resumeFocusedTasksTopActivities();

        mAtm.mWindowOrganizerController.mLaunchTaskFragments
                .put(mFragmentToken, mTaskFragment);
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_setAdjacentRoots()
            throws RemoteException {
        final TaskFragment taskFragment2 =
                new TaskFragment(mAtm, new Binder(), true /* createdByOrganizer */);
        final WindowContainerToken token2 = taskFragment2.mRemoteToken.toWindowContainerToken();
        mOrganizer.applyTransaction(mTransaction);

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.setAdjacentRoots(mFragmentWindowToken, token2, false /* moveTogether */);

        assertThrows(SecurityException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
            } catch (RemoteException e) {
                fail();
            }
        });

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        taskFragment2.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        clearInvocations(mAtm.mRootWindowContainer);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_createTaskFragment()
            throws RemoteException {
        mController.registerOrganizer(mIOrganizer);
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final int uid = Binder.getCallingUid();
        activity.info.applicationInfo.uid = uid;
        activity.getTask().effectiveUid = uid;
        final IBinder fragmentToken = new Binder();
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken, activity.token).build();
        mOrganizer.applyTransaction(mTransaction);

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        mTransaction.createTaskFragment(params);
        mTransaction.startActivityInTaskFragment(
                mFragmentToken, null /* callerToken */, new Intent(), null /* activityOptions */);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, mock(IBinder.class));
        mTransaction.setAdjacentTaskFragments(mFragmentToken, mock(IBinder.class),
                null /* options */);
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        // Successfully created a TaskFragment.
        final TaskFragment taskFragment = mAtm.mWindowOrganizerController
                .getTaskFragment(fragmentToken);
        assertNotNull(taskFragment);
        assertEquals(activity.getTask(), taskFragment.getTask());
    }

    @Test
    public void testApplyTransaction_createTaskFragment_failForDifferentUid()
            throws RemoteException {
        mController.registerOrganizer(mIOrganizer);
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final int uid = Binder.getCallingUid();
        final IBinder fragmentToken = new Binder();
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken, activity.token).build();
        mOrganizer.applyTransaction(mTransaction);
        mTransaction.createTaskFragment(params);

        // Fail to create TaskFragment when the task uid is different from caller.
        activity.info.applicationInfo.uid = uid;
        activity.getTask().effectiveUid = uid + 1;
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        assertNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));

        // Fail to create TaskFragment when the task uid is different from owner activity.
        activity.info.applicationInfo.uid = uid + 1;
        activity.getTask().effectiveUid = uid;
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        assertNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));

        // Successfully created a TaskFragment for same uid.
        activity.info.applicationInfo.uid = uid;
        activity.getTask().effectiveUid = uid;
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        assertNotNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_reparentChildren()
            throws RemoteException {
        mOrganizer.applyTransaction(mTransaction);
        mController.registerOrganizer(mIOrganizer);
        doReturn(true).when(mTaskFragment).isAttached();

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.reparentChildren(mFragmentWindowToken, null /* newParent */);

        assertThrows(SecurityException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
            } catch (RemoteException e) {
                fail();
            }
        });

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        clearInvocations(mAtm.mRootWindowContainer);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_reparentActivityToTaskFragment_triggerLifecycleUpdate()
            throws RemoteException {
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        mOrganizer.applyTransaction(mTransaction);
        mController.registerOrganizer(mIOrganizer);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setFragmentToken(mFragmentToken)
                .build();
        mAtm.mWindowOrganizerController.mLaunchTaskFragments
                .put(mFragmentToken, mTaskFragment);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, activity.appToken);
        clearInvocations(mAtm.mRootWindowContainer);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testDeferPendingTaskFragmentEventsOfInvisibleTask() {
        // Task - TaskFragment - Activity.
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .build();

        // Mock the task to invisible
        doReturn(false).when(task).shouldBeVisible(any());

        // Sending events
        mController.registerOrganizer(mIOrganizer);
        taskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();

        // Verifies that event was not sent
        verify(mOrganizer, never()).onTaskFragmentInfoChanged(any());
    }

    /**
     * Tests that a task fragment info changed event is still sent if the task is invisible only
     * when the info changed event is because of the last activity in a task finishing.
     */
    @Test
    public void testLastPendingTaskFragmentInfoChangedEventOfInvisibleTaskSent() {
        // Create a TaskFragment with an activity, all within a parent task
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .setCreateParentTask()
                .createActivityCount(1)
                .build();
        final Task parentTask = taskFragment.getTask();
        final ActivityRecord activity = taskFragment.getTopNonFinishingActivity();
        assertTrue(parentTask.shouldBeVisible(null));

        // Dispatch pending info changed event from creating the activity
        mController.registerOrganizer(mIOrganizer);
        taskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();

        // Finish the activity and verify that the task is invisible
        activity.finishing = true;
        assertFalse(parentTask.shouldBeVisible(null));

        // Verify the info changed callback still occurred despite the task being invisible
        reset(mOrganizer);
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTaskFragmentInfoChanged(any());
    }
}
