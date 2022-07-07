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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static androidx.window.extensions.embedding.EmbeddingTestUtils.SPLIT_RATIO;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_BOUNDS;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_ID;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createActivityInfoWithMinDimensions;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createMockTaskFragmentInfo;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitRule;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.getSplitBounds;
import static androidx.window.extensions.embedding.SplitRule.FINISH_ALWAYS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
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

import java.util.ArrayList;
import java.util.Collections;
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
    private static final Intent PLACEHOLDER_INTENT = new Intent().setComponent(
            new ComponentName("test", "placeholder"));

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
        doNothing().when(mSplitPresenter).applyTransaction(any());
        final Configuration activityConfig = new Configuration();
        activityConfig.windowConfiguration.setBounds(TASK_BOUNDS);
        activityConfig.windowConfiguration.setMaxBounds(TASK_BOUNDS);
        doReturn(activityConfig).when(mActivityResources).getConfiguration();
        doReturn(mHandler).when(mSplitController).getHandler();
        mActivity = createMockActivity();
    }

    @Test
    public void testGetTopActiveContainer() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        // tf1 has no running activity so is not active.
        final TaskFragmentContainer tf1 = new TaskFragmentContainer(null /* activity */,
                new Intent(), taskContainer, mSplitController);
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

        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, mActivity,
                TASK_ID);
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
        doReturn(true).when(mSplitController).launchPlaceholderIfNecessary(mActivity,
                false /* isOnCreated */);
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

        verify(mSplitPresenter).updateSplitContainer(splitContainer, tf, mTransaction);
    }

    @Test
    public void testOnActivityCreated() {
        mSplitController.onActivityCreated(mActivity);

        // Disallow to split as primary because we want the new launch to be always on top.
        verify(mSplitController).resolveActivityToContainer(mActivity, false /* isOnReparent */);
    }

    @Test
    public void testOnActivityReparentToTask_sameProcess() {
        mSplitController.onActivityReparentToTask(TASK_ID, new Intent(),
                mActivity.getActivityToken());

        // Treated as on activity created, but allow to split as primary.
        verify(mSplitController).resolveActivityToContainer(mActivity, true /* isOnReparent */);
        // Try to place the activity to the top TaskFragment when there is no matched rule.
        verify(mSplitController).placeActivityInTopContainer(mActivity);
    }

    @Test
    public void testOnActivityReparentToTask_diffProcess() {
        // Create an empty TaskFragment to initialize for the Task.
        mSplitController.newContainer(new Intent(), mActivity, TASK_ID);
        final IBinder activityToken = new Binder();
        final Intent intent = new Intent();

        mSplitController.onActivityReparentToTask(TASK_ID, intent, activityToken);

        // Treated as starting new intent
        verify(mSplitController, never()).resolveActivityToContainer(any(), anyBoolean());
        verify(mSplitController).resolveStartActivityIntent(any(), eq(TASK_ID), eq(intent),
                isNull());
    }

    @Test
    public void testResolveStartActivityIntent_withoutLaunchingActivity() {
        final Intent intent = new Intent();
        final ActivityRule expandRule = new ActivityRule.Builder(r -> false, i -> i == intent)
                .setShouldAlwaysExpand(true)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(expandRule));

        // No other activity available in the Task.
        TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(mTransaction,
                TASK_ID, intent, null /* launchingActivity */);
        assertNull(container);

        // Task contains another activity that can be used as owner activity.
        createMockTaskFragmentContainer(mActivity);
        container = mSplitController.resolveStartActivityIntent(mTransaction,
                TASK_ID, intent, null /* launchingActivity */);
        assertNotNull(container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldExpand() {
        final Intent intent = new Intent();
        setupExpandRule(intent);
        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);

        assertNotNull(container);
        assertTrue(container.areLastRequestedBoundsEqual(null));
        assertTrue(container.isLastRequestedWindowingModeEqual(WINDOWING_MODE_UNDEFINED));
        assertFalse(container.hasActivity(mActivity.getActivityToken()));
        verify(mSplitPresenter).createTaskFragment(mTransaction, container.getTaskFragmentToken(),
                mActivity.getActivityToken(), new Rect(), WINDOWING_MODE_UNDEFINED);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithLaunchingActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithTopExpandActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);
        createMockTaskFragmentContainer(mActivity);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, null /* launchingActivity */);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithTopSecondaryActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);
        final Activity primaryActivity = createMockActivity();
        addSplitTaskFragments(primaryActivity, mActivity);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, null /* launchingActivity */);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithTopPrimaryActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(mActivity, secondaryActivity);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, null /* launchingActivity */);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldLaunchInFullscreen() {
        final Intent intent = new Intent().setComponent(
                new ComponentName(ApplicationProvider.getApplicationContext(),
                        MinimumDimensionActivity.class));
        setupSplitRule(mActivity, intent);
        final Activity primaryActivity = createMockActivity();
        addSplitTaskFragments(primaryActivity, mActivity);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, null /* launchingActivity */);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertNotNull(mSplitController.getActiveSplitForContainers(primaryContainer, container));
        assertTrue(primaryContainer.areLastRequestedBoundsEqual(null));
        assertTrue(container.areLastRequestedBoundsEqual(null));
    }

    @Test
    public void testResolveStartActivityIntent_shouldExpandSplitContainer() {
        final Intent intent = new Intent().setComponent(
                new ComponentName(ApplicationProvider.getApplicationContext(),
                        MinimumDimensionActivity.class));
        setupSplitRule(mActivity, intent, false /* clearTop */);
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(mActivity, secondaryActivity, false /* clearTop */);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertNotNull(mSplitController.getActiveSplitForContainers(primaryContainer, container));
        assertTrue(primaryContainer.areLastRequestedBoundsEqual(null));
        assertTrue(container.areLastRequestedBoundsEqual(null));
        assertEquals(container, mSplitController.getContainerWithActivity(secondaryActivity));
    }

    @Test
    public void testResolveStartActivityIntent_noInfo_shouldCreateSplitContainer() {
        final Intent intent = new Intent().setComponent(
                new ComponentName(ApplicationProvider.getApplicationContext(),
                        MinimumDimensionActivity.class));
        setupSplitRule(mActivity, intent, false /* clearTop */);
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(mActivity, secondaryActivity, false /* clearTop */);

        final TaskFragmentContainer secondaryContainer = mSplitController
                .getContainerWithActivity(secondaryActivity);
        secondaryContainer.mInfo = null;

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertNotNull(mSplitController.getActiveSplitForContainers(primaryContainer, container));
        assertTrue(primaryContainer.areLastRequestedBoundsEqual(null));
        assertTrue(container.areLastRequestedBoundsEqual(null));
        assertNotEquals(container, secondaryContainer);
    }

    @Test
    public void testPlaceActivityInTopContainer() {
        mSplitController.placeActivityInTopContainer(mActivity);

        verify(mSplitPresenter, never()).applyTransaction(any());

        mSplitController.newContainer(new Intent(), mActivity, TASK_ID);
        mSplitController.placeActivityInTopContainer(mActivity);

        verify(mSplitPresenter).applyTransaction(any());

        // Not reparent if activity is in a TaskFragment.
        clearInvocations(mSplitPresenter);
        mSplitController.newContainer(mActivity, TASK_ID);
        mSplitController.placeActivityInTopContainer(mActivity);

        verify(mSplitPresenter, never()).applyTransaction(any());
    }

    @Test
    public void testResolveActivityToContainer_noRuleMatched() {
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertFalse(result);
        verify(mSplitController, never()).newContainer(any(), any(), any(), anyInt());
    }

    @Test
    public void testResolveActivityToContainer_expandRule_notInTaskFragment() {
        setupExpandRule(mActivity);

        // When the activity is not in any TaskFragment, create a new expanded TaskFragment for it.
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);
        final TaskFragmentContainer container = mSplitController.getContainerWithActivity(
                mActivity);

        assertTrue(result);
        assertNotNull(container);
        verify(mSplitController).newContainer(mActivity, TASK_ID);
        verify(mSplitPresenter).expandActivity(container.getTaskFragmentToken(), mActivity);
    }

    @Test
    public void testResolveActivityToContainer_expandRule_inSingleTaskFragment() {
        setupExpandRule(mActivity);

        // When the activity is not in any TaskFragment, create a new expanded TaskFragment for it.
        final TaskFragmentContainer container = mSplitController.newContainer(mActivity, TASK_ID);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).expandTaskFragment(container.getTaskFragmentToken());
    }

    @Test
    public void testResolveActivityToContainer_expandRule_inSplitTaskFragment() {
        setupExpandRule(mActivity);

        // When the activity is not in any TaskFragment, create a new expanded TaskFragment for it.
        final Activity activity = createMockActivity();
        addSplitTaskFragments(activity, mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);
        final TaskFragmentContainer container = mSplitController.getContainerWithActivity(
                mActivity);

        assertTrue(result);
        assertNotNull(container);
        verify(mSplitPresenter).expandActivity(container.getTaskFragmentToken(), mActivity);
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_notInTaskFragment() {
        setupPlaceholderRule(mActivity);
        final SplitPlaceholderRule placeholderRule =
                (SplitPlaceholderRule) mSplitController.getSplitRules().get(0);

        // Launch placeholder if the activity is not in any TaskFragment.
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).startActivityToSide(mActivity, PLACEHOLDER_INTENT,
                mSplitController.getPlaceholderOptions(mActivity, true /* isOnCreated */),
                placeholderRule, true /* isPlaceholder */);
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inOccludedTaskFragment() {
        setupPlaceholderRule(mActivity);

        // Don't launch placeholder if the activity is not in the topmost active TaskFragment.
        final Activity activity = createMockActivity();
        mSplitController.newContainer(mActivity, TASK_ID);
        mSplitController.newContainer(activity, TASK_ID);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertFalse(result);
        verify(mSplitPresenter, never()).startActivityToSide(any(), any(), any(), any(),
                anyBoolean());
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inTopMostTaskFragment() {
        setupPlaceholderRule(mActivity);
        final SplitPlaceholderRule placeholderRule =
                (SplitPlaceholderRule) mSplitController.getSplitRules().get(0);

        // Launch placeholder if the activity is in the topmost expanded TaskFragment.
        mSplitController.newContainer(mActivity, TASK_ID);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).startActivityToSide(mActivity, PLACEHOLDER_INTENT,
                mSplitController.getPlaceholderOptions(mActivity, true /* isOnCreated */),
                placeholderRule, true /* isPlaceholder */);
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inPrimarySplit() {
        setupPlaceholderRule(mActivity);

        // Don't launch placeholder if the activity is in primary split.
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(mActivity, secondaryActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertFalse(result);
        verify(mSplitPresenter, never()).startActivityToSide(any(), any(), any(), any(),
                anyBoolean());
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inSecondarySplit() {
        setupPlaceholderRule(mActivity);
        final SplitPlaceholderRule placeholderRule =
                (SplitPlaceholderRule) mSplitController.getSplitRules().get(0);

        // Launch placeholder if the activity is in secondary split.
        final Activity primaryActivity = createMockActivity();
        addSplitTaskFragments(primaryActivity, mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).startActivityToSide(mActivity, PLACEHOLDER_INTENT,
                mSplitController.getPlaceholderOptions(mActivity, true /* isOnCreated */),
                placeholderRule, true /* isPlaceholder */);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_inPrimarySplitWithRuleMatched() {
        final Intent secondaryIntent = new Intent();
        setupSplitRule(mActivity, secondaryIntent);
        final SplitPairRule splitRule = (SplitPairRule) mSplitController.getSplitRules().get(0);

        // Activity is already in primary split, no need to create new split.
        final TaskFragmentContainer primaryContainer = mSplitController.newContainer(mActivity,
                TASK_ID);
        final TaskFragmentContainer secondaryContainer = mSplitController.newContainer(
                secondaryIntent, mActivity, TASK_ID);
        mSplitController.registerSplit(
                mTransaction,
                primaryContainer,
                mActivity,
                secondaryContainer,
                splitRule);
        clearInvocations(mSplitController);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitController, never()).newContainer(any(), any(), any(), anyInt());
        verify(mSplitController, never()).registerSplit(any(), any(), any(), any(), any());
    }

    @Test
    public void testResolveActivityToContainer_splitRule_inPrimarySplitWithNoRuleMatched() {
        final Intent secondaryIntent = new Intent();
        setupSplitRule(mActivity, secondaryIntent);
        final SplitPairRule splitRule = (SplitPairRule) mSplitController.getSplitRules().get(0);

        // The new launched activity is in primary split, but there is no rule for it to split with
        // the secondary, so return false.
        final TaskFragmentContainer primaryContainer = mSplitController.newContainer(mActivity,
                TASK_ID);
        final TaskFragmentContainer secondaryContainer = mSplitController.newContainer(
                secondaryIntent, mActivity, TASK_ID);
        mSplitController.registerSplit(
                mTransaction,
                primaryContainer,
                mActivity,
                secondaryContainer,
                splitRule);
        final Activity launchedActivity = createMockActivity();
        primaryContainer.addPendingAppearedActivity(launchedActivity);

        assertFalse(mSplitController.resolveActivityToContainer(launchedActivity,
                false /* isOnReparent */));
    }

    @Test
    public void testResolveActivityToContainer_splitRule_inSecondarySplitWithRuleMatched() {
        final Activity primaryActivity = createMockActivity();
        setupSplitRule(primaryActivity, mActivity);

        // Activity is already in secondary split, no need to create new split.
        addSplitTaskFragments(primaryActivity, mActivity);
        clearInvocations(mSplitController);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitController, never()).newContainer(any(), any(), any(), anyInt());
        verify(mSplitController, never()).registerSplit(any(), any(), any(), any(), any());
    }

    @Test
    public void testResolveActivityToContainer_splitRule_inSecondarySplitWithNoRuleMatched() {
        final Activity primaryActivity = createMockActivity();
        final Activity secondaryActivity = createMockActivity();
        setupSplitRule(primaryActivity, secondaryActivity);

        // Activity is in secondary split, but there is no rule to split it with primary.
        addSplitTaskFragments(primaryActivity, secondaryActivity);
        mSplitController.getContainerWithActivity(secondaryActivity)
                .addPendingAppearedActivity(mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertFalse(result);
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_isPlaceholderWithRuleMatched() {
        final Activity primaryActivity = createMockActivity();
        setupPlaceholderRule(primaryActivity);
        final SplitPlaceholderRule placeholderRule =
                (SplitPlaceholderRule) mSplitController.getSplitRules().get(0);
        doReturn(PLACEHOLDER_INTENT).when(mActivity).getIntent();

        // Activity is a placeholder.
        final TaskFragmentContainer primaryContainer = mSplitController.newContainer(
                primaryActivity, TASK_ID);
        final TaskFragmentContainer secondaryContainer = mSplitController.newContainer(mActivity,
                TASK_ID);
        mSplitController.registerSplit(
                mTransaction,
                primaryContainer,
                mActivity,
                secondaryContainer,
                placeholderRule);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_splitWithActivityBelowAsSecondary() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(activityBelow, mActivity);

        final TaskFragmentContainer container = mSplitController.newContainer(activityBelow,
                TASK_ID);
        container.addPendingAppearedActivity(mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(activityBelow, mActivity);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_splitWithActivityBelowAsPrimary() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(mActivity, activityBelow);

        // Disallow to split as primary.
        final TaskFragmentContainer container = mSplitController.newContainer(activityBelow,
                TASK_ID);
        container.addPendingAppearedActivity(mActivity);
        boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertFalse(result);
        assertEquals(container, mSplitController.getContainerWithActivity(mActivity));

        // Allow to split as primary.
        result = mSplitController.resolveActivityToContainer(mActivity, true /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(mActivity, activityBelow);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_splitWithCurrentPrimaryAsSecondary() {
        final Activity primaryActivity = createMockActivity();
        setupSplitRule(primaryActivity, mActivity);

        final Activity activityBelow = createMockActivity();
        addSplitTaskFragments(primaryActivity, activityBelow);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                primaryActivity);
        final TaskFragmentContainer secondaryContainer = mSplitController.getContainerWithActivity(
                activityBelow);
        secondaryContainer.addPendingAppearedActivity(mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);
        final TaskFragmentContainer container = mSplitController.getContainerWithActivity(
                mActivity);

        assertTrue(result);
        // TODO(b/231845476) we should always respect clearTop.
        // assertNotEquals(secondaryContainer, container);
        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_splitWithCurrentPrimaryAsPrimary() {
        final Activity primaryActivity = createMockActivity();
        setupSplitRule(mActivity, primaryActivity);

        final Activity activityBelow = createMockActivity();
        addSplitTaskFragments(primaryActivity, activityBelow);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                primaryActivity);
        primaryContainer.addPendingAppearedActivity(mActivity);
        boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertFalse(result);
        assertEquals(primaryContainer, mSplitController.getContainerWithActivity(mActivity));


        result = mSplitController.resolveActivityToContainer(mActivity, true /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(mActivity, primaryActivity);
    }

    @Test
    public void testResolveActivityToContainer_primaryActivityMinDimensionsNotSatisfied() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(mActivity, activityBelow);

        doReturn(createActivityInfoWithMinDimensions()).when(mActivity).getActivityInfo();

        final TaskFragmentContainer container = mSplitController.newContainer(activityBelow,
                TASK_ID);
        container.addPendingAppearedActivity(mActivity);

        // Allow to split as primary.
        boolean result = mSplitController.resolveActivityToContainer(mActivity,
                true /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(mActivity, activityBelow, true /* matchParentBounds */);
    }

    @Test
    public void testResolveActivityToContainer_secondaryActivityMinDimensionsNotSatisfied() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(activityBelow, mActivity);

        doReturn(createActivityInfoWithMinDimensions()).when(mActivity).getActivityInfo();

        final TaskFragmentContainer container = mSplitController.newContainer(activityBelow,
                TASK_ID);
        container.addPendingAppearedActivity(mActivity);

        boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(activityBelow, mActivity, true /* matchParentBounds */);
    }

    @Test
    public void testResolveActivityToContainer_minDimensions_shouldExpandSplitContainer() {
        final Activity primaryActivity = createMockActivity();
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(primaryActivity, secondaryActivity, false /* clearTop */);

        setupSplitRule(primaryActivity, mActivity, false /* clearTop */);
        doReturn(createActivityInfoWithMinDimensions()).when(mActivity).getActivityInfo();
        doReturn(secondaryActivity).when(mSplitController).findActivityBelow(eq(mActivity));

        clearInvocations(mSplitPresenter);
        boolean result = mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(primaryActivity, mActivity, true /* matchParentBounds */);
        assertEquals(mSplitController.getContainerWithActivity(secondaryActivity),
                mSplitController.getContainerWithActivity(mActivity));
        verify(mSplitPresenter, never()).createNewSplitContainer(any(), any(), any());
    }

    @Test
    public void testResolveActivityToContainer_inUnknownTaskFragment() {
        doReturn(new Binder()).when(mSplitController).getInitialTaskFragmentToken(mActivity);

        // No need to handle when the new launched activity is in an unknown TaskFragment.
        assertTrue(mSplitController.resolveActivityToContainer(mActivity,
                false /* isOnReparent */));
    }

    @Test
    public void testGetPlaceholderOptions() {
        doReturn(true).when(mActivity).isResumed();

        assertNull(mSplitController.getPlaceholderOptions(mActivity, false /* isOnCreated */));

        doReturn(false).when(mActivity).isResumed();

        assertNull(mSplitController.getPlaceholderOptions(mActivity, true /* isOnCreated */));

        // Launch placeholder without moving the Task to front if the Task is now in background (not
        // resumed or onCreated).
        final Bundle options = mSplitController.getPlaceholderOptions(mActivity,
                false /* isOnCreated */);

        assertNotNull(options);
        final ActivityOptions activityOptions = new ActivityOptions(options);
        assertTrue(activityOptions.getAvoidMoveToFront());
    }

    @Test
    public void testFinishTwoSplitThatShouldFinishTogether() {
        // Setup two split pairs that should finish each other when finishing one.
        final Activity secondaryActivity0 = createMockActivity();
        final Activity secondaryActivity1 = createMockActivity();
        final TaskFragmentContainer primaryContainer = createMockTaskFragmentContainer(mActivity);
        final TaskFragmentContainer secondaryContainer0 = createMockTaskFragmentContainer(
                secondaryActivity0);
        final TaskFragmentContainer secondaryContainer1 = createMockTaskFragmentContainer(
                secondaryActivity1);
        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);
        final SplitRule rule0 = createSplitRule(mActivity, secondaryActivity0, FINISH_ALWAYS,
                FINISH_ALWAYS, false /* clearTop */);
        final SplitRule rule1 = createSplitRule(mActivity, secondaryActivity1, FINISH_ALWAYS,
                FINISH_ALWAYS, false /* clearTop */);
        registerSplitPair(primaryContainer, secondaryContainer0, rule0);
        registerSplitPair(primaryContainer, secondaryContainer1, rule1);

        primaryContainer.finish(true /* shouldFinishDependent */, mSplitPresenter,
                mTransaction, mSplitController);

        // All containers and activities should be finished based on the FINISH_ALWAYS behavior.
        assertTrue(primaryContainer.isFinished());
        assertTrue(secondaryContainer0.isFinished());
        assertTrue(secondaryContainer1.isFinished());
        verify(mActivity).finish();
        verify(secondaryActivity0).finish();
        verify(secondaryActivity1).finish();
        assertTrue(taskContainer.mContainers.isEmpty());
        assertTrue(taskContainer.mSplitContainers.isEmpty());
    }

    /** Creates a mock activity in the organizer process. */
    private Activity createMockActivity() {
        final Activity activity = mock(Activity.class);
        doReturn(mActivityResources).when(activity).getResources();
        final IBinder activityToken = new Binder();
        doReturn(activityToken).when(activity).getActivityToken();
        doReturn(activity).when(mSplitController).getActivity(activityToken);
        doReturn(TASK_ID).when(activity).getTaskId();
        doReturn(new ActivityInfo()).when(activity).getActivityInfo();
        return activity;
    }

    /** Creates a mock TaskFragment that has been registered and appeared in the organizer. */
    private TaskFragmentContainer createMockTaskFragmentContainer(@NonNull Activity activity) {
        final TaskFragmentContainer container = mSplitController.newContainer(activity, TASK_ID);
        setupTaskFragmentInfo(container, activity);
        return container;
    }

    /** Setups the given TaskFragment as it has appeared in the server. */
    private void setupTaskFragmentInfo(@NonNull TaskFragmentContainer container,
            @NonNull Activity activity) {
        final TaskFragmentInfo info = createMockTaskFragmentInfo(container, activity);
        container.setInfo(info);
        mSplitPresenter.mFragmentInfos.put(container.getTaskFragmentToken(), info);
    }

    /** Setups a rule to always expand the given intent. */
    private void setupExpandRule(@NonNull Intent expandIntent) {
        final ActivityRule expandRule = new ActivityRule.Builder(r -> false, expandIntent::equals)
                .setShouldAlwaysExpand(true)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(expandRule));
    }

    /** Setups a rule to always expand the given activity. */
    private void setupExpandRule(@NonNull Activity expandActivity) {
        final ActivityRule expandRule = new ActivityRule.Builder(expandActivity::equals, i -> false)
                .setShouldAlwaysExpand(true)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(expandRule));
    }

    /** Setups a rule to launch placeholder for the given activity. */
    private void setupPlaceholderRule(@NonNull Activity primaryActivity) {
        final SplitRule placeholderRule = new SplitPlaceholderRule.Builder(PLACEHOLDER_INTENT,
                primaryActivity::equals, i -> false, w -> true)
                .setSplitRatio(SPLIT_RATIO)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(placeholderRule));
    }

    /** Setups a rule to always split the given activities. */
    private void setupSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent) {
        setupSplitRule(primaryActivity, secondaryIntent, true /* clearTop */);
    }

    /** Setups a rule to always split the given activities. */
    private void setupSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent, boolean clearTop) {
        final SplitRule splitRule = createSplitRule(primaryActivity, secondaryIntent, clearTop);
        mSplitController.setEmbeddingRules(Collections.singleton(splitRule));
    }

    /** Setups a rule to always split the given activities. */
    private void setupSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        setupSplitRule(primaryActivity, secondaryActivity, true /* clearTop */);
    }

    /** Setups a rule to always split the given activities. */
    private void setupSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, boolean clearTop) {
        final SplitRule splitRule = createSplitRule(primaryActivity, secondaryActivity, clearTop);
        mSplitController.setEmbeddingRules(Collections.singleton(splitRule));
    }

    /** Adds a pair of TaskFragments as split for the given activities. */
    private void addSplitTaskFragments(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        addSplitTaskFragments(primaryActivity, secondaryActivity, true /* clearTop */);
    }

    /** Adds a pair of TaskFragments as split for the given activities. */
    private void addSplitTaskFragments(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, boolean clearTop) {
        registerSplitPair(createMockTaskFragmentContainer(primaryActivity),
                createMockTaskFragmentContainer(secondaryActivity),
                createSplitRule(primaryActivity, secondaryActivity, clearTop));
    }

    /** Registers the two given TaskFragments as split pair. */
    private void registerSplitPair(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer, @NonNull SplitRule rule) {
        mSplitController.registerSplit(
                mock(WindowContainerTransaction.class),
                primaryContainer,
                primaryContainer.getTopNonFinishingActivity(),
                secondaryContainer,
                rule);

        // We need to set those in case we are not respecting clear top.
        // TODO(b/231845476) we should always respect clearTop.
        final int windowingMode = mSplitController.getTaskContainer(TASK_ID)
                .getWindowingModeForSplitTaskFragment(TASK_BOUNDS);
        primaryContainer.setLastRequestedWindowingMode(windowingMode);
        secondaryContainer.setLastRequestedWindowingMode(windowingMode);
        primaryContainer.setLastRequestedBounds(getSplitBounds(true /* isPrimary */));
        secondaryContainer.setLastRequestedBounds(getSplitBounds(false /* isPrimary */));
    }

    /** Asserts that the two given activities are in split. */
    private void assertSplitPair(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        assertSplitPair(primaryActivity, secondaryActivity, false /* matchParentBounds */);
    }

    /** Asserts that the two given activities are in split. */
    private void assertSplitPair(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, boolean matchParentBounds) {
        assertSplitPair(mSplitController.getContainerWithActivity(primaryActivity),
                mSplitController.getContainerWithActivity(secondaryActivity), matchParentBounds);
    }

    private void assertSplitPair(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer) {
        assertSplitPair(primaryContainer, secondaryContainer, false /* matchParentBounds*/);
    }

    /** Asserts that the two given TaskFragments are in split. */
    private void assertSplitPair(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer, boolean matchParentBounds) {
        assertNotNull(primaryContainer);
        assertNotNull(secondaryContainer);
        assertNotNull(mSplitController.getActiveSplitForContainers(primaryContainer,
                secondaryContainer));
        if (primaryContainer.mInfo != null) {
            final Rect primaryBounds = matchParentBounds ? new Rect()
                    : getSplitBounds(true /* isPrimary */);
            final int windowingMode = matchParentBounds ? WINDOWING_MODE_UNDEFINED
                    : WINDOWING_MODE_MULTI_WINDOW;
            assertTrue(primaryContainer.areLastRequestedBoundsEqual(primaryBounds));
            assertTrue(primaryContainer.isLastRequestedWindowingModeEqual(windowingMode));
        }
        if (secondaryContainer.mInfo != null) {
            final Rect secondaryBounds = matchParentBounds ? new Rect()
                    : getSplitBounds(false /* isPrimary */);
            final int windowingMode = matchParentBounds ? WINDOWING_MODE_UNDEFINED
                    : WINDOWING_MODE_MULTI_WINDOW;
            assertTrue(secondaryContainer.areLastRequestedBoundsEqual(secondaryBounds));
            assertTrue(secondaryContainer.isLastRequestedWindowingModeEqual(windowingMode));
        }
    }
}
