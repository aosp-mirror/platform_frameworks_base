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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
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

/**
 * Test class for {@link SplitPresenter}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:SplitPresenterTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitPresenterTest {
    private static final int TASK_ID = 10;
    private static final Rect TASK_BOUNDS = new Rect(0, 0, 600, 1200);

    @Mock
    private Activity mActivity;
    @Mock
    private Resources mActivityResources;
    @Mock
    private TaskFragmentInfo mTaskFragmentInfo;
    @Mock
    private WindowContainerTransaction mTransaction;
    private SplitController mController;
    private SplitPresenter mPresenter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mController = new SplitController();
        mPresenter = mController.mPresenter;
        spyOn(mController);
        spyOn(mPresenter);
        final Configuration activityConfig = new Configuration();
        activityConfig.windowConfiguration.setBounds(TASK_BOUNDS);
        activityConfig.windowConfiguration.setMaxBounds(TASK_BOUNDS);
        doReturn(mActivityResources).when(mActivity).getResources();
        doReturn(activityConfig).when(mActivityResources).getConfiguration();
    }

    @Test
    public void testCreateTaskFragment() {
        final TaskFragmentContainer container = mController.newContainer(mActivity, TASK_ID);
        mPresenter.createTaskFragment(mTransaction, container.getTaskFragmentToken(),
                mActivity.getActivityToken(), TASK_BOUNDS, WINDOWING_MODE_MULTI_WINDOW);

        assertTrue(container.areLastRequestedBoundsEqual(TASK_BOUNDS));
        assertTrue(container.isLastRequestedWindowingModeEqual(WINDOWING_MODE_MULTI_WINDOW));
        verify(mTransaction).createTaskFragment(any());
    }

    @Test
    public void testResizeTaskFragment() {
        final TaskFragmentContainer container = mController.newContainer(mActivity, TASK_ID);
        mPresenter.mFragmentInfos.put(container.getTaskFragmentToken(), mTaskFragmentInfo);
        mPresenter.resizeTaskFragment(mTransaction, container.getTaskFragmentToken(), TASK_BOUNDS);

        assertTrue(container.areLastRequestedBoundsEqual(TASK_BOUNDS));
        verify(mTransaction).setBounds(any(), eq(TASK_BOUNDS));

        // No request to set the same bounds.
        clearInvocations(mTransaction);
        mPresenter.resizeTaskFragment(mTransaction, container.getTaskFragmentToken(), TASK_BOUNDS);

        verify(mTransaction, never()).setBounds(any(), any());
    }

    @Test
    public void testUpdateWindowingMode() {
        final TaskFragmentContainer container = mController.newContainer(mActivity, TASK_ID);
        mPresenter.mFragmentInfos.put(container.getTaskFragmentToken(), mTaskFragmentInfo);
        mPresenter.updateWindowingMode(mTransaction, container.getTaskFragmentToken(),
                WINDOWING_MODE_MULTI_WINDOW);

        assertTrue(container.isLastRequestedWindowingModeEqual(WINDOWING_MODE_MULTI_WINDOW));
        verify(mTransaction).setWindowingMode(any(), eq(WINDOWING_MODE_MULTI_WINDOW));

        // No request to set the same windowing mode.
        clearInvocations(mTransaction);
        mPresenter.updateWindowingMode(mTransaction, container.getTaskFragmentToken(),
                WINDOWING_MODE_MULTI_WINDOW);

        verify(mTransaction, never()).setWindowingMode(any(), anyInt());

    }
}
