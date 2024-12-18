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

package androidx.window.extensions.embedding;

import static androidx.window.extensions.embedding.EmbeddingTestUtils.createMockTaskFragmentInfo;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createTestTaskContainer;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.app.Activity;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.extensions.layout.WindowLayoutComponentImpl;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link TaskFragmentContainer}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:TaskFragmentContainerTest
 */
// Suppress GuardedBy warning on unit tests
@SuppressWarnings("GuardedBy")
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TaskFragmentContainerTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private SplitPresenter mPresenter;
    private SplitController mController;
    @Mock
    private TaskFragmentInfo mInfo;
    @Mock
    private WindowContainerTransaction mTransaction;
    private Activity mActivity;
    private Intent mIntent;

    @Before
    public void setup() {
        DeviceStateManagerFoldingFeatureProducer producer =
                mock(DeviceStateManagerFoldingFeatureProducer.class);
        WindowLayoutComponentImpl component = mock(WindowLayoutComponentImpl.class);
        mController = new SplitController(component, producer);
        spyOn(mController);
        mActivity = createMockActivity();
        mIntent = new Intent();
    }

    @Test
    public void testNewContainer() {
        final TaskContainer taskContainer = createTestTaskContainer();
        mController.addTaskContainer(taskContainer.getTaskId(), taskContainer);

        // One of the activity and the intent must be non-null
        assertThrows(IllegalArgumentException.class,
                () -> new TaskFragmentContainer.Builder(mController, taskContainer.getTaskId(),
                        null /* activityInTask */).build());

        // One of the activity and the intent must be null.
        assertThrows(IllegalArgumentException.class,
                () -> new TaskFragmentContainer.Builder(mController, taskContainer.getTaskId(),
                        null /* activityInTask */)
                        .setPendingAppearedActivity(createMockActivity())
                        .setPendingAppearedIntent(mIntent)
                        .build());
    }

    @Test
    public void testFinish() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(taskContainer,
                mActivity, null /* pendingAppearedIntent */);
        doReturn(container).when(mController).getContainerWithActivity(mActivity);

        // Only remove the activity, but not clear the reference until appeared.
        container.finish(true /* shouldFinishDependent */, mPresenter, mTransaction, mController);

        verify(mTransaction).finishActivity(mActivity.getActivityToken());
        verify(mPresenter, never()).deleteTaskFragment(any(), any());
        verify(mController, never()).removeContainer(any());

        // Calling twice should not finish activity again.
        clearInvocations(mTransaction);
        container.finish(true /* shouldFinishDependent */, mPresenter, mTransaction, mController);

        verify(mTransaction, never()).finishActivity(any());
        verify(mPresenter, never()).deleteTaskFragment(any(), any());
        verify(mController, never()).removeContainer(any());

        // Remove all references after the container has appeared in server.
        doReturn(new ArrayList<>()).when(mInfo).getActivities();
        container.setInfo(mTransaction, mInfo);
        container.finish(true /* shouldFinishDependent */, mPresenter, mTransaction, mController);

        verify(mTransaction, never()).finishActivity(any());
        verify(mPresenter).deleteTaskFragment(mTransaction, container.getTaskFragmentToken());
        verify(mController).removeContainer(container);
    }

    @Test
    public void testFinish_notFinishActivityThatIsReparenting() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container0 = createTaskFragmentContainer(taskContainer,
                mActivity, null /* pendingAppearedIntent */);
        final TaskFragmentInfo info = createMockTaskFragmentInfo(container0, mActivity);
        container0.setInfo(mTransaction, info);
        // Request to reparent the activity to a new TaskFragment.
        final TaskFragmentContainer container1 = createTaskFragmentContainer(taskContainer,
                mActivity, null /* pendingAppearedIntent */);
        doReturn(container1).when(mController).getContainerWithActivity(mActivity);

        // The activity is requested to be reparented, so don't finish it.
        container0.finish(true /* shouldFinishDependent */, mPresenter, mTransaction, mController);

        verify(mTransaction, never()).finishActivity(any());
        verify(mPresenter).deleteTaskFragment(mTransaction, container0.getTaskFragmentToken());
        verify(mController).removeContainer(container0);
    }

    @Test
    public void testFinish_alwaysFinishPlaceholder() {
        // Register container1 as a placeholder
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container0 = createTaskFragmentContainer(taskContainer,
                mActivity, null /* pendingAppearedIntent */);
        final TaskFragmentInfo info0 = createMockTaskFragmentInfo(container0, mActivity);
        container0.setInfo(mTransaction, info0);
        final Activity placeholderActivity = createMockActivity();
        final TaskFragmentContainer container1 = createTaskFragmentContainer(taskContainer,
                placeholderActivity, null /* pendingAppearedIntent */);
        final TaskFragmentInfo info1 = createMockTaskFragmentInfo(container1, placeholderActivity);
        container1.setInfo(mTransaction, info1);
        final SplitAttributes splitAttributes = new SplitAttributes.Builder().build();
        final SplitPlaceholderRule rule = new SplitPlaceholderRule.Builder(new Intent(),
                mActivity::equals, (java.util.function.Predicate) i -> false,
                (java.util.function.Predicate) w -> true)
                .setDefaultSplitAttributes(splitAttributes)
                .build();
        mController.registerSplit(mTransaction, container0, mActivity, container1, rule,
                splitAttributes);

        // The placeholder TaskFragment should be finished even if the primary is finished with
        // shouldFinishDependent = false.
        container0.finish(false /* shouldFinishDependent */, mPresenter, mTransaction, mController);

        assertTrue(container0.isFinished());
        assertTrue(container1.isFinished());
        verify(mPresenter).deleteTaskFragment(mTransaction, container0.getTaskFragmentToken());
        verify(mPresenter).deleteTaskFragment(mTransaction, container1.getTaskFragmentToken());
        verify(mController).removeContainer(container0);
        verify(mController).removeContainer(container1);
    }

    @Test
    public void testSetInfo() {
        final TaskContainer taskContainer = createTestTaskContainer();
        // Pending activity should be cleared when it has appeared on server side.
        final TaskFragmentContainer pendingActivityContainer = createTaskFragmentContainer(
                taskContainer, mActivity, null /* pendingAppearedIntent */);

        assertTrue(pendingActivityContainer.mPendingAppearedActivities.contains(
                mActivity.getActivityToken()));

        final TaskFragmentInfo info0 = createMockTaskFragmentInfo(pendingActivityContainer,
                mActivity);
        pendingActivityContainer.setInfo(mTransaction, info0);

        assertTrue(pendingActivityContainer.mPendingAppearedActivities.isEmpty());

        // Pending intent should be cleared when the container becomes non-empty.
        final TaskFragmentContainer pendingIntentContainer = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);

        assertEquals(mIntent, pendingIntentContainer.getPendingAppearedIntent());

        final TaskFragmentInfo info1 = createMockTaskFragmentInfo(pendingIntentContainer,
                mActivity);
        pendingIntentContainer.setInfo(mTransaction, info1);

        assertNull(pendingIntentContainer.getPendingAppearedIntent());
    }

    @Test
    public void testIsWaitingActivityAppear() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null  /* pendingAppearedActivity */, mIntent);

        assertTrue(container.isWaitingActivityAppear());

        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        doReturn(new ArrayList<>()).when(info).getActivities();
        doReturn(true).when(info).isEmpty();
        container.setInfo(mTransaction, info);

        assertTrue(container.isWaitingActivityAppear());

        doReturn(false).when(info).isEmpty();
        container.setInfo(mTransaction, info);

        assertFalse(container.isWaitingActivityAppear());
    }

    @Test
    public void testAppearEmptyTimeout() {
        doNothing().when(mController).onTaskFragmentAppearEmptyTimeout(any(), any());
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);

        assertNull(container.mAppearEmptyTimeout);

        // Set timeout if the first info set is empty.
        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        container.mInfo = null;
        doReturn(true).when(info).isEmpty();
        container.setInfo(mTransaction, info);

        assertNotNull(container.mAppearEmptyTimeout);

        // Not set if it is not appeared empty.
        doReturn(new ArrayList<>()).when(info).getActivities();
        doReturn(false).when(info).isEmpty();
        container.setInfo(mTransaction, info);

        assertNull(container.mAppearEmptyTimeout);

        // Remove timeout after the container becomes non-empty.
        doReturn(false).when(info).isEmpty();
        container.setInfo(mTransaction, info);

        assertNull(container.mAppearEmptyTimeout);

        // Running the timeout will call into SplitController.onTaskFragmentAppearEmptyTimeout.
        container.mInfo = null;
        container.setPendingAppearedIntent(mIntent);
        doReturn(true).when(info).isEmpty();
        container.setInfo(mTransaction, info);
        container.mAppearEmptyTimeout.run();

        assertNull(container.mAppearEmptyTimeout);
        verify(mController).onTaskFragmentAppearEmptyTimeout(container);
    }

    @Test
    public void testCollectNonFinishingActivities() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        List<Activity> activities = container.collectNonFinishingActivities();

        assertTrue(activities.isEmpty());

        container.addPendingAppearedActivity(mActivity);
        activities = container.collectNonFinishingActivities();

        assertEquals(1, activities.size());

        final Activity activity0 = createMockActivity();
        final Activity activity1 = createMockActivity();
        final List<IBinder> runningActivities = Lists.newArrayList(activity0.getActivityToken(),
                activity1.getActivityToken());
        doReturn(runningActivities).when(mInfo).getActivities();
        container.setInfo(mTransaction, mInfo);
        activities = container.collectNonFinishingActivities();

        assertEquals(3, activities.size());
        assertEquals(activity0, activities.get(0));
        assertEquals(activity1, activities.get(1));
        assertEquals(mActivity, activities.get(2));
    }

    @Test
    public void testCollectNonFinishingActivities_checkIfStable() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);

        // In case mInfo is null, collectNonFinishingActivities(true) should return null.
        List<Activity> activities =
                container.collectNonFinishingActivities(true /* checkIfStable */);
        assertNull(activities);

        // collectNonFinishingActivities(true) should return proper value when the container is in a
        // stable state.
        final List<IBinder> runningActivities = Lists.newArrayList(mActivity.getActivityToken());
        doReturn(runningActivities).when(mInfo).getActivities();
        container.setInfo(mTransaction, mInfo);
        activities = container.collectNonFinishingActivities(true /* checkIfStable */);
        assertEquals(1, activities.size());

        // In case any activity is finishing, collectNonFinishingActivities(true) should return
        // null.
        doReturn(true).when(mActivity).isFinishing();
        activities = container.collectNonFinishingActivities(true /* checkIfStable */);
        assertNull(activities);
    }

    @Test
    public void testAddPendingActivity() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        container.addPendingAppearedActivity(mActivity);

        assertEquals(1, container.collectNonFinishingActivities().size());

        container.addPendingAppearedActivity(mActivity);

        assertEquals(1, container.collectNonFinishingActivities().size());
    }

    @Test
    public void testIsAbove() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container0 = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        final TaskFragmentContainer container1 = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);

        assertTrue(container1.isAbove(container0));
        assertFalse(container0.isAbove(container1));
    }

    @Test
    public void testGetBottomMostActivity() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        container.addPendingAppearedActivity(mActivity);

        assertEquals(mActivity, container.getBottomMostActivity());

        final Activity activity = createMockActivity();
        final List<IBinder> runningActivities = Lists.newArrayList(activity.getActivityToken());
        doReturn(runningActivities).when(mInfo).getActivities();
        container.setInfo(mTransaction, mInfo);

        assertEquals(activity, container.getBottomMostActivity());
    }

    @Test
    public void testOnActivityDestroyed() {
        final TaskContainer taskContainer = createTestTaskContainer(mController);
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        container.addPendingAppearedActivity(mActivity);
        final List<IBinder> activities = new ArrayList<>();
        activities.add(mActivity.getActivityToken());
        doReturn(activities).when(mInfo).getActivities();
        container.setInfo(mTransaction, mInfo);

        assertTrue(container.hasActivity(mActivity.getActivityToken()));

        taskContainer.onActivityDestroyed(mTransaction, mActivity.getActivityToken());

        // It should not contain the destroyed Activity.
        assertFalse(container.hasActivity(mActivity.getActivityToken()));
    }

    @Test
    public void testIsInIntermediateState() {
        // True if no info set.
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        spyOn(taskContainer);
        doReturn(true).when(taskContainer).isVisible();

        assertTrue(container.isInIntermediateState());
        assertTrue(taskContainer.isInIntermediateState());

        // True if empty info set.
        final List<IBinder> activities = new ArrayList<>();
        doReturn(activities).when(mInfo).getActivities();
        doReturn(true).when(mInfo).isEmpty();
        container.setInfo(mTransaction, mInfo);

        assertTrue(container.isInIntermediateState());
        assertTrue(taskContainer.isInIntermediateState());

        // False if info is not empty.
        doReturn(false).when(mInfo).isEmpty();
        container.setInfo(mTransaction, mInfo);

        assertFalse(container.isInIntermediateState());
        assertFalse(taskContainer.isInIntermediateState());

        // True if there is pending appeared activity.
        container.addPendingAppearedActivity(mActivity);

        assertTrue(container.isInIntermediateState());
        assertTrue(taskContainer.isInIntermediateState());

        // True if the activity is finishing.
        activities.add(mActivity.getActivityToken());
        doReturn(true).when(mActivity).isFinishing();
        container.setInfo(mTransaction, mInfo);

        assertTrue(container.isInIntermediateState());
        assertTrue(taskContainer.isInIntermediateState());

        // False if the activity is not finishing.
        doReturn(false).when(mActivity).isFinishing();
        container.setInfo(mTransaction, mInfo);

        assertFalse(container.isInIntermediateState());
        assertFalse(taskContainer.isInIntermediateState());

        // True if there is a token that can't find associated activity.
        activities.clear();
        activities.add(new Binder());
        container.setInfo(mTransaction, mInfo);

        assertTrue(container.isInIntermediateState());
        assertTrue(taskContainer.isInIntermediateState());

        // False if there is a token that can't find associated activity when the Task is invisible.
        doReturn(false).when(taskContainer).isVisible();

        assertFalse(container.isInIntermediateState());
        assertFalse(taskContainer.isInIntermediateState());
    }

    @Test
    public void testHasAppearedActivity() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        container.addPendingAppearedActivity(mActivity);

        assertFalse(container.hasAppearedActivity(mActivity.getActivityToken()));

        final List<IBinder> activities = new ArrayList<>();
        activities.add(mActivity.getActivityToken());
        doReturn(activities).when(mInfo).getActivities();
        container.setInfo(mTransaction, mInfo);

        assertTrue(container.hasAppearedActivity(mActivity.getActivityToken()));
    }

    @Test
    public void testHasPendingAppearedActivity() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        container.addPendingAppearedActivity(mActivity);

        assertTrue(container.hasPendingAppearedActivity(mActivity.getActivityToken()));

        final List<IBinder> activities = new ArrayList<>();
        activities.add(mActivity.getActivityToken());
        doReturn(activities).when(mInfo).getActivities();
        container.setInfo(mTransaction, mInfo);

        assertFalse(container.hasPendingAppearedActivity(mActivity.getActivityToken()));
    }

    @Test
    public void testHasActivity() {
        final TaskContainer taskContainer = createTestTaskContainer(mController);
        final TaskFragmentContainer container1 = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);
        final TaskFragmentContainer container2 = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, mIntent);

        // Activity is pending appeared on container2.
        container2.addPendingAppearedActivity(mActivity);

        assertFalse(container1.hasActivity(mActivity.getActivityToken()));
        assertTrue(container2.hasActivity(mActivity.getActivityToken()));

        // Activity is pending appeared on container1 (removed from container2).
        container1.addPendingAppearedActivity(mActivity);

        assertTrue(container1.hasActivity(mActivity.getActivityToken()));
        assertFalse(container2.hasActivity(mActivity.getActivityToken()));

        final List<IBinder> activities = new ArrayList<>();
        activities.add(mActivity.getActivityToken());
        doReturn(activities).when(mInfo).getActivities();

        // Although Activity is appeared on container2, we prioritize pending appeared record on
        // container1.
        container2.setInfo(mTransaction, mInfo);

        assertTrue(container1.hasActivity(mActivity.getActivityToken()));
        assertFalse(container2.hasActivity(mActivity.getActivityToken()));
        // When the pending appeared record is removed from container1, we respect the appeared
        // record in container2.
        container1.removePendingAppearedActivity(mActivity.getActivityToken());

        assertFalse(container1.hasActivity(mActivity.getActivityToken()));
        assertTrue(container2.hasActivity(mActivity.getActivityToken()));
    }

    @Test
    public void testNewContainerWithPairedPrimaryContainer() {
        final TaskContainer taskContainer = createTestTaskContainer();
        mController.addTaskContainer(taskContainer.getTaskId(), taskContainer);
        final TaskFragmentContainer tf0 = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, new Intent());
        final TaskFragmentContainer tf1 = createTaskFragmentContainer(
                taskContainer, null /* pendingAppearedActivity */, new Intent());

        // When tf2 is created with using tf0 as pairedPrimaryContainer, tf2 should be inserted
        // right above tf0.
        final TaskFragmentContainer tf2 = new TaskFragmentContainer.Builder(mController,
                taskContainer.getTaskId(), null /* activityInTask */)
                .setPendingAppearedIntent(new Intent())
                .setPairedPrimaryContainer(tf0)
                .build();
        assertEquals(0, taskContainer.indexOf(tf0));
        assertEquals(1, taskContainer.indexOf(tf2));
        assertEquals(2, taskContainer.indexOf(tf1));
    }

    @Test
    public void testNewContainerWithPairedPendingAppearedActivity() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer tf0 = createTaskFragmentContainer(taskContainer,
                createMockActivity(), null /* pendingAppearedIntent */);
        final TaskFragmentContainer tf1 = createTaskFragmentContainer(taskContainer,
                null /* pendingAppearedActivity */, new Intent());

        // When tf2 is created with pendingAppearedActivity, tf2 should be inserted below any
        // TaskFragment without any Activity.
        final TaskFragmentContainer tf2 = createTaskFragmentContainer(taskContainer,
                createMockActivity(), null /* pendingAppearedIntent */);
        assertEquals(0, taskContainer.indexOf(tf0));
        assertEquals(1, taskContainer.indexOf(tf2));
        assertEquals(2, taskContainer.indexOf(tf1));
    }

    @Test
    public void testIsVisible() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final TaskFragmentContainer container = createTaskFragmentContainer(taskContainer,
                null /* pendingAppearedActivity */, new Intent());

        // Not visible when there is not appeared.
        assertFalse(container.isVisible());

        // Respect info.isVisible.
        TaskFragmentInfo info = createMockTaskFragmentInfo(container, mActivity,
                true /* isVisible */);
        container.setInfo(mTransaction, info);

        assertTrue(container.isVisible());

        info = createMockTaskFragmentInfo(container, mActivity, false /* isVisible */);
        container.setInfo(mTransaction, info);

        assertFalse(container.isVisible());
    }

    /** Creates a mock activity in the organizer process. */
    private Activity createMockActivity() {
        final Activity activity = mock(Activity.class);
        final IBinder activityToken = new Binder();
        doReturn(activityToken).when(activity).getActivityToken();
        doReturn(activity).when(mController).getActivity(activityToken);
        return activity;
    }

    private TaskFragmentContainer createTaskFragmentContainer(TaskContainer taskContainer,
            Activity pendingAppearedActivity, Intent pendingAppearedIntent) {
        final int taskId = taskContainer.getTaskId();
        mController.addTaskContainer(taskId, taskContainer);
        return new TaskFragmentContainer.Builder(mController, taskId, pendingAppearedActivity)
                .setPendingAppearedActivity(pendingAppearedActivity)
                .setPendingAppearedIntent(pendingAppearedIntent)
                .build();
    }
}
