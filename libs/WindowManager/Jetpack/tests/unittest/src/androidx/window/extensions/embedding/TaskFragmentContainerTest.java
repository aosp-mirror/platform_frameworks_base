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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.app.Activity;
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
    private static final int TASK_ID = 10;

    @Mock
    private SplitPresenter mPresenter;
    @Mock
    private SplitController mController;
    @Mock
    private TaskFragmentInfo mInfo;
    @Mock
    private Handler mHandler;
    private Activity mActivity;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        doReturn(mHandler).when(mController).getHandler();
        mActivity = createMockActivity();
    }

    @Test
    public void testFinish() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(mActivity, taskContainer,
                mController);
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
    public void testIsWaitingActivityAppear() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                taskContainer, mController);

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
                taskContainer, mController);

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
    public void testCollectActivities() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                taskContainer, mController);
        List<Activity> activities = container.collectActivities();

        assertTrue(activities.isEmpty());

        container.addPendingAppearedActivity(mActivity);
        activities = container.collectActivities();

        assertEquals(1, activities.size());

        final Activity activity0 = createMockActivity();
        final Activity activity1 = createMockActivity();
        final List<IBinder> runningActivities = Lists.newArrayList(activity0.getActivityToken(),
                activity1.getActivityToken());
        doReturn(runningActivities).when(mInfo).getActivities();
        container.setInfo(mInfo);
        activities = container.collectActivities();

        assertEquals(3, activities.size());
        assertEquals(activity0, activities.get(0));
        assertEquals(activity1, activities.get(1));
        assertEquals(mActivity, activities.get(2));
    }

    @Test
    public void testAddPendingActivity() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                taskContainer, mController);
        container.addPendingAppearedActivity(mActivity);

        assertEquals(1, container.collectActivities().size());

        container.addPendingAppearedActivity(mActivity);

        assertEquals(1, container.collectActivities().size());
    }

    @Test
    public void testGetBottomMostActivity() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        final TaskFragmentContainer container = new TaskFragmentContainer(null /* activity */,
                taskContainer, mController);
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
