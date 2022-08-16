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

import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_ID;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createMockTaskFragmentInfo;

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
import android.os.Handler;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link TaskFragmentContainer}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:TaskFragmentContainerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TaskFragmentContainerTest {
    @Mock
    private SplitPresenter mPresenter;
    @Mock
    private SplitController mController;
    @Mock
    private TaskFragmentInfo mInfo;
    @Mock
    private Handler mHandler;
    private Activity mActivity;
    private Intent mIntent;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        doReturn(mHandler).when(mController).getHandler();
        mActivity = createMockActivity();
        mIntent = new Intent();
    }

    @Test
    public void testNewContainer() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);

        // One of the activity and the intent must be non-null
        assertThrows(IllegalArgumentException.class,
                () -> new TaskFragmentContainer(null, null, taskContainer, mController));

        // One of the activity and the intent must be null.
        assertThrows(IllegalArgumentException.class,
                () -> new TaskFragmentContainer(mActivity, mIntent, taskContainer, mController));
    }

    @Test
    public void testFinish() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(mActivity,
                null /* pendingAppearedIntent */, taskContainer, mController);
        doReturn(container).when(mController).getContainerWithActivity(mActivity);
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        // Only remove the activity, but not clear the reference until appeared.
        container.finish(true /* shouldFinishDependent */, mPresenter, wct, mController);

        verify(mActivity).finish();
        verify(mPresenter, never()).deleteTaskFragment(any(), any());
        verify(mController, never()).removeContainer(any());

        // Calling twice should not finish activity again.
        clearInvocations(mActivity);
        container.finish(true /* shouldFinishDependent */, mPresenter, wct, mController);

        verify(mActivity, never()).finish();
        verify(mPresenter, never()).deleteTaskFragment(any(), any());
        verify(mController, never()).removeContainer(any());

        // Remove all references after the container has appeared in server.
        doReturn(new ArrayList<>()).when(mInfo).getActivities();
        container.setInfo(mInfo);
        container.finish(true /* shouldFinishDependent */, mPresenter, wct, mController);

        verify(mActivity, never()).finish();
        verify(mPresenter).deleteTaskFragment(wct, container.getTaskFragmentToken());
        verify(mController).removeContainer(container);
    }

    @Test
    public void testFinish_notFinishActivityThatIsReparenting() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container0 = new TaskFragmentContainer(mActivity,
                null /* pendingAppearedIntent */, taskContainer, mController);
        final TaskFragmentInfo info = createMockTaskFragmentInfo(container0, mActivity);
        container0.setInfo(info);
        // Request to reparent the activity to a new TaskFragment.
        final TaskFragmentContainer container1 = new TaskFragmentContainer(mActivity,
                null /* pendingAppearedIntent */, taskContainer, mController);
        doReturn(container1).when(mController).getContainerWithActivity(mActivity);
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        // The activity is requested to be reparented, so don't finish it.
        container0.finish(true /* shouldFinishDependent */, mPresenter, wct, mController);

        verify(mActivity, never()).finish();
        verify(mPresenter).deleteTaskFragment(wct, container0.getTaskFragmentToken());
        verify(mController).removeContainer(container0);
    }

    @Test
    public void testSetInfo() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        // Pending activity should be cleared when it has appeared on server side.
        final TaskFragmentContainer pendingActivityContainer = new TaskFragmentContainer(mActivity,
                null /* pendingAppearedIntent */, taskContainer, mController);

        assertTrue(pendingActivityContainer.mPendingAppearedActivities.contains(mActivity));

        final TaskFragmentInfo info0 = createMockTaskFragmentInfo(pendingActivityContainer,
                mActivity);
        pendingActivityContainer.setInfo(info0);

        assertTrue(pendingActivityContainer.mPendingAppearedActivities.isEmpty());

        // Pending intent should be cleared when the container becomes non-empty.
        final TaskFragmentContainer pendingIntentContainer = new TaskFragmentContainer(
                null /* pendingAppearedActivity */, mIntent, taskContainer, mController);

        assertEquals(mIntent, pendingIntentContainer.getPendingAppearedIntent());

        final TaskFragmentInfo info1 = createMockTaskFragmentInfo(pendingIntentContainer,
                mActivity);
        pendingIntentContainer.setInfo(info1);

        assertNull(pendingIntentContainer.getPendingAppearedIntent());
    }

    @Test
    public void testIsWaitingActivityAppear() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                mIntent, taskContainer, mController);

        assertTrue(container.isWaitingActivityAppear());

        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        doReturn(new ArrayList<>()).when(info).getActivities();
        doReturn(true).when(info).isEmpty();
        container.setInfo(info);

        assertTrue(container.isWaitingActivityAppear());

        doReturn(false).when(info).isEmpty();
        container.setInfo(info);

        assertFalse(container.isWaitingActivityAppear());
    }

    @Test
    public void testAppearEmptyTimeout() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                mIntent, taskContainer, mController);

        assertNull(container.mAppearEmptyTimeout);

        // Not set if it is not appeared empty.
        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        doReturn(new ArrayList<>()).when(info).getActivities();
        doReturn(false).when(info).isEmpty();
        container.setInfo(info);

        assertNull(container.mAppearEmptyTimeout);

        // Set timeout if the first info set is empty.
        container.mInfo = null;
        doReturn(true).when(info).isEmpty();
        container.setInfo(info);

        assertNotNull(container.mAppearEmptyTimeout);

        // Remove timeout after the container becomes non-empty.
        doReturn(false).when(info).isEmpty();
        container.setInfo(info);

        assertNull(container.mAppearEmptyTimeout);

        // Running the timeout will call into SplitController.onTaskFragmentAppearEmptyTimeout.
        container.mInfo = null;
        doReturn(true).when(info).isEmpty();
        container.setInfo(info);
        container.mAppearEmptyTimeout.run();

        assertNull(container.mAppearEmptyTimeout);
        verify(mController).onTaskFragmentAppearEmptyTimeout(container);
    }

    @Test
    public void testCollectNonFinishingActivities() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                mIntent, taskContainer, mController);
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
        container.setInfo(mInfo);
        activities = container.collectNonFinishingActivities();

        assertEquals(3, activities.size());
        assertEquals(activity0, activities.get(0));
        assertEquals(activity1, activities.get(1));
        assertEquals(mActivity, activities.get(2));
    }

    @Test
    public void testAddPendingActivity() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                mIntent, taskContainer, mController);
        container.addPendingAppearedActivity(mActivity);

        assertEquals(1, container.collectNonFinishingActivities().size());

        container.addPendingAppearedActivity(mActivity);

        assertEquals(1, container.collectNonFinishingActivities().size());
    }

    @Test
    public void testGetBottomMostActivity() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                mIntent, taskContainer, mController);
        container.addPendingAppearedActivity(mActivity);

        assertEquals(mActivity, container.getBottomMostActivity());

        final Activity activity = createMockActivity();
        final List<IBinder> runningActivities = Lists.newArrayList(activity.getActivityToken());
        doReturn(runningActivities).when(mInfo).getActivities();
        container.setInfo(mInfo);

        assertEquals(activity, container.getBottomMostActivity());
    }

    /** Creates a mock activity in the organizer process. */
    private Activity createMockActivity() {
        final Activity activity = mock(Activity.class);
        final IBinder activityToken = new Binder();
        doReturn(activityToken).when(activity).getActivityToken();
        doReturn(activity).when(mController).getActivity(activityToken);
        return activity;
    }
}
