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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.TaskFragmentOperation.OP_TYPE_CREATE_TASK_FRAGMENT;
import static android.window.TaskFragmentOperation.OP_TYPE_CREATE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_DELETE_TASK_FRAGMENT;
import static android.window.TaskFragmentOperation.OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_REORDER_TO_BOTTOM_OF_TASK;
import static android.window.TaskFragmentOperation.OP_TYPE_REORDER_TO_FRONT;
import static android.window.TaskFragmentOperation.OP_TYPE_REORDER_TO_TOP_OF_TASK;
import static android.window.TaskFragmentOperation.OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_ANIMATION_PARAMS;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_DIM_ON_TASK;
import static android.window.TaskFragmentOperation.OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_OP_TYPE;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_THROWABLE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_CHANGE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_CLOSE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_NONE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_OPEN;
import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.TaskFragment.EMBEDDING_ALLOWED;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.testing.Assert.assertThrows;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.ITaskFragmentOrganizer;
import android.window.RemoteTransition;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentOrganizerToken;
import android.window.TaskFragmentParentInfo;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.List;

/**
 * Build/Install/Run:
 *  atest WmTests:TaskFragmentOrganizerControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskFragmentOrganizerControllerTest extends WindowTestsBase {

    private TaskFragmentOrganizerController mController;
    private WindowOrganizerController mWindowOrganizerController;
    private TransitionController mTransitionController;
    private TaskFragmentOrganizer mOrganizer;
    private TaskFragmentOrganizerToken mOrganizerToken;
    private ITaskFragmentOrganizer mIOrganizer;
    private TaskFragment mTaskFragment;
    private IBinder mFragmentToken;
    private WindowContainerTransaction mTransaction;
    private WindowContainerToken mFragmentWindowToken;
    private RemoteAnimationDefinition mDefinition;
    private IBinder mErrorToken;
    private Rect mTaskFragBounds;

    @Mock
    private TaskFragmentInfo mTaskFragmentInfo;
    @Mock
    private Task mTask;
    @Mock
    private IApplicationThread mAppThread;
    @Captor
    private ArgumentCaptor<TaskFragmentTransaction> mTransactionCaptor;

    @Before
    public void setup() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        removeGlobalMinSizeRestriction();
        mWindowOrganizerController = mAtm.mWindowOrganizerController;
        mTransitionController = mWindowOrganizerController.mTransitionController;
        mController = mWindowOrganizerController.mTaskFragmentOrganizerController;
        mOrganizer = new TaskFragmentOrganizer(Runnable::run);
        mOrganizerToken = mOrganizer.getOrganizerToken();
        mIOrganizer = ITaskFragmentOrganizer.Stub.asInterface(mOrganizerToken.asBinder());
        mFragmentToken = new Binder();
        mTaskFragment =
                new TaskFragment(mAtm, mFragmentToken, true /* createdByOrganizer */);
        mTransaction = new WindowContainerTransaction();
        mTransaction.setTaskFragmentOrganizer(mIOrganizer);
        mFragmentWindowToken = mTaskFragment.mRemoteToken.toWindowContainerToken();
        mDefinition = new RemoteAnimationDefinition();
        mErrorToken = new Binder();
        final Rect displayBounds = mDisplayContent.getBounds();
        mTaskFragBounds = new Rect(displayBounds.left, displayBounds.top, displayBounds.centerX(),
                displayBounds.centerY());

        spyOn(mController);
        spyOn(mOrganizer);
        spyOn(mTaskFragment);
        spyOn(mWindowOrganizerController);
        spyOn(mTransitionController);
        doReturn(mIOrganizer).when(mTaskFragment).getTaskFragmentOrganizer();
        doReturn(mTaskFragmentInfo).when(mTaskFragment).getTaskFragmentInfo();
        doReturn(new SurfaceControl()).when(mTaskFragment).getSurfaceControl();
        doReturn(mFragmentToken).when(mTaskFragment).getFragmentToken();
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();
        doReturn(mAppThread).when(mController).getAppThread(anyInt(), anyInt());
        doAnswer(invocation -> {
            final ITaskFragmentOrganizer organizer =
                    (ITaskFragmentOrganizer) invocation.getArguments()[0];
            final TaskFragmentTransaction taskFragmentTransaction =
                    (TaskFragmentTransaction) invocation.getArguments()[1];
            organizer.onTransactionReady(taskFragmentTransaction);
            return null;
        }).when(mAppThread).scheduleTaskFragmentTransaction(any(), any());

        // To prevent it from calling the real server.
        doNothing().when(mOrganizer).applyTransaction(any(), anyInt(), anyBoolean());
        doNothing().when(mOrganizer).onTransactionHandled(any(), any(), anyInt(), anyBoolean());

        registerTaskFragmentOrganizer(mIOrganizer);
    }

    @Test
    public void testCallTaskFragmentCallbackWithoutRegister_throwsException() {
        mController.unregisterOrganizer(mIOrganizer);

        doReturn(mTask).when(mTaskFragment).getTask();

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentInfoChanged(
                        mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));
    }

    @Test
    public void testOnTaskFragmentAppeared_throughTaskFragmentOrganizer() throws RemoteException {
        mSetFlagsRule.disableFlags(Flags.FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);

        // No-op when the TaskFragment is not attached.
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTransactionReady(any());
        verify(mAppThread, never()).scheduleTaskFragmentTransaction(any(), any());

        // Send callback when the TaskFragment is attached.
        setupMockParent(mTaskFragment, mTask);

        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTaskFragmentParentInfoChangedTransaction(mTask);
        assertTaskFragmentAppearedTransaction(false /* hasSurfaceControl */);
        verify(mAppThread, never()).scheduleTaskFragmentTransaction(any(), any());
    }

    @Test
    public void testOnTaskFragmentAppeared_throughApplicationThread() throws RemoteException  {
        mSetFlagsRule.enableFlags(Flags.FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);
        // Re-register the organizer in case the flag was disabled during setup.
        mController.unregisterOrganizer(mIOrganizer);
        registerTaskFragmentOrganizer(mIOrganizer);

        // No-op when the TaskFragment is not attached.
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTransactionReady(any());
        verify(mAppThread, never()).scheduleTaskFragmentTransaction(any(), any());

        // Send callback when the TaskFragment is attached.
        setupMockParent(mTaskFragment, mTask);

        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mAppThread).scheduleTaskFragmentTransaction(eq(mIOrganizer), any());
        assertTaskFragmentParentInfoChangedTransaction(mTask);
        assertTaskFragmentAppearedTransaction(false /* hasSurfaceControl */);
    }

    @Test
    public void testOnTaskFragmentAppeared_systemOrganizer() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG);

        mController.unregisterOrganizer(mIOrganizer);
        registerTaskFragmentOrganizer(mIOrganizer, true /* isSystemOrganizer */);

        // No-op when the TaskFragment is not attached.
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTransactionReady(any());

        // Send callback when the TaskFragment is attached.
        setupMockParent(mTaskFragment, mTask);

        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTaskFragmentParentInfoChangedTransaction(mTask);

        // System organizer should receive the SurfaceControl
        assertTaskFragmentAppearedTransaction(true /* hasSurfaceControl */);
    }

    @Test
    public void testOnTaskFragmentInfoChanged() {
        setupMockParent(mTaskFragment, mTask);

        // No-op if onTaskFragmentAppeared is not called yet.
        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTransactionReady(any());

        // Call onTaskFragmentAppeared first.
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTransactionReady(any());

        // No callback if the info is not changed.
        clearInvocations(mOrganizer);
        doReturn(true).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTransactionReady(any());

        // Trigger callback if the info is changed.
        doReturn(false).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.dispatchPendingEvents();

        assertTaskFragmentInfoChangedTransaction();
    }

    @Test
    public void testOnTaskFragmentVanished() {
        mTaskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTrue(mTaskFragment.mTaskFragmentVanishedSent);
        assertTaskFragmentVanishedTransaction();
    }

    @Test
    public void testOnTaskFragmentVanished_clearUpRemaining() {
        setupMockParent(mTaskFragment, mTask);

        // Not trigger onTaskFragmentAppeared.
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTrue(mTaskFragment.mTaskFragmentVanishedSent);
        assertTaskFragmentVanishedTransaction();

        // Not trigger onTaskFragmentInfoChanged.
        // Call onTaskFragmentAppeared before calling onTaskFragmentInfoChanged.
        mTaskFragment.mTaskFragmentVanishedSent = false;
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();
        clearInvocations(mOrganizer);
        doReturn(true).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());
        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTaskFragmentVanishedTransaction();
    }

    @Test
    public void testOnTaskFragmentParentInfoChanged() {
        setupMockParent(mTaskFragment, mTask);
        mTask.getTaskFragmentParentInfo().getConfiguration().smallestScreenWidthDp = 10;

        mController.onTaskFragmentAppeared(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTaskFragmentParentInfoChangedTransaction(mTask);

        // No extra parent info changed callback if the info is not changed.
        clearInvocations(mOrganizer);

        mController.onTaskFragmentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertEquals(1, changes.size());
        final TaskFragmentTransaction.Change change = changes.get(0);
        assertEquals(TYPE_TASK_FRAGMENT_INFO_CHANGED, change.getType());

        // Trigger callback if the size is changed.
        clearInvocations(mOrganizer);
        mTask.getTaskFragmentParentInfo().getConfiguration().smallestScreenWidthDp = 100;
        mController.onTaskFragmentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTaskFragmentParentInfoChangedTransaction(mTask);

        // Trigger callback if the windowing mode is changed.
        clearInvocations(mOrganizer);
        mTask.getTaskFragmentParentInfo().getConfiguration().windowConfiguration
                .setWindowingMode(WINDOWING_MODE_PINNED);
        mController.onTaskFragmentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        assertTaskFragmentParentInfoChangedTransaction(mTask);
    }

    @Test
    public void testOnTaskFragmentError() {
        final Throwable exception = new IllegalArgumentException("Test exception");

        mController.onTaskFragmentError(mTaskFragment.getTaskFragmentOrganizer(),
                mErrorToken, null /* taskFragment */, OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS,
                exception);
        mController.dispatchPendingEvents();

        assertTaskFragmentErrorTransaction(OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS,
                exception.getClass());
    }

    @Test
    public void testOnActivityReparentedToTask_activityInOrganizerProcess_useActivityToken() {
        // Make sure the activity pid/uid is the same as the organizer caller.
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        // Flush EVENT_APPEARED.
        mController.dispatchPendingEvents();
        final Task task = activity.getTask();
        activity.info.applicationInfo.uid = uid;
        doReturn(pid).when(activity).getPid();
        task.effectiveUid = uid;

        // No need to notify organizer if it is not embedded.
        mController.onActivityReparentedToTask(activity);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTransactionReady(any());

        // Notify organizer if it was embedded before entered Pip.
        activity.mLastTaskFragmentOrganizerBeforePip = mIOrganizer;
        mController.onActivityReparentedToTask(activity);
        mController.dispatchPendingEvents();

        assertActivityReparentedToTaskTransaction(task.mTaskId, activity.intent, activity.token);

        // Notify organizer if there is any embedded in the Task.
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .build();
        taskFragment.setTaskFragmentOrganizer(mOrganizer.getOrganizerToken(), uid,
                DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME);
        activity.reparent(taskFragment, POSITION_TOP);
        activity.mLastTaskFragmentOrganizerBeforePip = null;
        // Flush EVENT_INFO_CHANGED.
        mController.dispatchPendingEvents();

        // Clear invocations now because there will be another transaction for the TaskFragment
        // change above, triggered by the reparent. We only want to test onActivityReparentedToTask
        // here.
        clearInvocations(mOrganizer);
        mController.onActivityReparentedToTask(activity);
        mController.dispatchPendingEvents();

        assertActivityReparentedToTaskTransaction(task.mTaskId, activity.intent, activity.token);
    }

    @Test
    public void testOnActivityReparentedToTask_activityNotInOrganizerProcess_useTemporaryToken() {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final WindowProcessController organizerProc = mSystemServicesTestRule.addProcess(
                "pkg.organizer", DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME, pid, uid);
        mTaskFragment.setTaskFragmentOrganizer(mOrganizer.getOrganizerToken(), uid,
                DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME);
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final Task task = createTask(mDisplayContent);
        task.addChild(mTaskFragment, POSITION_TOP);
        final ActivityRecord activity = createActivityRecord(task);
        // Flush EVENT_APPEARED.
        mController.dispatchPendingEvents();

        // Even if the activity is not launched in an organized TaskFragment, it is still considered
        // as the remote activity to the organizer process. Because when the task becomes visible,
        // the organizer process needs to be interactive (unfrozen) to receive TaskFragment events.
        activity.setVisibleRequested(true);
        activity.setState(ActivityRecord.State.RESUMED, "test");
        assertTrue(organizerProc.hasVisibleActivities());
        activity.setVisibleRequested(false);
        activity.setState(ActivityRecord.State.STOPPED, "test");
        assertFalse(organizerProc.hasVisibleActivities());

        // Make sure the activity belongs to the same app, but it is in a different pid.
        activity.info.applicationInfo.uid = uid;
        doReturn(pid + 1).when(activity).getPid();
        task.effectiveUid = uid;

        // Notify organizer if it was embedded before entered Pip.
        // Create a temporary token since the activity doesn't belong to the same process.
        clearInvocations(mOrganizer);
        activity.mLastTaskFragmentOrganizerBeforePip = mIOrganizer;
        mController.onActivityReparentedToTask(activity);
        mController.dispatchPendingEvents();

        // Allow organizer to reparent activity in other process using the temporary token.
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());
        final TaskFragmentTransaction.Change change = changes.get(0);
        assertEquals(TYPE_ACTIVITY_REPARENTED_TO_TASK, change.getType());
        assertEquals(task.mTaskId, change.getTaskId());
        assertIntentsEqualForOrganizer(activity.intent, change.getActivityIntent());
        assertNotEquals(activity.token, change.getActivityToken());
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, change.getActivityToken());
        assertApplyTransactionAllowed(mTransaction);

        assertEquals(mTaskFragment, activity.getTaskFragment());
        // The temporary token can only be used once.
        assertNull(mController.getReparentActivityFromTemporaryToken(mIOrganizer,
                change.getActivityToken()));

        // The organizer process should also have visible state by the visible activity in a
        // different process.
        activity.setVisibleRequested(true);
        activity.setState(ActivityRecord.State.RESUMED, "test");
        assertTrue(organizerProc.hasVisibleActivities());
        activity.setVisibleRequested(false);
        activity.setState(ActivityRecord.State.STOPPED, "test");
        assertFalse(organizerProc.hasVisibleActivities());
    }

    @Test
    public void testOnActivityReparentedToTask_untrustedEmbed_notReported() {
        final ActivityRecord activity = setupUntrustedEmbeddingPipReparent();
        doReturn(false).when(activity).isUntrustedEmbeddingStateSharingAllowed();

        // Notify organizer if it was embedded before entered Pip.
        // Create a temporary token since the activity doesn't belong to the same process.
        mController.onActivityReparentedToTask(activity);
        mController.dispatchPendingEvents();

        // Disallow organizer to reparent activity that is untrusted embedded.
        verify(mOrganizer, never()).onTransactionReady(mTransactionCaptor.capture());
    }

    @Test
    public void testOnActivityReparentedToTask_untrustedEmbed_reportedWhenAppOptIn() {
        final ActivityRecord activity = setupUntrustedEmbeddingPipReparent();
        doReturn(true).when(activity).isUntrustedEmbeddingStateSharingAllowed();

        // Notify organizer if it was embedded before entered Pip.
        // Create a temporary token since the activity doesn't belong to the same process.
        mController.onActivityReparentedToTask(activity);
        mController.dispatchPendingEvents();

        // Allow organizer to reparent activity in other process using the temporary token.
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());
        final TaskFragmentTransaction.Change change = changes.get(0);
        assertEquals(TYPE_ACTIVITY_REPARENTED_TO_TASK, change.getType());
        assertEquals(activity.getTask().mTaskId, change.getTaskId());
        assertIntentsEqualForOrganizer(activity.intent, change.getActivityIntent());
        assertNotEquals(activity.token, change.getActivityToken());
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, change.getActivityToken());
        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testOnActivityReparentedToTask_trimReportedIntent() {
        // Make sure the activity pid/uid is the same as the organizer caller.
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final Task task = activity.getTask();
        activity.info.applicationInfo.uid = uid;
        doReturn(pid).when(activity).getPid();
        task.effectiveUid = uid;
        activity.mLastTaskFragmentOrganizerBeforePip = mIOrganizer;

        // Test the Intent trim in #assertIntentTrimmed
        activity.intent.setComponent(new ComponentName("TestPackage", "TestClass"))
                .setPackage("TestPackage")
                .setAction("TestAction")
                .setData(mock(Uri.class))
                .putExtra("Test", 123)
                .setFlags(10);

        mController.onActivityReparentedToTask(activity);
        mController.dispatchPendingEvents();

        assertActivityReparentedToTaskTransaction(task.mTaskId, activity.intent, activity.token);
    }

    @Test
    public void testRegisterRemoteAnimations() {
        mController.registerRemoteAnimations(mIOrganizer, mDefinition);

        assertEquals(mDefinition, mController.getRemoteAnimationDefinition(mIOrganizer));

        mController.unregisterRemoteAnimations(mIOrganizer);

        assertNull(mController.getRemoteAnimationDefinition(mIOrganizer));
    }

    @Test
    public void testApplyTransaction_disallowRemoteTransitionForNonSystemOrganizer() {
        mTransaction.setRelativeBounds(mFragmentWindowToken, new Rect(0, 0, 100, 100));
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        // Throw exception if the transaction has remote transition and is not requested by system
        // organizer
        assertThrows(SecurityException.class, () ->
                mController.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                        true /* shouldApplyIndependently */,
                        new RemoteTransition(mock(IRemoteTransition.class))));
    }

    @Test
    public void testApplyTransaction_allowRemoteTransitionForSystemOrganizer() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG);

        mController.unregisterOrganizer(mIOrganizer);
        registerTaskFragmentOrganizer(mIOrganizer, true /* isSystemOrganizer */);

        mTransaction.setRelativeBounds(mFragmentWindowToken, new Rect(0, 0, 100, 100));
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        // Remote transition is allowed for system organizer
        mController.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                true /* shouldApplyIndependently */,
                new RemoteTransition(mock(IRemoteTransition.class)));
    }

    @Test
    public void testApplyTransaction_enforceConfigurationChangeOnOrganizedTaskFragment() {
        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.setRelativeBounds(mFragmentWindowToken, new Rect(0, 0, 100, 100));

        assertApplyTransactionDisallowed(mTransaction);

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_deleteTaskFragment() {
        doReturn(true).when(mTaskFragment).isAttached();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.deleteTaskFragment(mFragmentToken);

        assertApplyTransactionDisallowed(mTransaction);

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        clearInvocations(mAtm.mRootWindowContainer);

        assertApplyTransactionAllowed(mTransaction);
        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_createTaskFragment() {
        final ActivityRecord ownerActivity = createActivityRecord(mDisplayContent);

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        createTaskFragmentFromOrganizer(mTransaction, ownerActivity, mFragmentToken);
        mTransaction.startActivityInTaskFragment(
                mFragmentToken, null /* callerToken */, new Intent(), null /* activityOptions */);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, mock(IBinder.class));
        mTransaction.setAdjacentTaskFragments(mFragmentToken, mock(IBinder.class),
                null /* options */);
        mTransaction.clearAdjacentTaskFragments(mFragmentToken);
        assertApplyTransactionAllowed(mTransaction);

        // Successfully created a TaskFragment.
        final TaskFragment taskFragment = mWindowOrganizerController.getTaskFragment(
                mFragmentToken);
        assertNotNull(taskFragment);
        assertEquals(ownerActivity.getTask(), taskFragment.getTask());
    }

    @Test
    public void testApplyTransaction_enforceTaskFragmentOrganized_startActivityInTaskFragment() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord ownerActivity = createActivityRecord(task);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        mTransaction.startActivityInTaskFragment(
                mFragmentToken, ownerActivity.token, new Intent(), null /* activityOptions */);
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_OPEN,
                false /* shouldApplyIndependently */);

        // Not allowed because TaskFragment is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceTaskFragmentOrganized_reparentActivityInTaskFragment() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(task);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, activity.token);
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);

        // Not allowed because TaskFragment is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceTaskFragmentOrganized_setAdjacentTaskFragments() {
        final Task task = createTask(mDisplayContent);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final IBinder fragmentToken2 = new Binder();
        final TaskFragment taskFragment2 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(fragmentToken2)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(fragmentToken2, taskFragment2);
        mTransaction.setAdjacentTaskFragments(mFragmentToken, fragmentToken2, null /* params */);
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);

        // Not allowed because TaskFragments are not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);
        assertNull(mTaskFragment.getAdjacentTaskFragment());
        assertNull(taskFragment2.getAdjacentTaskFragment());

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        // Not allowed because TaskFragment2 is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);
        assertNull(mTaskFragment.getAdjacentTaskFragment());
        assertNull(taskFragment2.getAdjacentTaskFragment());

        mTaskFragment.onTaskFragmentOrganizerRemoved();
        taskFragment2.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        // Not allowed because mTaskFragment is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);
        assertNull(mTaskFragment.getAdjacentTaskFragment());
        assertNull(taskFragment2.getAdjacentTaskFragment());

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
        assertEquals(taskFragment2, mTaskFragment.getAdjacentTaskFragment());
    }

    @Test
    public void testApplyTransaction_enforceTaskFragmentOrganized_clearAdjacentTaskFragments() {
        final Task task = createTask(mDisplayContent);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final IBinder fragmentToken2 = new Binder();
        final TaskFragment taskFragment2 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(fragmentToken2)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(fragmentToken2, taskFragment2);
        mTaskFragment.setAdjacentTaskFragment(taskFragment2);

        mTransaction.clearAdjacentTaskFragments(mFragmentToken);
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);

        // Not allowed because TaskFragment is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);
        assertEquals(taskFragment2, mTaskFragment.getAdjacentTaskFragment());

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
        assertNull(mTaskFragment.getAdjacentTaskFragment());
        assertNull(taskFragment2.getAdjacentTaskFragment());
    }

    @Test
    public void testApplyTransaction_enforceTaskFragmentOrganized_requestFocusOnTaskFragment() {
        final Task task = createTask(mDisplayContent);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        mTransaction.requestFocusOnTaskFragment(mFragmentToken);
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);

        // Not allowed because TaskFragment is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceTaskFragmentOrganized_addTaskFragmentOperation() {
        final Task task = createTask(mDisplayContent);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_ANIMATION_PARAMS)
                .setAnimationParams(TaskFragmentAnimationParams.DEFAULT)
                .build();
        mTransaction.addTaskFragmentOperation(mFragmentToken, operation);
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);

        // Not allowed because TaskFragment is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testAddTaskFragmentOperation() {
        final Task task = createTask(mDisplayContent);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .build();
        assertEquals(TaskFragmentAnimationParams.DEFAULT, mTaskFragment.getAnimationParams());

        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final TaskFragmentAnimationParams animationParams =
                new TaskFragmentAnimationParams.Builder()
                        .setAnimationBackgroundColor(Color.GREEN)
                        .build();
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_ANIMATION_PARAMS)
                .setAnimationParams(animationParams)
                .build();
        mTransaction.addTaskFragmentOperation(mFragmentToken, operation);
        final TaskFragmentOperation dimOperation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_DIM_ON_TASK)
                .setDimOnTask(true)
                .build();
        mTransaction.addTaskFragmentOperation(mFragmentToken, dimOperation);
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);
        assertApplyTransactionAllowed(mTransaction);

        assertEquals(animationParams, mTaskFragment.getAnimationParams());
        assertEquals(Color.GREEN, mTaskFragment.getAnimationParams().getAnimationBackgroundColor());

        assertTrue(mTaskFragment.isDimmingOnParentTask());
    }

    @Test
    public void testApplyTransaction_createTaskFragment_failForDifferentUid() {
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final int uid = Binder.getCallingUid();
        final IBinder fragmentToken = new Binder();
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken, activity.token).build();
        mTransaction.createTaskFragment(params);

        // Fail to create TaskFragment when the task uid is different from caller.
        activity.info.applicationInfo.uid = uid;
        activity.getTask().effectiveUid = uid + 1;
        assertApplyTransactionAllowed(mTransaction);

        assertNull(mWindowOrganizerController.getTaskFragment(fragmentToken));

        // Fail to create TaskFragment when the task uid is different from owner activity.
        activity.info.applicationInfo.uid = uid + 1;
        activity.getTask().effectiveUid = uid;
        assertApplyTransactionAllowed(mTransaction);

        assertNull(mWindowOrganizerController.getTaskFragment(fragmentToken));

        // Successfully created a TaskFragment for same uid.
        activity.info.applicationInfo.uid = uid;
        activity.getTask().effectiveUid = uid;
        assertApplyTransactionAllowed(mTransaction);

        assertNotNull(mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    @Test
    public void testApplyTransaction_createTaskFragment_withPairedPrimaryFragmentToken() {
        final Task task = createTask(mDisplayContent);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .createActivityCount(1)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final ActivityRecord activityOnTop = createActivityRecord(task);
        final int uid = Binder.getCallingUid();
        activityOnTop.info.applicationInfo.uid = uid;
        activityOnTop.getTask().effectiveUid = uid;
        final IBinder fragmentToken1 = new Binder();
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken1, activityOnTop.token)
                .setPairedPrimaryFragmentToken(mFragmentToken)
                .build();
        mTransaction.setTaskFragmentOrganizer(mIOrganizer);
        mTransaction.createTaskFragment(params);
        assertApplyTransactionAllowed(mTransaction);

        // Successfully created a TaskFragment.
        final TaskFragment taskFragment = mWindowOrganizerController.getTaskFragment(
                fragmentToken1);
        assertNotNull(taskFragment);
        // The new TaskFragment should be positioned right above the paired TaskFragment.
        assertEquals(task.mChildren.indexOf(mTaskFragment) + 1,
                task.mChildren.indexOf(taskFragment));
        // The top activity should remain on top.
        assertEquals(task.mChildren.indexOf(taskFragment) + 1,
                task.mChildren.indexOf(activityOnTop));
    }

    @Test
    public void testApplyTransaction_createTaskFragment_overrideBounds() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord activityAtBottom = createActivityRecord(task);
        final int uid = Binder.getCallingUid();
        activityAtBottom.info.applicationInfo.uid = uid;
        activityAtBottom.getTask().effectiveUid = uid;
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .createActivityCount(1)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final IBinder fragmentToken1 = new Binder();
        final Rect bounds = new Rect(100, 100, 500, 1000);
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken1, activityAtBottom.token)
                .setPairedActivityToken(activityAtBottom.token)
                .setInitialRelativeBounds(bounds)
                .build();
        mTransaction.setTaskFragmentOrganizer(mIOrganizer);
        mTransaction.createTaskFragment(params);
        assertApplyTransactionAllowed(mTransaction);

        // Successfully created a TaskFragment.
        final TaskFragment taskFragment = mWindowOrganizerController.getTaskFragment(
                fragmentToken1);
        assertNotNull(taskFragment);
        // The relative embedded bounds is updated to the initial requested bounds.
        assertEquals(bounds, taskFragment.getRelativeEmbeddedBounds());
    }

    @Test
    public void testApplyTransaction_createTaskFragment_withPairedActivityToken() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord activityAtBottom = createActivityRecord(task);
        final int uid = Binder.getCallingUid();
        activityAtBottom.info.applicationInfo.uid = uid;
        activityAtBottom.getTask().effectiveUid = uid;
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .createActivityCount(1)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final IBinder fragmentToken1 = new Binder();
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken1, activityAtBottom.token)
                .setPairedActivityToken(activityAtBottom.token)
                .build();
        mTransaction.setTaskFragmentOrganizer(mIOrganizer);
        mTransaction.createTaskFragment(params);
        assertApplyTransactionAllowed(mTransaction);

        // Successfully created a TaskFragment.
        final TaskFragment taskFragment = mWindowOrganizerController.getTaskFragment(
                fragmentToken1);
        assertNotNull(taskFragment);
        // The new TaskFragment should be positioned right above the paired activity.
        assertEquals(task.mChildren.indexOf(activityAtBottom) + 1,
                task.mChildren.indexOf(taskFragment));
        // The top TaskFragment should remain on top.
        assertEquals(task.mChildren.indexOf(taskFragment) + 1,
                task.mChildren.indexOf(mTaskFragment));
    }

    @Test
    public void testApplyTransaction_reparentActivityToTaskFragment_triggerLifecycleUpdate() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(task);
        // Skip manipulate the SurfaceControl.
        doNothing().when(activity).setDropInputMode(anyInt());
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .setOrganizer(mOrganizer)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, activity.token);
        doReturn(EMBEDDING_ALLOWED).when(mTaskFragment).isAllowedToEmbedActivity(activity);
        clearInvocations(mAtm.mRootWindowContainer);

        assertApplyTransactionAllowed(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_requestFocusOnTaskFragment() {
        final Task task = createTask(mDisplayContent);
        final IBinder token0 = new Binder();
        final TaskFragment tf0 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(token0)
                .setOrganizer(mOrganizer)
                .createActivityCount(1)
                .build();
        final IBinder token1 = new Binder();
        final TaskFragment tf1 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(token1)
                .setOrganizer(mOrganizer)
                .createActivityCount(1)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(token0, tf0);
        mWindowOrganizerController.mLaunchTaskFragments.put(token1, tf1);
        final ActivityRecord activity0 = tf0.getTopMostActivity();
        final ActivityRecord activity1 = tf1.getTopMostActivity();

        // No effect if the current focus is in a different Task.
        final ActivityRecord activityInOtherTask = createActivityRecord(mDefaultDisplay);
        mDisplayContent.setFocusedApp(activityInOtherTask);
        mTransaction.requestFocusOnTaskFragment(token0);
        assertApplyTransactionAllowed(mTransaction);

        assertEquals(activityInOtherTask, mDisplayContent.mFocusedApp);

        // No effect if there is no resumed activity in the request TaskFragment.
        activity0.setState(ActivityRecord.State.PAUSED, "test");
        activity1.setState(ActivityRecord.State.RESUMED, "test");
        mDisplayContent.setFocusedApp(activity1);
        assertApplyTransactionAllowed(mTransaction);

        assertEquals(activity1, mDisplayContent.mFocusedApp);

        // Set focus to the request TaskFragment when the current focus is in the same Task, and it
        // has a resumed activity.
        activity0.setState(ActivityRecord.State.RESUMED, "test");
        mDisplayContent.setFocusedApp(activity1);
        assertApplyTransactionAllowed(mTransaction);

        assertEquals(activity0, mDisplayContent.mFocusedApp);
    }

    @Test
    public void testApplyTransaction_finishActivity() {
        final ActivityRecord activity = createActivityRecord(mDisplayContent);

        mTransaction.finishActivity(activity.token);
        assertApplyTransactionAllowed(mTransaction);

        assertTrue(activity.finishing);
    }

    @Test
    public void testApplyTransaction_skipTransactionForUnregisterOrganizer() {
        mController.unregisterOrganizer(mIOrganizer);
        final ActivityRecord ownerActivity = createActivityRecord(mDisplayContent);
        final IBinder fragmentToken = new Binder();

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        createTaskFragmentFromOrganizer(mTransaction, ownerActivity, fragmentToken);
        assertApplyTransactionAllowed(mTransaction);

        // Nothing should happen as the organizer is not registered.
        assertNull(mWindowOrganizerController.getTaskFragment(fragmentToken));

        registerTaskFragmentOrganizer(mIOrganizer);
        assertApplyTransactionAllowed(mTransaction);

        // Successfully created when the organizer is registered.
        assertNotNull(mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    @Test
    public void testOnTransactionHandled_skipTransactionForUnregisterOrganizer() {
        mController.unregisterOrganizer(mIOrganizer);
        final ActivityRecord ownerActivity = createActivityRecord(mDisplayContent);
        final IBinder fragmentToken = new Binder();

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        createTaskFragmentFromOrganizer(mTransaction, ownerActivity, fragmentToken);
        mController.onTransactionHandled(new Binder(), mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);

        // Nothing should happen as the organizer is not registered.
        assertNull(mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    @Test
    public void testOrganizerRemovedWithPendingEvents() {
        final TaskFragment tf0 = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .build();
        final TaskFragment tf1 = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        assertTrue(tf0.isOrganizedTaskFragment());
        assertTrue(tf1.isOrganizedTaskFragment());
        assertTrue(tf0.isAttached());
        assertTrue(tf0.isAttached());

        // Mock the behavior that remove TaskFragment can trigger event dispatch.
        final Answer<Void> removeImmediately = invocation -> {
            invocation.callRealMethod();
            mController.dispatchPendingEvents();
            return null;
        };
        doAnswer(removeImmediately).when(tf0).removeImmediately();
        doAnswer(removeImmediately).when(tf1).removeImmediately();

        // Add pending events.
        mController.onTaskFragmentAppeared(mIOrganizer, tf0);
        mController.onTaskFragmentAppeared(mIOrganizer, tf1);

        // Remove organizer.
        mController.unregisterOrganizer(mIOrganizer);
        mController.dispatchPendingEvents();

        // Nothing should happen after the organizer is removed.
        verify(mOrganizer, never()).onTransactionReady(any());

        // TaskFragments should be removed.
        assertFalse(tf0.isOrganizedTaskFragment());
        assertFalse(tf1.isOrganizedTaskFragment());
        assertFalse(tf0.isAttached());
        assertFalse(tf0.isAttached());
    }

    @Test
    public void testTaskFragmentInPip_startActivityInTaskFragment() {
        setupTaskFragmentInPip();
        final ActivityRecord activity = mTaskFragment.getTopMostActivity();
        spyOn(mAtm.getActivityStartController());
        spyOn(mWindowOrganizerController);

        // Not allow to start activity in a TaskFragment that is in a PIP Task.
        mTransaction.startActivityInTaskFragment(
                        mFragmentToken, activity.token, new Intent(), null /* activityOptions */)
                .setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);

        verify(mAtm.getActivityStartController(), never()).startActivityInTaskFragment(any(), any(),
                any(), any(), anyInt(), anyInt(), any());
        verify(mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(mErrorToken), eq(mTaskFragment),
                eq(OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT),
                any(IllegalArgumentException.class));
    }

    @Test
    public void testTaskFragmentInPip_reparentActivityToTaskFragment() {
        setupTaskFragmentInPip();
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        spyOn(mWindowOrganizerController);

        // Not allow to reparent activity to a TaskFragment that is in a PIP Task.
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, activity.token)
                .setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);

        verify(mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(mErrorToken), eq(mTaskFragment),
                eq(OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT),
                any(IllegalArgumentException.class));
        assertNull(activity.getOrganizedTaskFragment());
    }

    @Test
    public void testTaskFragmentInPip_setAdjacentTaskFragment() {
        setupTaskFragmentInPip();
        spyOn(mWindowOrganizerController);

        // Not allow to set adjacent on a TaskFragment that is in a PIP Task.
        mTransaction.setAdjacentTaskFragments(mFragmentToken, new Binder(), null /* options */)
                .setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);

        verify(mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(mErrorToken), eq(mTaskFragment), eq(OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS),
                any(IllegalArgumentException.class));
        verify(mTaskFragment, never()).setAdjacentTaskFragment(any());
    }

    @Test
    public void testTaskFragmentInPip_createTaskFragment() {
        final Task pipTask = createTask(mDisplayContent, WINDOWING_MODE_PINNED,
                ACTIVITY_TYPE_STANDARD);
        final ActivityRecord activity = createActivityRecord(pipTask);
        final IBinder fragmentToken = new Binder();
        spyOn(mWindowOrganizerController);

        // Not allow to create TaskFragment in a PIP Task.
        createTaskFragmentFromOrganizer(mTransaction, activity, fragmentToken);
        mTransaction.setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);

        verify(mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(mErrorToken), eq(null), eq(OP_TYPE_CREATE_TASK_FRAGMENT),
                any(IllegalArgumentException.class));
        assertNull(mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    @Test
    public void testTaskFragmentInPip_deleteTaskFragment() {
        setupTaskFragmentInPip();
        spyOn(mWindowOrganizerController);

        // Not allow to delete a TaskFragment that is in a PIP Task.
        mTransaction.deleteTaskFragment(mFragmentToken)
                .setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);

        verify(mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(mErrorToken), eq(mTaskFragment), eq(OP_TYPE_DELETE_TASK_FRAGMENT),
                any(IllegalArgumentException.class));
        assertNotNull(mWindowOrganizerController.getTaskFragment(mFragmentToken));

        // Allow organizer to delete empty TaskFragment for cleanup.
        final Task task = mTaskFragment.getTask();
        mTaskFragment.removeChild(mTaskFragment.getTopMostActivity());
        assertApplyTransactionAllowed(mTransaction);

        assertNull(mWindowOrganizerController.getTaskFragment(mFragmentToken));
        assertNull(task.getTopChild());
    }

    @Test
    public void testTaskFragmentInPip_setConfig() {
        setupTaskFragmentInPip();
        spyOn(mWindowOrganizerController);

        // Set relative bounds is ignored on a TaskFragment that is in a PIP Task.
        mTransaction.setRelativeBounds(mFragmentWindowToken, new Rect(0, 0, 100, 100));

        assertApplyTransactionAllowed(mTransaction);

        verify(mTaskFragment, never()).setRelativeEmbeddedBounds(any());
    }

    @Test
    public void testDeferPendingTaskFragmentEventsOfInvisibleTask() {
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .build();
        doReturn(false).when(task).shouldBeVisible(any());

        // Dispatch the initial event in the Task to update the Task visibility to the organizer.
        mController.onTaskFragmentAppeared(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTransactionReady(any());

        // Verify that events were not sent when the Task is in background.
        clearInvocations(mOrganizer);
        final Rect bounds = new Rect(0, 0, 500, 1000);
        task.setBoundsUnchecked(bounds);
        mController.onTaskFragmentParentInfoChanged(mIOrganizer, task);
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer, never()).onTransactionReady(any());

        // Verify that the events were sent when the Task becomes visible.
        doReturn(true).when(task).shouldBeVisible(any());
        task.lastActiveTime++;
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTransactionReady(any());
    }

    @Test
    public void testSendAllPendingTaskFragmentEventsWhenAnyTaskIsVisible() {
        // Invisible Task.
        final Task invisibleTask = createTask(mDisplayContent);
        final TaskFragment invisibleTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(invisibleTask)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .build();
        doReturn(false).when(invisibleTask).shouldBeVisible(any());

        // Visible Task.
        final IBinder fragmentToken = new Binder();
        final Task visibleTask = createTask(mDisplayContent);
        final TaskFragment visibleTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(visibleTask)
                .setOrganizer(mOrganizer)
                .setFragmentToken(fragmentToken)
                .build();
        doReturn(true).when(invisibleTask).shouldBeVisible(any());

        // Sending events
        invisibleTaskFragment.mTaskFragmentAppearedSent = true;
        visibleTaskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentInfoChanged(mIOrganizer, invisibleTaskFragment);
        mController.onTaskFragmentInfoChanged(mIOrganizer, visibleTaskFragment);
        mController.dispatchPendingEvents();

        // Verify that both events are sent.
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();

        // There should be two Task info changed with two TaskFragment info changed.
        assertEquals(4, changes.size());
        // Invisible Task info changed
        assertEquals(TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED, changes.get(0).getType());
        assertEquals(invisibleTask.mTaskId, changes.get(0).getTaskId());
        // Invisible TaskFragment info changed
        assertEquals(TYPE_TASK_FRAGMENT_INFO_CHANGED, changes.get(1).getType());
        assertEquals(invisibleTaskFragment.getFragmentToken(),
                changes.get(1).getTaskFragmentToken());
        // Visible Task info changed
        assertEquals(TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED, changes.get(2).getType());
        assertEquals(visibleTask.mTaskId, changes.get(2).getTaskId());
        // Visible TaskFragment info changed
        assertEquals(TYPE_TASK_FRAGMENT_INFO_CHANGED, changes.get(3).getType());
        assertEquals(visibleTaskFragment.getFragmentToken(), changes.get(3).getTaskFragmentToken());
    }

    @Test
    public void testCanSendPendingTaskFragmentEventsAfterActivityResumed() {
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .createActivityCount(1)
                .build();
        final ActivityRecord activity = taskFragment.getTopMostActivity();
        doReturn(false).when(task).shouldBeVisible(any());
        taskFragment.setResumedActivity(null, "test");

        // Dispatch the initial event in the Task to update the Task visibility to the organizer.
        mController.onTaskFragmentAppeared(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTransactionReady(any());

        // Verify the info changed event is not sent because the Task is invisible
        clearInvocations(mOrganizer);
        final Rect bounds = new Rect(0, 0, 500, 1000);
        task.setBoundsUnchecked(bounds);
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer, never()).onTransactionReady(any());

        // Mock the task becomes visible, and activity resumed. Verify the info changed event is
        // sent.
        doReturn(true).when(task).shouldBeVisible(any());
        taskFragment.setResumedActivity(activity, "test");
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTransactionReady(any());
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
        taskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();

        // Finish the activity and verify that the task is invisible
        activity.finishing = true;
        assertFalse(parentTask.shouldBeVisible(null));

        // Verify the info changed callback still occurred despite the task being invisible
        clearInvocations(mOrganizer);
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTransactionReady(any());
    }

    /**
     * Tests that a task fragment info changed event is sent if the TaskFragment becomes empty
     * even if the Task is invisible.
     */
    @Test
    public void testPendingTaskFragmentInfoChangedEvent_emptyTaskFragment() {
        // Create a TaskFragment with an activity, all within a parent task
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .createActivityCount(1)
                .build();
        final ActivityRecord embeddedActivity = taskFragment.getTopNonFinishingActivity();
        // Add another activity in the Task so that it always contains a non-finishing activity.
        createActivityRecord(task);
        doReturn(false).when(task).shouldBeVisible(any());

        // Dispatch the initial event in the Task to update the Task visibility to the organizer.
        clearInvocations(mOrganizer);
        mController.onTaskFragmentAppeared(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTransactionReady(any());

        // Verify the info changed event is not sent because the Task is invisible
        clearInvocations(mOrganizer);
        final Rect bounds = new Rect(0, 0, 500, 1000);
        task.setBoundsUnchecked(bounds);
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer, never()).onTransactionReady(any());

        // Finish the embedded activity, and verify the info changed event is sent because the
        // TaskFragment is becoming empty.
        embeddedActivity.finishing = true;
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTransactionReady(any());
    }

    /**
     * When an embedded {@link TaskFragment} is removed, we should clean up the reference in the
     * {@link WindowOrganizerController}.
     */
    @Test
    public void testTaskFragmentRemoved_cleanUpEmbeddedTaskFragment() {
        final ActivityRecord ownerActivity = createActivityRecord(mDisplayContent);
        final IBinder fragmentToken = new Binder();
        createTaskFragmentFromOrganizer(mTransaction, ownerActivity, fragmentToken);
        assertApplyTransactionAllowed(mTransaction);
        final TaskFragment taskFragment = mWindowOrganizerController.getTaskFragment(fragmentToken);

        assertNotNull(taskFragment);

        taskFragment.removeImmediately();

        assertNull(mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    /**
     * For config change to untrusted embedded TaskFragment, the bounds should be always within
     * its parent bounds.
     */
    @Test
    public void testUntrustedEmbedding_configChange() {
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        doReturn(false).when(mTaskFragment).isAllowedToBeEmbeddedInTrustedMode();
        final Task task = createTask(mDisplayContent);
        final Rect taskBounds = new Rect(task.getBounds());
        task.addChild(mTaskFragment, POSITION_TOP);

        // When set a relative bounds outside of its parent's, it is allowed, but the actual
        // TaskFragment bounds will be updated to be fit the parent's bounds.
        final Rect tfBounds = new Rect(taskBounds);
        tfBounds.right++;
        mTransaction.setRelativeBounds(mFragmentWindowToken, tfBounds);
        assertApplyTransactionAllowed(mTransaction);

        assertEquals(tfBounds, mTaskFragment.getRelativeEmbeddedBounds());
        assertEquals(taskBounds, mTaskFragment.getBounds());
    }

    // TODO(b/232871351): add test for minimum dimension violation in startActivityInTaskFragment
    @Test
    public void testMinDimensionViolation_ReparentActivityToTaskFragment() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(task);
        // Make minWidth/minHeight exceeds the TaskFragment bounds.
        activity.info.windowLayout = new ActivityInfo.WindowLayout(
                0, 0, 0, 0, 0, mTaskFragBounds.width() + 10, mTaskFragBounds.height() + 10);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .setOrganizer(mOrganizer)
                .setBounds(mTaskFragBounds)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);

        // Reparent activity to mTaskFragment, which is smaller than activity's
        // minimum dimensions.
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, activity.token)
                .setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);
        // The pending event will be dispatched on the handler (from requestTraversal).
        waitHandlerIdle(mWm.mAnimationHandler);

        assertTaskFragmentErrorTransaction(OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT,
                SecurityException.class);
    }

    @Test
    public void testMinDimensionViolation_setRelativeBounds() {
        final Task task = createTask(mDisplayContent);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(1)
                .setFragmentToken(mFragmentToken)
                .setOrganizer(mOrganizer)
                .setBounds(new Rect(0, 0, mTaskFragBounds.right * 2, mTaskFragBounds.bottom * 2))
                .build();
        final ActivityRecord activity = mTaskFragment.getTopMostActivity();
        // Make minWidth/minHeight exceeds the TaskFragment bounds.
        activity.info.windowLayout = new ActivityInfo.WindowLayout(
                0, 0, 0, 0, 0, mTaskFragBounds.width() + 10, mTaskFragBounds.height() + 10);
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        clearInvocations(mAtm.mRootWindowContainer);

        // Shrink the TaskFragment to mTaskFragBounds to make its bounds smaller than activity's
        // minimum dimensions.
        mTransaction.setRelativeBounds(mTaskFragment.mRemoteToken.toWindowContainerToken(),
                        mTaskFragBounds)
                .setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);

        // When the requested bounds do not satisfy the min dimension, it will be reset to empty.
        assertWithMessage("setRelativeBounds must not be performed.")
                .that(mTaskFragment.getRelativeEmbeddedBounds()).isEqualTo(new Rect());
    }

    @Test
    public void testOnTransactionReady_invokeOnTransactionHandled() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        mOrganizer.onTransactionReady(transaction);

        // Organizer should always trigger #onTransactionHandled when receives #onTransactionReady
        verify(mOrganizer).onTransactionHandled(eq(transaction.getTransactionToken()), any(),
                anyInt(), anyBoolean());
        verify(mOrganizer, never()).applyTransaction(any(), anyInt(), anyBoolean());
    }

    @Test
    public void testDispatchTransaction_deferTransitionReady() {
        setupMockParent(mTaskFragment, mTask);
        final ArgumentCaptor<IBinder> tokenCaptor = ArgumentCaptor.forClass(IBinder.class);
        final ArgumentCaptor<WindowContainerTransaction> wctCaptor =
                ArgumentCaptor.forClass(WindowContainerTransaction.class);
        doReturn(true).when(mTransitionController).isCollecting();
        doReturn(10).when(mTransitionController).getCollectingTransitionId();

        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        // Defer transition when send TaskFragment transaction during transition collection.
        verify(mTransitionController).deferTransitionReady();
        verify(mOrganizer).onTransactionHandled(tokenCaptor.capture(), wctCaptor.capture(),
                anyInt(), anyBoolean());

        final IBinder transactionToken = tokenCaptor.getValue();
        final WindowContainerTransaction wct = wctCaptor.getValue();
        wct.setTaskFragmentOrganizer(mIOrganizer);
        mController.onTransactionHandled(transactionToken, wct, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);

        verify(mTransitionController).continueTransitionReady();
    }

    @Test
    public void testWindowOrganizerApplyTransaction_throwException() {
        // Not allow to use #applyTransaction(WindowContainerTransaction).
        assertThrows(RuntimeException.class, () -> mOrganizer.applyTransaction(mTransaction));

        // Allow to use the overload method.
        mOrganizer.applyTransaction(mTransaction, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */);
    }

    @Test
    public void testTaskFragmentTransitionType() {
        // 1-1 relationship with WindowManager.TransitionType
        assertEquals(TRANSIT_NONE, TASK_FRAGMENT_TRANSIT_NONE);
        assertEquals(TRANSIT_OPEN, TASK_FRAGMENT_TRANSIT_OPEN);
        assertEquals(TRANSIT_CLOSE, TASK_FRAGMENT_TRANSIT_CLOSE);
        assertEquals(TRANSIT_CHANGE, TASK_FRAGMENT_TRANSIT_CHANGE);
    }

    @Test
    public void testApplyTransaction_setRelativeBounds() {
        final Task task = createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        final WindowContainerToken token = mTaskFragment.mRemoteToken.toWindowContainerToken();
        final Rect relBounds = new Rect(0, 0, 100, 1000);
        mTransaction.setRelativeBounds(token, relBounds);

        // Not allowed because TaskFragment is not organized by the caller organizer.
        assertApplyTransactionDisallowed(mTransaction);

        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
        assertEquals(relBounds, mTaskFragment.getRelativeEmbeddedBounds());
        assertEquals(relBounds, mTaskFragment.getBounds());

        // TaskFragment bounds should be updated to the same relative bounds.
        final Rect taskBounds = new Rect(200, 200, 700, 1500);
        task.setBoundsUnchecked(taskBounds);
        assertEquals(taskBounds, task.getBounds());
        assertEquals(relBounds, mTaskFragment.getRelativeEmbeddedBounds());
        assertEquals(mTaskFragment.translateRelativeBoundsToAbsoluteBounds(relBounds, taskBounds),
                mTaskFragment.getBounds());
        assertEquals(new Rect(200, 200, 300, 1200), mTaskFragment.getBounds());

        // Set TaskFragment to fill Task
        mTransaction.setRelativeBounds(token, new Rect());

        assertApplyTransactionAllowed(mTransaction);
        assertEquals(new Rect(), mTaskFragment.getRelativeEmbeddedBounds());
        assertEquals(taskBounds, mTaskFragment.getBounds());
    }

    @Test
    public void testUntrustedEmbedding_setRelativeBounds_adjustToTaskBounds() {
        final Task task = createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(mFragmentToken)
                .build();
        final WindowContainerToken token = mTaskFragment.mRemoteToken.toWindowContainerToken();
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        // Untrusted embedded
        doReturn(false).when(mTaskFragment).isAllowedToBeEmbeddedInTrustedMode();

        final Rect taskBounds = new Rect(0, 0, 500, 1000);
        task.setBoundsUnchecked(taskBounds);

        final Rect taskFragmentBounds = new Rect(250, 0, 750, 1000);
        mTransaction.setRelativeBounds(token, taskFragmentBounds);

        // Allow operation in case the Task is also resizing.
        // Adjust the relBounds to the intersection.
        assertApplyTransactionAllowed(mTransaction);
        // Relative bounds is correctly set.
        assertEquals(taskFragmentBounds, mTaskFragment.getRelativeEmbeddedBounds());
        // The actual window bounds is adjusted to fit the Task bounds.
        assertEquals(new Rect(250, 0, 500, 1000), mTaskFragment.getBounds());

        // Adjust to the full requested bounds when the Task is resized.
        taskBounds.set(0, 0, 750, 1000);
        task.setBoundsUnchecked(taskBounds);
        assertEquals(taskFragmentBounds, mTaskFragment.getBounds());
    }

    @Test
    public void testApplyTransaction_reorderTaskFragmentToFront() {
        final Task task = createTask(mDisplayContent);
        // Create a TaskFragment.
        final IBinder token0 = new Binder();
        final TaskFragment tf0 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(token0)
                .setOrganizer(mOrganizer)
                .createActivityCount(1)
                .build();
        // Create another TaskFragment
        final IBinder token1 = new Binder();
        final TaskFragment tf1 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(token1)
                .setOrganizer(mOrganizer)
                .createActivityCount(1)
                .build();
        // Create a non-embedded Activity on top.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(token0, tf0);
        mWindowOrganizerController.mLaunchTaskFragments.put(token1, tf1);

        // Reorder TaskFragment to front
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_REORDER_TO_FRONT).build();
        mTransaction.addTaskFragmentOperation(token0, operation);
        assertApplyTransactionAllowed(mTransaction);

        // Ensure the non-embedded activity still on top.
        assertEquals(topActivity, task.getTopChild().asActivityRecord());

        // Ensure the TaskFragment is moved to front.
        final TaskFragment frontMostTaskFragment = task.getTaskFragment(tf -> tf.asTask() == null);
        assertEquals(frontMostTaskFragment, tf0);
    }

    @Test
    public void testApplyTransaction_reorderToBottomOfTask() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG);

        mController.unregisterOrganizer(mIOrganizer);
        registerTaskFragmentOrganizer(mIOrganizer, true /* isSystemOrganizer */);
        final Task task = createTask(mDisplayContent);
        // Create a non-embedded Activity at the bottom.
        final ActivityRecord bottomActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();
        final TaskFragment tf0 = createTaskFragment(task);
        final TaskFragment tf1 = createTaskFragment(task);
        // Create a non-embedded Activity at the top.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();

        // Ensure correct order of the children before the operation
        assertEquals(topActivity, task.getChildAt(3).asActivityRecord());
        assertEquals(tf1, task.getChildAt(2).asTaskFragment());
        assertEquals(tf0, task.getChildAt(1).asTaskFragment());
        assertEquals(bottomActivity, task.getChildAt(0).asActivityRecord());

        // Reorder TaskFragment to bottom
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_REORDER_TO_BOTTOM_OF_TASK).build();
        mTransaction.addTaskFragmentOperation(tf1.getFragmentToken(), operation);
        assertApplyTransactionAllowed(mTransaction);

        // Ensure correct order of the children after the operation
        assertEquals(topActivity, task.getChildAt(3).asActivityRecord());
        assertEquals(tf0, task.getChildAt(2).asTaskFragment());
        assertEquals(bottomActivity, task.getChildAt(1).asActivityRecord());
        assertEquals(tf1, task.getChildAt(0).asTaskFragment());
    }

    @Test
    public void testApplyTransaction_reorderToTopOfTask() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG);

        mController.unregisterOrganizer(mIOrganizer);
        registerTaskFragmentOrganizer(mIOrganizer, true /* isSystemOrganizer */);
        final Task task = createTask(mDisplayContent);
        // Create a non-embedded Activity at the bottom.
        final ActivityRecord bottomActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();
        final TaskFragment tf0 = createTaskFragment(task);
        final TaskFragment tf1 = createTaskFragment(task);
        // Create a non-embedded Activity at the top.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();

        // Ensure correct order of the children before the operation
        assertEquals(topActivity, task.getChildAt(3).asActivityRecord());
        assertEquals(tf1, task.getChildAt(2).asTaskFragment());
        assertEquals(tf0, task.getChildAt(1).asTaskFragment());
        assertEquals(bottomActivity, task.getChildAt(0).asActivityRecord());

        // Reorder TaskFragment to top
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_REORDER_TO_TOP_OF_TASK).build();
        mTransaction.addTaskFragmentOperation(tf0.getFragmentToken(), operation);
        assertApplyTransactionAllowed(mTransaction);

        // Ensure correct order of the children after the operation
        assertEquals(tf0, task.getChildAt(3).asTaskFragment());
        assertEquals(topActivity, task.getChildAt(2).asActivityRecord());
        assertEquals(tf1, task.getChildAt(1).asTaskFragment());
        assertEquals(bottomActivity, task.getChildAt(0).asActivityRecord());
    }

    @Test
    public void testApplyTransaction_createTaskFragmentDecorSurface() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG);

        // TODO(b/293654166) remove system organizer requirement once security review is cleared.
        mController.unregisterOrganizer(mIOrganizer);
        registerTaskFragmentOrganizer(mIOrganizer, true /* isSystemOrganizer */);
        final Task task = createTask(mDisplayContent);

        final TaskFragment tf = createTaskFragment(task);
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_CREATE_TASK_FRAGMENT_DECOR_SURFACE).build();
        mTransaction.addTaskFragmentOperation(tf.getFragmentToken(), operation);

        assertApplyTransactionAllowed(mTransaction);

        verify(task).moveOrCreateDecorSurfaceFor(tf);
    }

    @Test
    public void testApplyTransaction_removeTaskFragmentDecorSurface() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG);

        // TODO(b/293654166) remove system organizer requirement once security review is cleared.
        mController.unregisterOrganizer(mIOrganizer);
        registerTaskFragmentOrganizer(mIOrganizer, true /* isSystemOrganizer */);
        final Task task = createTask(mDisplayContent);
        final TaskFragment tf = createTaskFragment(task);

        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE).build();
        mTransaction.addTaskFragmentOperation(tf.getFragmentToken(), operation);

        assertApplyTransactionAllowed(mTransaction);

        verify(task).removeDecorSurface();
    }

    @Test
    public void testApplyTransaction_reorderToBottomOfTask_failsIfNotSystemOrganizer() {
        testApplyTransaction_reorder_failsIfNotSystemOrganizer_common(
                OP_TYPE_REORDER_TO_BOTTOM_OF_TASK);
    }

    @Test
    public void testApplyTransaction_reorderToTopOfTask_failsIfNotSystemOrganizer() {
        testApplyTransaction_reorder_failsIfNotSystemOrganizer_common(
                OP_TYPE_REORDER_TO_TOP_OF_TASK);
    }

    @NonNull
    private ActivityRecord setupUntrustedEmbeddingPipReparent() {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        mTaskFragment.setTaskFragmentOrganizer(mOrganizer.getOrganizerToken(), uid,
                DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME);
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        final Task task = createTask(mDisplayContent);
        task.addChild(mTaskFragment, POSITION_TOP);
        final ActivityRecord activity = createActivityRecord(task);

        // Flush EVENT_APPEARED.
        mController.dispatchPendingEvents();

        // Make sure the activity is embedded in untrusted mode.
        activity.info.applicationInfo.uid = uid + 1;
        doReturn(pid + 1).when(activity).getPid();
        task.effectiveUid = uid;
        doReturn(EMBEDDING_ALLOWED).when(task).isAllowedToEmbedActivity(activity, uid);
        doReturn(false).when(task).isAllowedToEmbedActivityInTrustedMode(activity, uid);
        doReturn(true).when(task).isAllowedToEmbedActivityInUntrustedMode(activity);

        clearInvocations(mOrganizer);
        activity.mLastTaskFragmentOrganizerBeforePip = mIOrganizer;

        return activity;
    }

    private void testApplyTransaction_reorder_failsIfNotSystemOrganizer_common(
            @TaskFragmentOperation.OperationType int opType) {
        final Task task = createTask(mDisplayContent);
        doNothing().when(task).sendTaskFragmentParentInfoChangedIfNeeded();
        // Create a non-embedded Activity at the bottom.
        final ActivityRecord bottomActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();
        final TaskFragment tf0 = createTaskFragment(task);
        final TaskFragment tf1 = createTaskFragment(task);
        // Create a non-embedded Activity at the top.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();

        // Ensure correct order of the children before the operation
        assertEquals(topActivity, task.getChildAt(3).asActivityRecord());
        assertEquals(tf1, task.getChildAt(2).asTaskFragment());
        assertEquals(tf0, task.getChildAt(1).asTaskFragment());
        assertEquals(bottomActivity, task.getChildAt(0).asActivityRecord());

        // Apply reorder transaction, which is expected to fail for non-system organizer.
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                opType).build();
        mTransaction
                .addTaskFragmentOperation(tf0.getFragmentToken(), operation)
                .setErrorCallbackToken(mErrorToken);
        assertApplyTransactionAllowed(mTransaction);
        // The pending event will be dispatched on the handler (from requestTraversal).
        waitHandlerIdle(mWm.mAnimationHandler);

        assertTaskFragmentErrorTransaction(opType, SecurityException.class);

        // Ensure no change to the order of the children after the operation
        assertEquals(topActivity, task.getChildAt(3).asActivityRecord());
        assertEquals(tf1, task.getChildAt(2).asTaskFragment());
        assertEquals(tf0, task.getChildAt(1).asTaskFragment());
        assertEquals(bottomActivity, task.getChildAt(0).asActivityRecord());
    }

    /**
     * Creates a {@link TaskFragment} with the {@link WindowContainerTransaction}. Calls
     * {@link WindowOrganizerController#applyTransaction(WindowContainerTransaction)} to apply the
     * transaction,
     */
    private void createTaskFragmentFromOrganizer(WindowContainerTransaction wct,
            ActivityRecord ownerActivity, IBinder fragmentToken) {
        final int uid = Binder.getCallingUid();
        ownerActivity.info.applicationInfo.uid = uid;
        ownerActivity.getTask().effectiveUid = uid;
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken, ownerActivity.token).build();
        wct.setTaskFragmentOrganizer(mIOrganizer);

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        wct.createTaskFragment(params);
    }

    /** Asserts that applying the given transaction will throw a {@link SecurityException}. */
    private void assertApplyTransactionDisallowed(WindowContainerTransaction t) {
        assertThrows(SecurityException.class, () ->
                mController.applyTransaction(t, TASK_FRAGMENT_TRANSIT_CHANGE,
                        false /* shouldApplyIndependently */, null /* remoteTransition */));
    }

    /** Asserts that applying the given transaction will not throw any exception. */
    private void assertApplyTransactionAllowed(WindowContainerTransaction t) {
        mController.applyTransaction(t, TASK_FRAGMENT_TRANSIT_CHANGE,
                false /* shouldApplyIndependently */, null /* remoteTransition */);
    }

    /** Asserts that there will be a transaction for TaskFragment appeared. */
    private void assertTaskFragmentAppearedTransaction(boolean hasSurfaceControl) {
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());

        // Use remove to verify multiple transaction changes.
        final TaskFragmentTransaction.Change change = changes.remove(0);
        assertEquals(TYPE_TASK_FRAGMENT_APPEARED, change.getType());
        assertEquals(mTaskFragmentInfo, change.getTaskFragmentInfo());
        assertEquals(mFragmentToken, change.getTaskFragmentToken());
        if (hasSurfaceControl) {
            assertNotNull(change.getTaskFragmentSurfaceControl());
        } else {
            assertNull(change.getTaskFragmentSurfaceControl());
        }
    }

    /** Asserts that there will be a transaction for TaskFragment info changed. */
    private void assertTaskFragmentInfoChangedTransaction() {
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());

        // Use remove to verify multiple transaction changes.
        final TaskFragmentTransaction.Change change = changes.remove(0);
        assertEquals(TYPE_TASK_FRAGMENT_INFO_CHANGED, change.getType());
        assertEquals(mTaskFragmentInfo, change.getTaskFragmentInfo());
        assertEquals(mFragmentToken, change.getTaskFragmentToken());
    }

    /** Asserts that there will be a transaction for TaskFragment vanished. */
    private void assertTaskFragmentVanishedTransaction() {
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());

        // Use remove to verify multiple transaction changes.
        final TaskFragmentTransaction.Change change = changes.remove(0);
        assertEquals(TYPE_TASK_FRAGMENT_VANISHED, change.getType());
        assertEquals(mTaskFragmentInfo, change.getTaskFragmentInfo());
        assertEquals(mFragmentToken, change.getTaskFragmentToken());
    }

    /** Asserts that there will be a transaction for TaskFragment vanished. */
    private void assertTaskFragmentParentInfoChangedTransaction(@NonNull Task task) {
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());

        // Use remove to verify multiple transaction changes.
        final TaskFragmentTransaction.Change change = changes.remove(0);
        assertEquals(TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED, change.getType());
        assertEquals(task.mTaskId, change.getTaskId());
        assertEquals(task.getTaskFragmentParentInfo(), change.getTaskFragmentParentInfo());
    }

    /** Asserts that there will be a transaction for TaskFragment error. */
    private void assertTaskFragmentErrorTransaction(@TaskFragmentOperation.OperationType int opType,
            @NonNull Class<?> exceptionClass) {
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());

        // Use remove to verify multiple transaction changes.
        final TaskFragmentTransaction.Change change = changes.remove(0);
        assertEquals(TYPE_TASK_FRAGMENT_ERROR, change.getType());
        assertEquals(mErrorToken, change.getErrorCallbackToken());
        final Bundle errorBundle = change.getErrorBundle();
        assertEquals(opType, errorBundle.getInt(KEY_ERROR_CALLBACK_OP_TYPE));
        assertEquals(exceptionClass, errorBundle.getSerializable(
                KEY_ERROR_CALLBACK_THROWABLE, Throwable.class).getClass());
    }

    /** Asserts that there will be a transaction for activity reparented to Task. */
    private void assertActivityReparentedToTaskTransaction(int taskId, @NonNull Intent intent,
            @NonNull IBinder activityToken) {
        verify(mOrganizer).onTransactionReady(mTransactionCaptor.capture());
        final TaskFragmentTransaction transaction = mTransactionCaptor.getValue();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        assertFalse(changes.isEmpty());

        // Use remove to verify multiple transaction changes.
        final TaskFragmentTransaction.Change change = changes.remove(0);
        assertEquals(TYPE_ACTIVITY_REPARENTED_TO_TASK, change.getType());
        assertEquals(taskId, change.getTaskId());
        assertIntentsEqualForOrganizer(intent, change.getActivityIntent());
        assertIntentTrimmed(change.getActivityIntent());
        assertEquals(activityToken, change.getActivityToken());
    }

    /** Setups an embedded TaskFragment. */
    private TaskFragment createTaskFragment(Task task) {
        final IBinder token = new Binder();
        TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setFragmentToken(token)
                .setOrganizer(mOrganizer)
                .createActivityCount(1)
                .build();
        mWindowOrganizerController.mLaunchTaskFragments.put(token, taskFragment);
        return taskFragment;
    }

    /** Setups an embedded TaskFragment in a PIP Task. */
    private void setupTaskFragmentInPip() {
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setFragmentToken(mFragmentToken)
                .setOrganizer(mOrganizer)
                .createActivityCount(1)
                .build();
        mFragmentWindowToken = mTaskFragment.mRemoteToken.toWindowContainerToken();
        mWindowOrganizerController.mLaunchTaskFragments.put(mFragmentToken, mTaskFragment);
        mTaskFragment.getTask().setWindowingMode(WINDOWING_MODE_PINNED);
    }

    /** Setups the mock Task as the parent of the given TaskFragment. */
    private static void setupMockParent(TaskFragment taskFragment, Task mockParent) {
        doReturn(mockParent).when(taskFragment).getTask();
        doReturn(new TaskFragmentParentInfo(
                new Configuration(), DEFAULT_DISPLAY, true, true, null /* decorSurface */))
                .when(mockParent).getTaskFragmentParentInfo();

        // Task needs to be visible
        mockParent.lastActiveTime = 100;
        doReturn(true).when(mockParent).shouldBeVisible(any());
    }

    private static void assertIntentsEqualForOrganizer(@NonNull Intent expected,
            @NonNull Intent actual) {
        assertEquals(expected.getComponent(), actual.getComponent());
        assertEquals(expected.getPackage(), actual.getPackage());
        assertEquals(expected.getAction(), actual.getAction());
    }

    private static void assertIntentTrimmed(@NonNull Intent intent) {
        assertNull(intent.getData());
        assertNull(intent.getExtras());
        assertEquals(0, intent.getFlags());
    }
}
