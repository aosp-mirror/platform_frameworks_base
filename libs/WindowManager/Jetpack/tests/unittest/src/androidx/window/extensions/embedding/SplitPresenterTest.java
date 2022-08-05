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

import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_BOUNDS;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_ID;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createActivityInfoWithMinDimensions;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createMockTaskFragmentInfo;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitRule;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.getSplitBounds;
import static androidx.window.extensions.embedding.SplitPresenter.POSITION_END;
import static androidx.window.extensions.embedding.SplitPresenter.POSITION_FILL;
import static androidx.window.extensions.embedding.SplitPresenter.POSITION_START;
import static androidx.window.extensions.embedding.SplitPresenter.RESULT_EXPANDED;
import static androidx.window.extensions.embedding.SplitPresenter.RESULT_EXPAND_FAILED_NO_TF_INFO;
import static androidx.window.extensions.embedding.SplitPresenter.RESULT_NOT_EXPANDED;
import static androidx.window.extensions.embedding.SplitPresenter.getBoundsForPosition;
import static androidx.window.extensions.embedding.SplitPresenter.getMinDimensions;
import static androidx.window.extensions.embedding.SplitPresenter.shouldShowSideBySide;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.util.Size;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.test.core.app.ApplicationProvider;
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
        mActivity = createMockActivity();
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

    @Test
    public void testGetMinDimensionsForIntent() {
        final Intent intent = new Intent(ApplicationProvider.getApplicationContext(),
                MinimumDimensionActivity.class);
        assertEquals(new Size(600, 1200), getMinDimensions(intent));
    }

    @Test
    public void testShouldShowSideBySide() {
        Activity secondaryActivity = createMockActivity();
        final SplitRule splitRule = createSplitRule(mActivity, secondaryActivity);

        assertTrue(shouldShowSideBySide(TASK_BOUNDS, splitRule));

        // Set minDimensions of primary container to larger than primary bounds.
        final Rect primaryBounds = getSplitBounds(true /* isPrimary */);
        Pair<Size, Size> minDimensionsPair = new Pair<>(
                new Size(primaryBounds.width() + 1, primaryBounds.height() + 1), null);

        assertFalse(shouldShowSideBySide(TASK_BOUNDS, splitRule, minDimensionsPair));
    }

    @Test
    public void testGetBoundsForPosition() {
        Activity secondaryActivity = createMockActivity();
        final SplitRule splitRule = createSplitRule(mActivity, secondaryActivity);
        final Rect primaryBounds = getSplitBounds(true /* isPrimary */);
        final Rect secondaryBounds = getSplitBounds(false /* isPrimary */);

        assertEquals("Primary bounds must be reported.",
                primaryBounds,
                getBoundsForPosition(POSITION_START, TASK_BOUNDS, splitRule,
                        mActivity, null /* miniDimensionsPair */));

        assertEquals("Secondary bounds must be reported.",
                secondaryBounds,
                getBoundsForPosition(POSITION_END, TASK_BOUNDS, splitRule,
                        mActivity, null /* miniDimensionsPair */));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                getBoundsForPosition(POSITION_FILL, TASK_BOUNDS, splitRule,
                        mActivity, null /* miniDimensionsPair */));

        Pair<Size, Size> minDimensionsPair = new Pair<>(
                new Size(primaryBounds.width() + 1, primaryBounds.height() + 1), null);

        assertEquals("Fullscreen bounds must be reported because of min dimensions.",
                new Rect(),
                getBoundsForPosition(POSITION_START, TASK_BOUNDS,
                        splitRule, mActivity, minDimensionsPair));
    }

    @Test
    public void testExpandSplitContainerIfNeeded() {
        SplitContainer splitContainer = mock(SplitContainer.class);
        Activity secondaryActivity = createMockActivity();
        SplitRule splitRule = createSplitRule(mActivity, secondaryActivity);
        TaskFragmentContainer primaryTf = mController.newContainer(mActivity, TASK_ID);
        TaskFragmentContainer secondaryTf = mController.newContainer(secondaryActivity, TASK_ID);
        doReturn(splitRule).when(splitContainer).getSplitRule();
        doReturn(primaryTf).when(splitContainer).getPrimaryContainer();
        doReturn(secondaryTf).when(splitContainer).getSecondaryContainer();

        assertThrows(IllegalArgumentException.class, () ->
                mPresenter.expandSplitContainerIfNeeded(mTransaction, splitContainer, mActivity,
                        null /* secondaryActivity */, null /* secondaryIntent */));

        assertEquals(RESULT_NOT_EXPANDED, mPresenter.expandSplitContainerIfNeeded(mTransaction,
                splitContainer, mActivity, secondaryActivity, null /* secondaryIntent */));
        verify(mPresenter, never()).expandTaskFragment(any(), any());

        doReturn(createActivityInfoWithMinDimensions()).when(secondaryActivity).getActivityInfo();
        assertEquals(RESULT_EXPAND_FAILED_NO_TF_INFO, mPresenter.expandSplitContainerIfNeeded(
                mTransaction, splitContainer, mActivity, secondaryActivity,
                null /* secondaryIntent */));

        primaryTf.setInfo(createMockTaskFragmentInfo(primaryTf, mActivity));
        secondaryTf.setInfo(createMockTaskFragmentInfo(secondaryTf, secondaryActivity));

        assertEquals(RESULT_EXPANDED, mPresenter.expandSplitContainerIfNeeded(mTransaction,
                splitContainer, mActivity, secondaryActivity, null /* secondaryIntent */));
        verify(mPresenter).expandTaskFragment(eq(mTransaction),
                eq(primaryTf.getTaskFragmentToken()));
        verify(mPresenter).expandTaskFragment(eq(mTransaction),
                eq(secondaryTf.getTaskFragmentToken()));

        clearInvocations(mPresenter);

        assertEquals(RESULT_EXPANDED, mPresenter.expandSplitContainerIfNeeded(mTransaction,
                splitContainer, mActivity, null /* secondaryActivity */,
                new Intent(ApplicationProvider.getApplicationContext(),
                        MinimumDimensionActivity.class)));
        verify(mPresenter).expandTaskFragment(eq(mTransaction),
                eq(primaryTf.getTaskFragmentToken()));
        verify(mPresenter).expandTaskFragment(eq(mTransaction),
                eq(secondaryTf.getTaskFragmentToken()));
    }

    private Activity createMockActivity() {
        final Activity activity = mock(Activity.class);
        final Configuration activityConfig = new Configuration();
        activityConfig.windowConfiguration.setBounds(TASK_BOUNDS);
        activityConfig.windowConfiguration.setMaxBounds(TASK_BOUNDS);
        doReturn(mActivityResources).when(activity).getResources();
        doReturn(activityConfig).when(mActivityResources).getConfiguration();
        doReturn(new ActivityInfo()).when(activity).getActivityInfo();
        doReturn(mock(IBinder.class)).when(activity).getActivityToken();
        return activity;
    }
}
