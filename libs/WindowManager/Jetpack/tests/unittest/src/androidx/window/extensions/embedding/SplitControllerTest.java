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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link SplitController}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:SplitControllerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitControllerTest {
    private static final int TASK_ID = 10;
    private static final Rect TASK_BOUNDS = new Rect(0, 0, 600, 1200);

    @Mock
    private Activity mActivity;
    @Mock
    private Resources mActivityResources;
    @Mock
    private TaskFragmentInfo mInfo;
    @Mock
    private WindowContainerTransaction mTransaction;
    @Mock
    private Handler mHandler;

    private SplitController mSplitController;
    private SplitPresenter mSplitPresenter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSplitController = new SplitController();
        mSplitPresenter = mSplitController.mPresenter;
        spyOn(mSplitController);
        spyOn(mSplitPresenter);
        final Configuration activityConfig = new Configuration();
        activityConfig.windowConfiguration.setBounds(TASK_BOUNDS);
        activityConfig.windowConfiguration.setMaxBounds(TASK_BOUNDS);
        doReturn(mActivityResources).when(mActivity).getResources();
        doReturn(activityConfig).when(mActivityResources).getConfiguration();
        doReturn(mHandler).when(mSplitController).getHandler();
    }

    @Test
    public void testGetTopActiveContainer() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        // tf1 has no running activity so is not active.
        final TaskFragmentContainer tf1 = new TaskFragmentContainer(null /* activity */,
                taskContainer, mSplitController);
        // tf2 has running activity so is active.
        final TaskFragmentContainer tf2 = mock(TaskFragmentContainer.class);
        doReturn(1).when(tf2).getRunningActivityCount();
        taskContainer.mContainers.add(tf2);
        // tf3 is finished so is not active.
        final TaskFragmentContainer tf3 = mock(TaskFragmentContainer.class);
        doReturn(true).when(tf3).isFinished();
        doReturn(false).when(tf3).isWaitingActivityAppear();
        taskContainer.mContainers.add(tf3);
        mSplitController.mTaskContainers.put(TASK_ID, taskContainer);

        assertWithMessage("Must return tf2 because tf3 is not active.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf2);

        taskContainer.mContainers.remove(tf3);

        assertWithMessage("Must return tf2 because tf2 has running activity.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf2);

        taskContainer.mContainers.remove(tf2);

        assertWithMessage("Must return tf because we are waiting for tf1 to appear.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf1);

        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        doReturn(new ArrayList<>()).when(info).getActivities();
        doReturn(true).when(info).isEmpty();
        tf1.setInfo(info);

        assertWithMessage("Must return tf because we are waiting for tf1 to become non-empty after"
                + " creation.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf1);

        doReturn(false).when(info).isEmpty();
        tf1.setInfo(info);

        assertWithMessage("Must return null because tf1 becomes empty.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isNull();
    }

    @Test
    public void testOnTaskFragmentVanished() {
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);
        doReturn(tf.getTaskFragmentToken()).when(mInfo).getFragmentToken();

        // The TaskFragment has been removed in the server, we only need to cleanup the reference.
        mSplitController.onTaskFragmentVanished(mInfo);

        verify(mSplitPresenter, never()).deleteTaskFragment(any(), any());
        verify(mSplitController).removeContainer(tf);
        verify(mActivity, never()).finish();
    }

    @Test
    public void testOnTaskFragmentAppearEmptyTimeout() {
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);
        mSplitController.onTaskFragmentAppearEmptyTimeout(tf);

        verify(mSplitPresenter).cleanupContainer(tf, false /* shouldFinishDependent */);
    }

    @Test
    public void testOnActivityDestroyed() {
        doReturn(new Binder()).when(mActivity).getActivityToken();
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);

        assertTrue(tf.hasActivity(mActivity.getActivityToken()));

        mSplitController.onActivityDestroyed(mActivity);

        assertFalse(tf.hasActivity(mActivity.getActivityToken()));
    }

    @Test
    public void testNewContainer() {
        // Must pass in a valid activity.
        assertThrows(IllegalArgumentException.class, () ->
                mSplitController.newContainer(null /* activity */, TASK_ID));
        assertThrows(IllegalArgumentException.class, () ->
                mSplitController.newContainer(mActivity, null /* launchingActivity */, TASK_ID));

        final TaskFragmentContainer tf = mSplitController.newContainer(null, mActivity, TASK_ID);
        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);

        assertNotNull(tf);
        assertNotNull(taskContainer);
        assertEquals(TASK_BOUNDS, taskContainer.getTaskBounds());
    }

    @Test
    public void testUpdateContainer() {
        // Make SplitController#launchPlaceholderIfNecessary(TaskFragmentContainer) return true
        // and verify if shouldContainerBeExpanded() not called.
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);
        spyOn(tf);
        doReturn(mActivity).when(tf).getTopNonFinishingActivity();
        doReturn(true).when(tf).isEmpty();
        doReturn(true).when(mSplitController).launchPlaceholderIfNecessary(mActivity);
        doNothing().when(mSplitPresenter).updateSplitContainer(any(), any(), any());

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).shouldContainerBeExpanded(any());

        // Verify if tf should be expanded, getTopActiveContainer() won't be called
        doReturn(null).when(tf).getTopNonFinishingActivity();
        doReturn(true).when(mSplitController).shouldContainerBeExpanded(tf);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).getTopActiveContainer(TASK_ID);

        // Verify if tf is not in split, dismissPlaceholderIfNecessary won't be called.
        doReturn(false).when(mSplitController).shouldContainerBeExpanded(tf);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any());

        // Verify if tf is not in the top splitContainer,
        final SplitContainer splitContainer = mock(SplitContainer.class);
        doReturn(tf).when(splitContainer).getPrimaryContainer();
        doReturn(tf).when(splitContainer).getSecondaryContainer();
        final List<SplitContainer> splitContainers =
                mSplitController.getTaskContainer(TASK_ID).mSplitContainers;
        splitContainers.add(splitContainer);
        // Add a mock SplitContainer on top of splitContainer
        splitContainers.add(1, mock(SplitContainer.class));

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any());

        // Verify if one or both containers in the top SplitContainer are finished,
        // dismissPlaceholder() won't be called.
        splitContainers.remove(1);
        doReturn(true).when(tf).isFinished();

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any());

        // Verify if placeholder should be dismissed, updateSplitContainer() won't be called.
        doReturn(false).when(tf).isFinished();
        doReturn(true).when(mSplitController)
                .dismissPlaceholderIfNecessary(splitContainer);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitPresenter, never()).updateSplitContainer(any(), any(), any());

        // Verify if the top active split is updated if both of its containers are not finished.
        doReturn(false).when(mSplitController)
                        .dismissPlaceholderIfNecessary(splitContainer);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitPresenter).updateSplitContainer(eq(splitContainer), eq(tf), eq(mTransaction));
    }
}
