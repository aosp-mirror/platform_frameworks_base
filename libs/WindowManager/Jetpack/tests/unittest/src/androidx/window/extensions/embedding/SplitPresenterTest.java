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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_ANIMATION_PARAMS;

import static androidx.window.extensions.embedding.EmbeddingTestUtils.DEFAULT_FINISH_PRIMARY_WITH_SECONDARY;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.DEFAULT_FINISH_SECONDARY_WITH_PRIMARY;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.SPLIT_ATTRIBUTES;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_BOUNDS;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_ID;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createActivityInfoWithMinDimensions;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createMockTaskFragmentInfo;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitPairRuleBuilder;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitRule;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createWindowLayoutInfo;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.getSplitBounds;
import static androidx.window.extensions.embedding.SplitPresenter.EXPAND_CONTAINERS_ATTRIBUTES;
import static androidx.window.extensions.embedding.SplitPresenter.POSITION_END;
import static androidx.window.extensions.embedding.SplitPresenter.POSITION_FILL;
import static androidx.window.extensions.embedding.SplitPresenter.POSITION_START;
import static androidx.window.extensions.embedding.SplitPresenter.RESULT_EXPANDED;
import static androidx.window.extensions.embedding.SplitPresenter.RESULT_EXPAND_FAILED_NO_TF_INFO;
import static androidx.window.extensions.embedding.SplitPresenter.RESULT_NOT_EXPANDED;
import static androidx.window.extensions.embedding.SplitPresenter.getMinDimensions;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import android.graphics.Color;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.util.Size;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOperation;
import android.window.WindowContainerTransaction;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.extensions.core.util.function.Function;
import androidx.window.extensions.layout.WindowLayoutComponentImpl;
import androidx.window.extensions.layout.WindowLayoutInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Test class for {@link SplitPresenter}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:SplitPresenterTest
 */
// Suppress GuardedBy warning on unit tests
@SuppressWarnings("GuardedBy")
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
    @Mock
    private WindowLayoutComponentImpl mWindowLayoutComponent;
    private SplitController mController;
    private SplitPresenter mPresenter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(new WindowLayoutInfo(new ArrayList<>())).when(mWindowLayoutComponent)
                .getCurrentWindowLayoutInfo(anyInt(), any());
        DeviceStateManagerFoldingFeatureProducer producer =
                mock(DeviceStateManagerFoldingFeatureProducer.class);
        mController = new SplitController(mWindowLayoutComponent, producer);
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
    public void testUpdateAnimationParams() {
        final TaskFragmentContainer container = mController.newContainer(mActivity, TASK_ID);

        // Verify the default.
        assertTrue(container.areLastRequestedAnimationParamsEqual(
                TaskFragmentAnimationParams.DEFAULT));

        final int bgColor = Color.GREEN;
        final TaskFragmentAnimationParams animationParams =
                new TaskFragmentAnimationParams.Builder()
                        .setAnimationBackgroundColor(bgColor)
                        .build();
        mPresenter.updateAnimationParams(mTransaction, container.getTaskFragmentToken(),
                animationParams);

        final TaskFragmentOperation expectedOperation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_ANIMATION_PARAMS)
                .setAnimationParams(animationParams)
                .build();
        verify(mTransaction).setTaskFragmentOperation(container.getTaskFragmentToken(),
                expectedOperation);
        assertTrue(container.areLastRequestedAnimationParamsEqual(animationParams));

        // No request to set the same animation params.
        clearInvocations(mTransaction);
        mPresenter.updateAnimationParams(mTransaction, container.getTaskFragmentToken(),
                animationParams);

        verify(mTransaction, never()).setTaskFragmentOperation(any(), any());
    }

    @Test
    public void testGetMinDimensionsForIntent() {
        final Intent intent = new Intent(ApplicationProvider.getApplicationContext(),
                MinimumDimensionActivity.class);
        assertEquals(new Size(600, 1200), getMinDimensions(intent));
    }

    @Test
    public void testShouldShowSideBySide() {
        assertTrue(SplitPresenter.shouldShowSplit(SPLIT_ATTRIBUTES));

        final SplitAttributes expandContainers = new SplitAttributes.Builder()
                .setSplitType(new SplitAttributes.SplitType.ExpandContainersSplitType())
                .build();

        assertFalse(SplitPresenter.shouldShowSplit(expandContainers));
    }

    @Test
    public void testGetBoundsForPosition_expandContainers() {
        final TaskContainer.TaskProperties taskProperties = getTaskProperty();
        final SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(new SplitAttributes.SplitType.ExpandContainersSplitType())
                .build();

        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));
    }

    @Test
    public void testGetBoundsForPosition_splitVertically() {
        final Rect primaryBounds = getSplitBounds(true /* isPrimary */,
                false /* splitHorizontally */);
        final Rect secondaryBounds = getSplitBounds(false /* isPrimary */,
                false /* splitHorizontally */);
        final TaskContainer.TaskProperties taskProperties = getTaskProperty();
        SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.RatioSplitType.splitEqually())
                .setLayoutDirection(SplitAttributes.LayoutDirection.LEFT_TO_RIGHT)
                .build();

        assertEquals("Primary bounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("Secondary bounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));

        splitAttributes = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.RatioSplitType.splitEqually())
                .setLayoutDirection(SplitAttributes.LayoutDirection.RIGHT_TO_LEFT)
                .build();

        assertEquals("Secondary bounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("Primary bounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));

        splitAttributes = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.RatioSplitType.splitEqually())
                .setLayoutDirection(SplitAttributes.LayoutDirection.LOCALE)
                .build();
        // Layout direction should follow screen layout for SplitAttributes.LayoutDirection.LOCALE.
        taskProperties.getConfiguration().screenLayout |= Configuration.SCREENLAYOUT_LAYOUTDIR_RTL;

        assertEquals("Secondary bounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("Primary bounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));
    }

    @Test
    public void testGetBoundsForPosition_splitHorizontally() {
        final Rect primaryBounds = getSplitBounds(true /* isPrimary */,
                true /* splitHorizontally */);
        final Rect secondaryBounds = getSplitBounds(false /* isPrimary */,
                true /* splitHorizontally */);
        final TaskContainer.TaskProperties taskProperties = getTaskProperty();
        SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.RatioSplitType.splitEqually())
                .setLayoutDirection(SplitAttributes.LayoutDirection.TOP_TO_BOTTOM)
                .build();

        assertEquals("Primary bounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("Secondary bounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));

        splitAttributes = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.RatioSplitType.splitEqually())
                .setLayoutDirection(SplitAttributes.LayoutDirection.BOTTOM_TO_TOP)
                .build();

        assertEquals("Secondary bounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("Primary bounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));
    }

    @Test
    public void testGetBoundsForPosition_useHingeFallback() {
        final Rect primaryBounds = getSplitBounds(true /* isPrimary */,
                false /* splitHorizontally */);
        final Rect secondaryBounds = getSplitBounds(false /* isPrimary */,
                false /* splitHorizontally */);
        final TaskContainer.TaskProperties taskProperties = getTaskProperty();
        final SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(new SplitAttributes.SplitType.HingeSplitType(
                        SplitAttributes.SplitType.RatioSplitType.splitEqually()
                )).setLayoutDirection(SplitAttributes.LayoutDirection.LEFT_TO_RIGHT)
                .build();

        // There's no hinge on the device. Use fallback SplitType.
        doReturn(new WindowLayoutInfo(new ArrayList<>())).when(mWindowLayoutComponent)
                .getCurrentWindowLayoutInfo(anyInt(), any());

        assertEquals("PrimaryBounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("SecondaryBounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));

        // Hinge is reported, but the host task is in multi-window mode. Still use fallback
        // splitType.
        doReturn(createWindowLayoutInfo()).when(mWindowLayoutComponent)
                .getCurrentWindowLayoutInfo(anyInt(), any());
        taskProperties.getConfiguration().windowConfiguration
                .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);

        assertEquals("PrimaryBounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("SecondaryBounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));

        // Hinge is reported, and the host task is in fullscreen, but layout direction doesn't match
        // folding area orientation. Still use fallback splitType.
        doReturn(createWindowLayoutInfo()).when(mWindowLayoutComponent)
                .getCurrentWindowLayoutInfo(anyInt(), any());
        taskProperties.getConfiguration().windowConfiguration
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        assertEquals("PrimaryBounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("SecondaryBounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));
    }

    @Test
    public void testGetBoundsForPosition_fallbackToExpandContainers() {
        final TaskContainer.TaskProperties taskProperties = getTaskProperty();
        final SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(new SplitAttributes.SplitType.HingeSplitType(
                        new SplitAttributes.SplitType.ExpandContainersSplitType()
                )).setLayoutDirection(SplitAttributes.LayoutDirection.LEFT_TO_RIGHT)
                .build();

        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));
    }

    @Test
    public void testGetBoundsForPosition_useHingeSplitType() {
        final TaskContainer.TaskProperties taskProperties = getTaskProperty();
        final SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(new SplitAttributes.SplitType.HingeSplitType(
                        new SplitAttributes.SplitType.ExpandContainersSplitType()
                )).setLayoutDirection(SplitAttributes.LayoutDirection.TOP_TO_BOTTOM)
                .build();
        final WindowLayoutInfo windowLayoutInfo = createWindowLayoutInfo();
        doReturn(windowLayoutInfo).when(mWindowLayoutComponent)
                .getCurrentWindowLayoutInfo(anyInt(), any());
        final Rect hingeBounds = windowLayoutInfo.getDisplayFeatures().get(0).getBounds();
        final Rect primaryBounds = new Rect(
                TASK_BOUNDS.left,
                TASK_BOUNDS.top,
                TASK_BOUNDS.right,
                hingeBounds.top
        );
        final Rect secondaryBounds = new Rect(
                TASK_BOUNDS.left,
                hingeBounds.bottom,
                TASK_BOUNDS.right,
                TASK_BOUNDS.bottom
        );

        assertEquals("PrimaryBounds must be reported.",
                primaryBounds,
                mPresenter.getBoundsForPosition(POSITION_START, taskProperties, splitAttributes));

        assertEquals("SecondaryBounds must be reported.",
                secondaryBounds,
                mPresenter.getBoundsForPosition(POSITION_END, taskProperties, splitAttributes));
        assertEquals("Task bounds must be reported.",
                new Rect(),
                mPresenter.getBoundsForPosition(POSITION_FILL, taskProperties, splitAttributes));
    }

    @Test
    public void testExpandSplitContainerIfNeeded() {
        Activity secondaryActivity = createMockActivity();
        SplitRule splitRule = createSplitRule(mActivity, secondaryActivity);
        TaskFragmentContainer primaryTf = mController.newContainer(mActivity, TASK_ID);
        TaskFragmentContainer secondaryTf = mController.newContainer(secondaryActivity, TASK_ID);
        SplitContainer splitContainer = new SplitContainer(primaryTf, secondaryActivity,
                secondaryTf, splitRule, SPLIT_ATTRIBUTES);

        assertThrows(IllegalArgumentException.class, () ->
                mPresenter.expandSplitContainerIfNeeded(mTransaction, splitContainer, mActivity,
                        null /* secondaryActivity */, null /* secondaryIntent */));

        assertEquals(RESULT_NOT_EXPANDED, mPresenter.expandSplitContainerIfNeeded(mTransaction,
                splitContainer, mActivity, secondaryActivity, null /* secondaryIntent */));
        verify(mPresenter, never()).expandTaskFragment(any(), any());

        splitContainer.setSplitAttributes(SPLIT_ATTRIBUTES);
        doReturn(createActivityInfoWithMinDimensions()).when(secondaryActivity).getActivityInfo();
        assertEquals(RESULT_EXPAND_FAILED_NO_TF_INFO, mPresenter.expandSplitContainerIfNeeded(
                mTransaction, splitContainer, mActivity, secondaryActivity,
                null /* secondaryIntent */));

        splitContainer.setSplitAttributes(SPLIT_ATTRIBUTES);
        primaryTf.setInfo(mTransaction, createMockTaskFragmentInfo(primaryTf, mActivity));
        secondaryTf.setInfo(mTransaction,
                createMockTaskFragmentInfo(secondaryTf, secondaryActivity));

        assertEquals(RESULT_EXPANDED, mPresenter.expandSplitContainerIfNeeded(mTransaction,
                splitContainer, mActivity, secondaryActivity, null /* secondaryIntent */));
        verify(mPresenter).expandTaskFragment(mTransaction, primaryTf.getTaskFragmentToken());
        verify(mPresenter).expandTaskFragment(mTransaction, secondaryTf.getTaskFragmentToken());

        splitContainer.setSplitAttributes(SPLIT_ATTRIBUTES);
        clearInvocations(mPresenter);

        assertEquals(RESULT_EXPANDED, mPresenter.expandSplitContainerIfNeeded(mTransaction,
                splitContainer, mActivity, null /* secondaryActivity */,
                new Intent(ApplicationProvider.getApplicationContext(),
                        MinimumDimensionActivity.class)));
        verify(mPresenter).expandTaskFragment(mTransaction, primaryTf.getTaskFragmentToken());
        verify(mPresenter).expandTaskFragment(mTransaction, secondaryTf.getTaskFragmentToken());
    }

    @Test
    public void testCreateNewSplitContainer_secondaryAbovePrimary() {
        final Activity secondaryActivity = createMockActivity();
        final TaskFragmentContainer bottomTf = mController.newContainer(secondaryActivity, TASK_ID);
        final TaskFragmentContainer primaryTf = mController.newContainer(mActivity, TASK_ID);
        final SplitPairRule rule = createSplitPairRuleBuilder(pair ->
                pair.first == mActivity && pair.second == secondaryActivity, pair -> false,
                metrics -> true)
                .setDefaultSplitAttributes(SPLIT_ATTRIBUTES)
                .setShouldClearTop(false)
                .build();

        mPresenter.createNewSplitContainer(mTransaction, mActivity, secondaryActivity, rule);

        assertEquals(primaryTf, mController.getContainerWithActivity(mActivity));
        final TaskFragmentContainer secondaryTf = mController.getContainerWithActivity(
                secondaryActivity);
        assertNotEquals(bottomTf, secondaryTf);
        assertTrue(secondaryTf.isAbove(primaryTf));
    }

    @Test
    public void testComputeSplitAttributes() {
        final SplitPairRule splitPairRule = createSplitPairRuleBuilder(
                activityPair -> true,
                activityIntentPair -> true,
                windowMetrics -> windowMetrics.getBounds().equals(TASK_BOUNDS))
                .setFinishSecondaryWithPrimary(DEFAULT_FINISH_SECONDARY_WITH_PRIMARY)
                .setFinishPrimaryWithSecondary(DEFAULT_FINISH_PRIMARY_WITH_SECONDARY)
                .setDefaultSplitAttributes(SPLIT_ATTRIBUTES)
                .build();
        final TaskContainer.TaskProperties taskProperties = getTaskProperty();

        assertEquals(SPLIT_ATTRIBUTES, mPresenter.computeSplitAttributes(taskProperties,
                splitPairRule, null /* minDimensionsPair */));

        final Pair<Size, Size> minDimensionsPair = new Pair<>(
                new Size(TASK_BOUNDS.width(), TASK_BOUNDS.height()), null);

        assertEquals(EXPAND_CONTAINERS_ATTRIBUTES, mPresenter.computeSplitAttributes(taskProperties,
                splitPairRule, minDimensionsPair));

        taskProperties.getConfiguration().windowConfiguration.setBounds(new Rect(
                TASK_BOUNDS.left + 1, TASK_BOUNDS.top + 1, TASK_BOUNDS.right + 1,
                TASK_BOUNDS.bottom + 1));

        assertEquals(EXPAND_CONTAINERS_ATTRIBUTES, mPresenter.computeSplitAttributes(taskProperties,
                splitPairRule, null /* minDimensionsPair */));

        final SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(
                        new SplitAttributes.SplitType.HingeSplitType(
                                SplitAttributes.SplitType.RatioSplitType.splitEqually()
                        )
                ).build();
        final Function<SplitAttributesCalculatorParams, SplitAttributes> calculator =
                params -> splitAttributes;

        mController.setSplitAttributesCalculator(calculator);

        assertEquals(splitAttributes, mPresenter.computeSplitAttributes(taskProperties,
                splitPairRule, null /* minDimensionsPair */));
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

    private static TaskContainer.TaskProperties getTaskProperty() {
        final Configuration configuration = new Configuration();
        configuration.windowConfiguration.setBounds(TASK_BOUNDS);
        return new TaskContainer.TaskProperties(DEFAULT_DISPLAY, configuration);
    }
}
