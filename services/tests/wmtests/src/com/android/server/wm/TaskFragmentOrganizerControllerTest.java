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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import android.window.WindowContainerTransactionCallback;

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
    public void testWindowContainerTransaction_setTaskFragmentOrganizer() {
        mOrganizer.applyTransaction(mTransaction);

        assertEquals(mIOrganizer, mTransaction.getTaskFragmentOrganizer());

        mTransaction = new WindowContainerTransaction();
        mOrganizer.applySyncTransaction(
                mTransaction, mock(WindowContainerTransactionCallback.class));

        assertEquals(mIOrganizer, mTransaction.getTaskFragmentOrganizer());
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
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* pid */);

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
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* pid */);

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
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* pid */);
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
        mTransaction.setAdjacentRoots(mFragmentWindowToken, token2);

        assertThrows(SecurityException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
            } catch (RemoteException e) {
                fail();
            }
        });

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* pid */);
        taskFragment2.setTaskFragmentOrganizer(mOrganizerToken, 10 /* pid */);
        clearInvocations(mAtm.mRootWindowContainer);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_createTaskFragment() {
        mOrganizer.applyTransaction(mTransaction);

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        final TaskFragmentCreationParams mockParams = mock(TaskFragmentCreationParams.class);
        doReturn(mOrganizerToken).when(mockParams).getOrganizer();
        mTransaction.createTaskFragment(mockParams);
        mTransaction.startActivityInTaskFragment(
                mFragmentToken, null /* callerToken */, new Intent(), null /* activityOptions */);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, mock(IBinder.class));
        mTransaction.setAdjacentTaskFragments(mFragmentToken, mock(IBinder.class),
                null /* options */);

        // It is expected to fail for the mock TaskFragmentCreationParams. It is ok as we are
        // testing the security check here.
        assertThrows(IllegalArgumentException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
            } catch (RemoteException e) {
                fail();
            }
        });
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
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* pid */);
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
}
