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

import static android.app.ActivityManager.START_CANCELED;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.TaskFragmentOperation.OP_TYPE_CREATE_TASK_FRAGMENT;
import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;

import static androidx.window.extensions.embedding.EmbeddingTestUtils.DEFAULT_FINISH_PRIMARY_WITH_SECONDARY;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.DEFAULT_FINISH_SECONDARY_WITH_PRIMARY;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.SPLIT_ATTRIBUTES;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_BOUNDS;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_ID;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TEST_TAG;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createActivityBuilder;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createActivityInfoWithMinDimensions;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createMockTaskFragmentInfo;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitPairRuleBuilder;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitPlaceholderRuleBuilder;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitRule;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createTestTaskContainer;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createTfContainer;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.getSplitBounds;
import static androidx.window.extensions.embedding.SplitRule.FINISH_ALWAYS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
import static org.mockito.Mockito.times;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.servertransaction.ClientTransactionListenerController;
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
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArraySet;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.window.ActivityWindowInfo;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentParentInfo;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerTransaction;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.extensions.layout.WindowLayoutComponentImpl;
import androidx.window.extensions.layout.WindowLayoutInfo;

import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Test class for {@link SplitController}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:SplitControllerTest
 */
// Suppress GuardedBy warning on unit tests
@SuppressWarnings("GuardedBy")
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitControllerTest {
    private static final Intent PLACEHOLDER_INTENT = new Intent().setComponent(
            new ComponentName("test", "placeholder"));

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Rule
    public final SetFlagsRule mSetFlagRule = new SetFlagsRule();

    private Activity mActivity;
    @Mock
    private Resources mActivityResources;
    @Mock
    private TaskFragmentInfo mInfo;
    @Mock
    private WindowContainerTransaction mTransaction;
    @Mock
    private Handler mHandler;
    @Mock
    private WindowLayoutComponentImpl mWindowLayoutComponent;
    @Mock
    private ActivityWindowInfo mActivityWindowInfo;
    @Mock
    private BiConsumer<IBinder, ActivityWindowInfo> mActivityWindowInfoListener;
    @Mock
    private androidx.window.extensions.core.util.function.Consumer<EmbeddedActivityWindowInfo>
            mEmbeddedActivityWindowInfoCallback;

    private SplitController mSplitController;
    private SplitPresenter mSplitPresenter;
    private Consumer<List<SplitInfo>> mEmbeddingCallback;
    private List<SplitInfo> mSplitInfos;
    private TransactionManager mTransactionManager;

    @Before
    public void setUp() {
        doReturn(new WindowLayoutInfo(new ArrayList<>())).when(mWindowLayoutComponent)
                .getCurrentWindowLayoutInfo(anyInt(), any());
        DeviceStateManagerFoldingFeatureProducer producer =
                mock(DeviceStateManagerFoldingFeatureProducer.class);
        mSplitController = new SplitController(mWindowLayoutComponent, producer);
        mSplitPresenter = mSplitController.mPresenter;
        mSplitInfos = new ArrayList<>();
        mEmbeddingCallback = splitInfos -> {
            mSplitInfos.clear();
            mSplitInfos.addAll(splitInfos);
        };
        mSplitController.setSplitInfoCallback(mEmbeddingCallback);
        mTransactionManager = mSplitController.mTransactionManager;
        spyOn(mSplitController);
        spyOn(mSplitPresenter);
        spyOn(mEmbeddingCallback);
        spyOn(mTransactionManager);
        doNothing().when(mSplitPresenter).applyTransaction(any(), anyInt(), anyBoolean());
        final Configuration activityConfig = new Configuration();
        activityConfig.windowConfiguration.setBounds(TASK_BOUNDS);
        activityConfig.windowConfiguration.setMaxBounds(TASK_BOUNDS);
        doReturn(activityConfig).when(mActivityResources).getConfiguration();
        doReturn(mHandler).when(mSplitController).getHandler();
        mActivity = createMockActivity();
    }

    @Test
    public void testOnTaskFragmentVanished() {
        final TaskFragmentContainer tf = createTfContainer(mSplitController, mActivity);
        doReturn(tf.getTaskFragmentToken()).when(mInfo).getFragmentToken();
        doReturn(createTestTaskContainer()).when(mSplitController).getTaskContainer(TASK_ID);

        // The TaskFragment has been removed in the server, we only need to cleanup the reference.
        mSplitController.onTaskFragmentVanished(mTransaction, mInfo, TASK_ID);

        verify(mSplitPresenter, never()).deleteTaskFragment(any(), any());
        verify(mSplitController).removeContainer(tf);
        verify(mSplitController).updateDivider(any(), any(), anyBoolean());
        verify(mTransaction, never()).finishActivity(any());
    }

    @Test
    public void testOnTaskFragmentAppearEmptyTimeout() {
        // Setup to make sure a transaction record is started.
        mTransactionManager.startNewTransaction();
        final TaskFragmentContainer tf = createTfContainer(mSplitController, mActivity);
        doCallRealMethod().when(mSplitController).onTaskFragmentAppearEmptyTimeout(any(), any());
        mSplitController.onTaskFragmentAppearEmptyTimeout(mTransaction, tf);

        verify(mSplitPresenter).cleanupContainer(mTransaction, tf,
                false /* shouldFinishDependent */);
    }

    @Test
    public void testOnActivityDestroyed() {
        doReturn(new Binder()).when(mActivity).getActivityToken();
        final TaskFragmentContainer tf = createTfContainer(mSplitController, mActivity);

        assertTrue(tf.hasActivity(mActivity.getActivityToken()));

        // When the activity is not finishing, do not clear the record.
        doReturn(false).when(mActivity).isFinishing();
        mSplitController.onActivityDestroyed(mTransaction, mActivity);

        assertTrue(tf.hasActivity(mActivity.getActivityToken()));

        // Clear the record when the activity is finishing and destroyed.
        doReturn(true).when(mActivity).isFinishing();
        mSplitController.onActivityDestroyed(mTransaction, mActivity);

        assertFalse(tf.hasActivity(mActivity.getActivityToken()));
    }

    @Test
    public void testNewContainer() {
        // Must pass in a valid activity.
        assertThrows(IllegalArgumentException.class, () ->
                createTfContainer(mSplitController, null /* activity */));

        final TaskFragmentContainer tf = createTfContainer(mSplitController, mActivity);
        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);

        assertNotNull(tf);
        assertNotNull(taskContainer);
        assertEquals(TASK_BOUNDS, taskContainer.getTaskProperties().getConfiguration()
                .windowConfiguration.getBounds());
    }

    @Test
    public void testUpdateContainer() {
        // Make SplitController#launchPlaceholderIfNecessary(TaskFragmentContainer) return true
        // and verify if shouldContainerBeExpanded() not called.
        final TaskFragmentContainer tf = createTfContainer(mSplitController, mActivity);
        spyOn(tf);
        doReturn(mActivity).when(tf).getTopNonFinishingActivity();
        doReturn(true).when(tf).isEmpty();
        doReturn(true).when(mSplitController).launchPlaceholderIfNecessary(mTransaction,
                mActivity, false /* isOnCreated */);
        doNothing().when(mSplitPresenter).updateSplitContainer(any(), any());

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).shouldContainerBeExpanded(any());

        // Verify if tf should be expanded, getTopActiveContainer() won't be called
        doReturn(null).when(tf).getTopNonFinishingActivity();
        doReturn(true).when(mSplitController).shouldContainerBeExpanded(tf);

        mSplitController.updateContainer(mTransaction, tf);

        TaskContainer taskContainer = tf.getTaskContainer();
        spyOn(taskContainer);
        verify(taskContainer, never()).getTopNonFinishingTaskFragmentContainer();

        // Verify if tf is not in split, dismissPlaceholderIfNecessary won't be called.
        doReturn(false).when(mSplitController).shouldContainerBeExpanded(tf);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any(), any());

        // Verify if tf is not in the top splitContainer,
        final SplitContainer splitContainer = mock(SplitContainer.class);
        doReturn(tf).when(splitContainer).getPrimaryContainer();
        doReturn(tf).when(splitContainer).getSecondaryContainer();
        doReturn(createTestTaskContainer()).when(splitContainer).getTaskContainer();
        final SplitRule splitRule = createSplitRule(mActivity, mActivity);
        doReturn(splitRule).when(splitContainer).getSplitRule();
        doReturn(splitRule.getDefaultSplitAttributes())
                .when(splitContainer).getDefaultSplitAttributes();
        taskContainer = mSplitController.getTaskContainer(TASK_ID);
        taskContainer.addSplitContainer(splitContainer);
        // Add a mock SplitContainer on top of splitContainer
        final SplitContainer splitContainer2 = mock(SplitContainer.class);
        taskContainer.addSplitContainer(splitContainer2);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any(), any());

        // Verify if one or both containers in the top SplitContainer are finished,
        // dismissPlaceholder() won't be called.
        final ArrayList<SplitContainer> splitContainersToRemove = new ArrayList<>();
        splitContainersToRemove.add(splitContainer2);
        taskContainer.removeSplitContainers(splitContainersToRemove);
        doReturn(true).when(tf).isFinished();

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any(), any());

        // Verify if placeholder should be dismissed, updateSplitContainer() won't be called.
        doReturn(false).when(tf).isFinished();
        doReturn(true).when(mSplitController)
                .dismissPlaceholderIfNecessary(mTransaction, splitContainer);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitPresenter, never()).updateSplitContainer(any(), any());

        // Verify if the top active split is updated if both of its containers are not finished.
        doReturn(false).when(mSplitController)
                .dismissPlaceholderIfNecessary(mTransaction, splitContainer);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitPresenter).updateSplitContainer(splitContainer, mTransaction);
    }

    @Test
    public void testUpdateContainer_skipIfTaskIsInvisible() {
        final Activity r0 = createMockActivity();
        final Activity r1 = createMockActivity();
        addSplitTaskFragments(r0, r1);
        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);
        final TaskFragmentContainer taskFragmentContainer =
                taskContainer.getTaskFragmentContainers().get(0);
        spyOn(taskContainer);

        // No update when the Task is invisible.
        clearInvocations(mSplitPresenter);
        doReturn(false).when(taskContainer).isVisible();
        mSplitController.updateContainer(mTransaction, taskFragmentContainer);

        verify(mSplitPresenter, never()).updateSplitContainer(any(), any());

        // Update the split when the Task is visible.
        doReturn(true).when(taskContainer).isVisible();
        mSplitController.updateContainer(mTransaction, taskFragmentContainer);

        verify(mSplitPresenter).updateSplitContainer(taskContainer.getSplitContainers().get(0),
                mTransaction);
    }

    @Test
    public void testOnStartActivityResultError() {
        final Intent intent = new Intent();
        final TaskContainer taskContainer = createTestTaskContainer();
        final int taskId = taskContainer.getTaskId();
        mSplitController.addTaskContainer(taskId, taskContainer);
        final TaskFragmentContainer container = new TaskFragmentContainer.Builder(mSplitController,
                taskId, null /* activityInTask */)
                .setPendingAppearedIntent(intent)
                .build();
        final SplitController.ActivityStartMonitor monitor =
                mSplitController.getActivityStartMonitor();

        container.setPendingAppearedIntent(intent);
        final Bundle bundle = new Bundle();
        bundle.putBinder(ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN,
                container.getTaskFragmentToken());
        monitor.mCurrentIntent = intent;
        doReturn(container).when(mSplitController).getContainer(any(IBinder.class));

        monitor.onStartActivityResult(START_CANCELED, bundle);
        assertNull(container.getPendingAppearedIntent());
    }

    @Test
    public void testOnActivityCreated() {
        mSplitController.onActivityCreated(mTransaction, mActivity);

        // Disallow to split as primary because we want the new launch to be always on top.
        verify(mSplitController).resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);
    }

    @Test
    public void testOnActivityReparentedToTask_sameProcess() {
        mSplitController.onActivityReparentedToTask(mTransaction, TASK_ID, new Intent(),
                mActivity.getActivityToken(), null /* fillTaskActivityToken */,
                null /* lastOverlayToken */);

        // Treated as on activity created, but allow to split as primary.
        verify(mSplitController).resolveActivityToContainer(mTransaction,
                mActivity, true /* isOnReparent */);
        // Try to place the activity to the top TaskFragment when there is no matched rule.
        verify(mSplitController).placeActivityInTopContainer(mTransaction, mActivity);
    }

    @Test
    public void testOnActivityReparentedToTask_diffProcess() {
        // Create an empty TaskFragment to initialize for the Task.
        new TaskFragmentContainer.Builder(mSplitController, TASK_ID, mActivity)
                .setPendingAppearedIntent(new Intent()).build();
        final IBinder activityToken = new Binder();
        final Intent intent = new Intent();

        mSplitController.onActivityReparentedToTask(mTransaction, TASK_ID, intent, activityToken,
                null /* fillTaskActivityToken */, null /* lastOverlayToken */);

        // Treated as starting new intent
        verify(mSplitController, never()).resolveActivityToContainer(any(), any(), anyBoolean());
        verify(mSplitController).resolveStartActivityIntent(any(), eq(TASK_ID), eq(intent),
                isNull());
    }

    @Test
    public void testResolveStartActivityIntent_withoutLaunchingActivity() {
        final Intent intent = new Intent();
        final ActivityRule expandRule = createActivityBuilder(r -> false, i -> i == intent)
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
    public void testResolveStartActivityIntent_skipIfIsolatedNavEnabled() {
        final TaskFragmentContainer container = createMockTaskFragmentContainer(mActivity);
        container.setIsolatedNavigationEnabled(true);

        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);
        assertNull(mSplitController.resolveStartActivityIntent(mTransaction, TASK_ID, intent,
                mActivity));
    }

    @Test
    public void testPlaceActivityInTopContainer() {
        mSplitController.placeActivityInTopContainer(mTransaction, mActivity);

        verify(mTransaction, never()).reparentActivityToTaskFragment(any(), any());

        // Place in the top container if there is no other rule matched.
        final TaskFragmentContainer topContainer =
                new TaskFragmentContainer.Builder(mSplitController, TASK_ID, mActivity)
                        .setPendingAppearedIntent(new Intent()).build();
        mSplitController.placeActivityInTopContainer(mTransaction, mActivity);

        verify(mTransaction).reparentActivityToTaskFragment(topContainer.getTaskFragmentToken(),
                mActivity.getActivityToken());

        // Not reparent if activity is in a TaskFragment.
        clearInvocations(mTransaction);
        createTfContainer(mSplitController, mActivity);
        mSplitController.placeActivityInTopContainer(mTransaction, mActivity);

        verify(mTransaction, never()).reparentActivityToTaskFragment(any(), any());
    }

    @Test
    public void testResolveActivityToContainer_noRuleMatched() {
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertFalse(result);
        verify(mSplitController, never()).addTaskContainer(anyInt(), any());
    }

    @Test
    public void testResolveActivityToContainer_expandRule_notInTaskFragment() {
        setupExpandRule(mActivity);

        // When the activity is not in any TaskFragment, create a new expanded TaskFragment for it.
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);
        final TaskFragmentContainer container = mSplitController.getContainerWithActivity(
                mActivity);

        assertTrue(result);
        assertNotNull(container);
        verify(mSplitPresenter).expandActivity(mTransaction, container.getTaskFragmentToken(),
                mActivity);
    }

    @Test
    public void testResolveActivityToContainer_expandRule_inSingleTaskFragment() {
        setupExpandRule(mActivity);

        // When the activity is not in any TaskFragment, create a new expanded TaskFragment for it.
        final TaskFragmentContainer container = createTfContainer(mSplitController, mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).expandTaskFragment(mTransaction, container);
    }

    @Test
    public void testResolveActivityToContainer_expandRule_inSplitTaskFragment() {
        setupExpandRule(mActivity);

        // When the activity is not in any TaskFragment, create a new expanded TaskFragment for it.
        final Activity activity = createMockActivity();
        addSplitTaskFragments(activity, mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);
        final TaskFragmentContainer container = mSplitController.getContainerWithActivity(
                mActivity);

        assertTrue(result);
        assertNotNull(container);
        verify(mSplitPresenter).expandActivity(mTransaction, container.getTaskFragmentToken(),
                mActivity);
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_notInTaskFragment() {
        // Setup to make sure a transaction record is started.
        mTransactionManager.startNewTransaction();
        setupPlaceholderRule(mActivity);
        final SplitPlaceholderRule placeholderRule =
                (SplitPlaceholderRule) mSplitController.getSplitRules().get(0);

        // Launch placeholder if the activity is not in any TaskFragment.
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).startActivityToSide(mTransaction, mActivity, PLACEHOLDER_INTENT,
                mSplitController.getPlaceholderOptions(mActivity, true /* isOnCreated */),
                placeholderRule, SPLIT_ATTRIBUTES, true /* isPlaceholder */);
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inOccludedTaskFragment() {
        setupPlaceholderRule(mActivity);

        // Don't launch placeholder if the activity is not in the topmost active TaskFragment.
        final Activity activity = createMockActivity();
        createTfContainer(mSplitController, mActivity);
        createTfContainer(mSplitController, activity);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter, never()).startActivityToSide(any(), any(), any(), any(), any(),
                any(), anyBoolean());
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inTopMostTaskFragment() {
        // Setup to make sure a transaction record is started.
        mTransactionManager.startNewTransaction();
        setupPlaceholderRule(mActivity);
        final SplitPlaceholderRule placeholderRule =
                (SplitPlaceholderRule) mSplitController.getSplitRules().get(0);

        // Launch placeholder if the activity is in the topmost expanded TaskFragment.
        createTfContainer(mSplitController, mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).startActivityToSide(mTransaction, mActivity, PLACEHOLDER_INTENT,
                mSplitController.getPlaceholderOptions(mActivity, true /* isOnCreated */),
                placeholderRule, SPLIT_ATTRIBUTES, true /* isPlaceholder */);
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inPrimarySplit() {
        setupPlaceholderRule(mActivity);

        // Don't launch placeholder if the activity is in primary split.
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(mActivity, secondaryActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter, never()).startActivityToSide(any(), any(), any(), any(), any(),
                any(), anyBoolean());
    }

    @Test
    public void testResolveActivityToContainer_placeholderRule_inSecondarySplit() {
        // Setup to make sure a transaction record is started.
        mTransactionManager.startNewTransaction();
        setupPlaceholderRule(mActivity);
        final SplitPlaceholderRule placeholderRule =
                (SplitPlaceholderRule) mSplitController.getSplitRules().get(0);

        // Launch placeholder if the activity is in secondary split.
        final Activity primaryActivity = createMockActivity();
        addSplitTaskFragments(primaryActivity, mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitPresenter).startActivityToSide(mTransaction, mActivity, PLACEHOLDER_INTENT,
                mSplitController.getPlaceholderOptions(mActivity, true /* isOnCreated */),
                placeholderRule, SPLIT_ATTRIBUTES, true /* isPlaceholder */);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_inPrimarySplitWithRuleMatched() {
        final Intent secondaryIntent = new Intent();
        setupSplitRule(mActivity, secondaryIntent);
        final SplitPairRule splitRule = (SplitPairRule) mSplitController.getSplitRules().get(0);

        // Activity is already in primary split, no need to create new split.
        final TaskFragmentContainer primaryContainer =
                createTfContainer(mSplitController, mActivity);
        final TaskFragmentContainer secondaryContainer =
                new TaskFragmentContainer.Builder(mSplitController, TASK_ID, mActivity)
                        .setPendingAppearedIntent(secondaryIntent).build();
        mSplitController.registerSplit(
                mTransaction,
                primaryContainer,
                mActivity,
                secondaryContainer,
                splitRule,
                SPLIT_ATTRIBUTES);
        clearInvocations(mSplitController);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitController, never()).registerSplit(any(), any(), any(), any(), any(), any());
    }

    @Test
    public void testResolveActivityToContainer_splitRule_inPrimarySplitWithNoRuleMatched() {
        final Intent secondaryIntent = new Intent();
        setupSplitRule(mActivity, secondaryIntent);
        final SplitPairRule splitRule = (SplitPairRule) mSplitController.getSplitRules().get(0);

        // The new launched activity is in primary split, but there is no rule for it to split with
        // the secondary, so return false.
        final TaskFragmentContainer primaryContainer =
                createTfContainer(mSplitController, mActivity);
        final TaskFragmentContainer secondaryContainer =
                new TaskFragmentContainer.Builder(mSplitController, TASK_ID, mActivity)
                        .setPendingAppearedIntent(secondaryIntent).build();
        mSplitController.registerSplit(
                mTransaction,
                primaryContainer,
                mActivity,
                secondaryContainer,
                splitRule,
                SPLIT_ATTRIBUTES);
        final Activity launchedActivity = createMockActivity();
        primaryContainer.addPendingAppearedActivity(launchedActivity);

        assertTrue(mSplitController.resolveActivityToContainer(mTransaction, launchedActivity,
                false /* isOnReparent */));
    }

    @Test
    public void testResolveActivityToContainer_splitRule_inSecondarySplitWithRuleMatched() {
        final Activity primaryActivity = createMockActivity();
        setupSplitRule(primaryActivity, mActivity);

        // Activity is already in secondary split, no need to create new split.
        addSplitTaskFragments(primaryActivity, mActivity);
        clearInvocations(mSplitController);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        verify(mSplitController, never()).registerSplit(any(), any(), any(), any(), any(), any());
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
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
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
        final TaskFragmentContainer primaryContainer =
                createTfContainer(mSplitController, primaryActivity);
        final TaskFragmentContainer secondaryContainer =
                createTfContainer(mSplitController, mActivity);
        mSplitController.registerSplit(
                mTransaction,
                primaryContainer,
                mActivity,
                secondaryContainer,
                placeholderRule,
                SPLIT_ATTRIBUTES);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_splitWithActivityBelowAsSecondary() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(activityBelow, mActivity);

        final TaskFragmentContainer container = createTfContainer(mSplitController, activityBelow);
        container.addPendingAppearedActivity(mActivity);
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(activityBelow, mActivity);
    }

    @Test
    public void testResolveActivityToContainer_splitRule_splitWithActivityBelowAsPrimary() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(mActivity, activityBelow);

        // Disallow to split as primary.
        final TaskFragmentContainer container = createTfContainer(mSplitController, activityBelow);
        container.addPendingAppearedActivity(mActivity);
        boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertFalse(result);
        assertEquals(container, mSplitController.getContainerWithActivity(mActivity));

        // Allow to split as primary.
        result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                true /* isOnReparent */);

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
        final boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
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
        boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        assertEquals(primaryContainer, mSplitController.getContainerWithActivity(mActivity));


        result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                true /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(mActivity, primaryActivity);
    }

    @Test
    public void testResolveActivityToContainer_primaryActivityMinDimensionsNotSatisfied() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(mActivity, activityBelow);

        doReturn(createActivityInfoWithMinDimensions()).when(mActivity).getActivityInfo();

        final TaskFragmentContainer container = createTfContainer(mSplitController, activityBelow);
        container.addPendingAppearedActivity(mActivity);

        // Allow to split as primary.
        boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                true /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(mActivity, activityBelow, true /* matchParentBounds */);
    }

    @Test
    public void testResolveActivityToContainer_secondaryActivityMinDimensionsNotSatisfied() {
        final Activity activityBelow = createMockActivity();
        setupSplitRule(activityBelow, mActivity);

        doReturn(createActivityInfoWithMinDimensions()).when(mActivity).getActivityInfo();

        final TaskFragmentContainer container = createTfContainer(mSplitController, activityBelow);
        container.addPendingAppearedActivity(mActivity);

        boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
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
        boolean result = mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);

        assertTrue(result);
        assertSplitPair(primaryActivity, mActivity, true /* matchParentBounds */);
        assertTrue(mSplitController.getContainerWithActivity(mActivity)
                .areLastRequestedBoundsEqual(new Rect()));
    }

    @Test
    public void testFindActivityBelow() {
        // Create a container with two activities
        final TaskFragmentContainer container = createMockTaskFragmentContainer(mActivity);
        final Activity pendingAppearedActivity = createMockActivity();
        container.addPendingAppearedActivity(pendingAppearedActivity);

        // Ensure the activity below matches
        assertEquals(mActivity,
                mSplitController.findActivityBelow(pendingAppearedActivity));

        // Ensure that the activity look up won't search for the in-process activities and should
        // IPC to WM core to get the activity below. It should be `null` for this mock test.
        spyOn(container);
        doReturn(true).when(container).hasCrossProcessActivities();
        assertNotEquals(mActivity,
                mSplitController.findActivityBelow(pendingAppearedActivity));
    }

    @Test
    public void testResolveActivityToContainer_inUnknownTaskFragment() {
        doReturn(new Binder()).when(mSplitController)
                .getTaskFragmentTokenFromActivityClientRecord(mActivity);

        // No need to handle when the new launched activity is in an unknown TaskFragment.
        assertTrue(mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */));
    }

    @Test
    public void testResolveActivityToContainer_skipIfNonTopOrPinned() {
        final TaskFragmentContainer container = createMockTaskFragmentContainer(mActivity);
        final Activity pinnedActivity = createMockActivity();
        final TaskFragmentContainer topContainer =
                createTfContainer(mSplitController, pinnedActivity);
        final TaskContainer taskContainer = container.getTaskContainer();
        spyOn(taskContainer);
        doReturn(container).when(taskContainer).getTopNonFinishingTaskFragmentContainer(false);
        doReturn(true).when(taskContainer).isTaskFragmentContainerPinned(topContainer);

        // No need to handle when the new launched activity is in a pinned TaskFragment.
        assertTrue(mSplitController.resolveActivityToContainer(mTransaction, pinnedActivity,
                false /* isOnReparent */));
        verify(mSplitController, never()).shouldExpand(any(), any());

        // Should proceed to resolve if the new launched activity is in the next top TaskFragment
        // (e.g. the top-most TaskFragment is pinned)
        mSplitController.resolveActivityToContainer(mTransaction, mActivity,
                false /* isOnReparent */);
        verify(mSplitController).shouldExpand(any(), any());
    }

    @Test
    public void testGetPlaceholderOptions() {
        // Setup to make sure a transaction record is started.
        mTransactionManager.startNewTransaction();
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
        verify(mTransaction).finishActivity(mActivity.getActivityToken());
        verify(mTransaction).finishActivity(secondaryActivity0.getActivityToken());
        verify(mTransaction).finishActivity(secondaryActivity1.getActivityToken());
        assertTrue(taskContainer.getTaskFragmentContainers().isEmpty());
        assertTrue(taskContainer.getSplitContainers().isEmpty());
    }

    @Test
    public void testOnTransactionReady_taskFragmentAppeared() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        transaction.addChange(new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_APPEARED)
                .setTaskId(TASK_ID)
                .setTaskFragmentToken(new Binder())
                .setTaskFragmentInfo(info));
        mSplitController.onTransactionReady(transaction);

        verify(mSplitController).onTaskFragmentAppeared(any(), eq(info));
        verify(mSplitPresenter).onTransactionHandled(eq(transaction.getTransactionToken()), any(),
                anyInt(), anyBoolean());
    }

    @Test
    public void testOnTransactionReady_taskFragmentInfoChanged() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        transaction.addChange(new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_INFO_CHANGED)
                .setTaskId(TASK_ID)
                .setTaskFragmentToken(new Binder())
                .setTaskFragmentInfo(info));
        mSplitController.onTransactionReady(transaction);

        verify(mSplitController).onTaskFragmentInfoChanged(any(), eq(info));
        verify(mSplitPresenter).onTransactionHandled(eq(transaction.getTransactionToken()), any(),
                anyInt(), anyBoolean());
    }

    @Test
    public void testOnTransactionReady_taskFragmentVanished() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        transaction.addChange(new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_VANISHED)
                .setTaskId(TASK_ID)
                .setTaskFragmentToken(new Binder())
                .setTaskFragmentInfo(info));
        mSplitController.onTransactionReady(transaction);

        verify(mSplitController).onTaskFragmentVanished(any(), eq(info), anyInt());
        verify(mSplitPresenter).onTransactionHandled(eq(transaction.getTransactionToken()), any(),
                anyInt(), anyBoolean());
    }

    @Test
    public void testOnTransactionReady_taskFragmentParentInfoChanged() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        final TaskFragmentParentInfo parentInfo = new TaskFragmentParentInfo(Configuration.EMPTY,
                DEFAULT_DISPLAY, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */);
        transaction.addChange(new TaskFragmentTransaction.Change(
                TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED)
                .setTaskId(TASK_ID)
                .setTaskFragmentParentInfo(parentInfo));
        mSplitController.onTransactionReady(transaction);

        verify(mSplitController).onTaskFragmentParentInfoChanged(any(), eq(TASK_ID),
                eq(parentInfo));
        verify(mSplitPresenter).onTransactionHandled(eq(transaction.getTransactionToken()), any(),
                anyInt(), anyBoolean());
    }

    @Test
    public void testOnTransactionReady_taskFragmentParentError() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        final IBinder errorToken = new Binder();
        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        final int opType = OP_TYPE_CREATE_TASK_FRAGMENT;
        final Exception exception = new SecurityException("test");
        final Bundle errorBundle = TaskFragmentOrganizer.putErrorInfoInBundle(exception, info,
                opType);
        transaction.addChange(new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_ERROR)
                .setErrorCallbackToken(errorToken)
                .setErrorBundle(errorBundle));
        mSplitController.onTransactionReady(transaction);

        verify(mSplitController).onTaskFragmentError(any(), eq(errorToken), eq(info), eq(opType),
                eq(exception));
        verify(mSplitPresenter).onTransactionHandled(eq(transaction.getTransactionToken()), any(),
                anyInt(), anyBoolean());
    }

    @Test
    public void testOnTransactionReady_activityReparentedToTask() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        final Intent intent = mock(Intent.class);
        final IBinder activityToken = new Binder();
        transaction.addChange(new TaskFragmentTransaction.Change(TYPE_ACTIVITY_REPARENTED_TO_TASK)
                .setTaskId(TASK_ID)
                .setActivityIntent(intent)
                .setActivityToken(activityToken));
        mSplitController.onTransactionReady(transaction);

        verify(mSplitController).onActivityReparentedToTask(any(), eq(TASK_ID), eq(intent),
                eq(activityToken), any(), any());
        verify(mSplitPresenter).onTransactionHandled(eq(transaction.getTransactionToken()), any(),
                anyInt(), anyBoolean());
    }

    @Test
    public void testHasSamePresentation() {
        SplitPairRule splitRule1 = createSplitPairRuleBuilder(
                activityPair -> true,
                activityIntentPair -> true,
                windowMetrics -> true)
                .setFinishSecondaryWithPrimary(DEFAULT_FINISH_SECONDARY_WITH_PRIMARY)
                .setFinishPrimaryWithSecondary(DEFAULT_FINISH_PRIMARY_WITH_SECONDARY)
                .setDefaultSplitAttributes(SPLIT_ATTRIBUTES)
                .build();
        SplitPairRule splitRule2 = createSplitPairRuleBuilder(
                activityPair -> true,
                activityIntentPair -> true,
                windowMetrics -> true)
                .setFinishSecondaryWithPrimary(DEFAULT_FINISH_SECONDARY_WITH_PRIMARY)
                .setFinishPrimaryWithSecondary(DEFAULT_FINISH_PRIMARY_WITH_SECONDARY)
                .setDefaultSplitAttributes(SPLIT_ATTRIBUTES)
                .build();

        assertTrue("Rules must have same presentation if tags are null and has same properties.",
                SplitController.areRulesSamePresentation(splitRule1, splitRule2,
                        new WindowMetrics(TASK_BOUNDS, WindowInsets.CONSUMED)));

        splitRule2 = createSplitPairRuleBuilder(
                activityPair -> true,
                activityIntentPair -> true,
                windowMetrics -> true)
                .setFinishSecondaryWithPrimary(DEFAULT_FINISH_SECONDARY_WITH_PRIMARY)
                .setFinishPrimaryWithSecondary(DEFAULT_FINISH_PRIMARY_WITH_SECONDARY)
                .setDefaultSplitAttributes(SPLIT_ATTRIBUTES)
                .setTag(TEST_TAG)
                .build();

        assertFalse("Rules must have different presentations if tags are not equal regardless"
                        + "of other properties",
                SplitController.areRulesSamePresentation(splitRule1, splitRule2,
                        new WindowMetrics(TASK_BOUNDS, WindowInsets.CONSUMED)));
    }

    @Test
    public void testSplitInfoCallback_reportSplit() {
        final Activity r0 = createMockActivity();
        final Activity r1 = createMockActivity();
        addSplitTaskFragments(r0, r1);

        mSplitController.updateCallbackIfNecessary();
        assertEquals(1, mSplitInfos.size());
        final SplitInfo splitInfo = mSplitInfos.get(0);
        assertEquals(1, splitInfo.getPrimaryActivityStack().getActivities().size());
        assertEquals(1, splitInfo.getSecondaryActivityStack().getActivities().size());
        assertEquals(r0, splitInfo.getPrimaryActivityStack().getActivities().get(0));
        assertEquals(r1, splitInfo.getSecondaryActivityStack().getActivities().get(0));
    }

    @Test
    public void testSplitInfoCallback_NotReportSplitIfUnstable() {
        final Activity r0 = createMockActivity();
        final Activity r1 = createMockActivity();
        addSplitTaskFragments(r0, r1);

        // Should report new SplitInfo list if stable.
        mSplitController.updateCallbackIfNecessary();
        assertEquals(1, mSplitInfos.size());

        // Should not report new SplitInfo list if unstable, e.g. any Activity is finishing.
        mSplitInfos.clear();
        final Activity r2 = createMockActivity();
        final Activity r3 = createMockActivity();
        doReturn(true).when(r2).isFinishing();
        addSplitTaskFragments(r2, r3);

        mSplitController.updateCallbackIfNecessary();
        assertTrue(mSplitInfos.isEmpty());

        // Should report SplitInfo list if it becomes stable again.
        mSplitInfos.clear();
        doReturn(false).when(r2).isFinishing();

        mSplitController.updateCallbackIfNecessary();
        assertEquals(2, mSplitInfos.size());
    }

    @Test
    public void testSplitInfoCallback_reportSplitInMultipleTasks() {
        final int taskId0 = 1;
        final int taskId1 = 2;
        final Activity r0 = createMockActivity(taskId0);
        final Activity r1 = createMockActivity(taskId0);
        final Activity r2 = createMockActivity(taskId1);
        final Activity r3 = createMockActivity(taskId1);
        addSplitTaskFragments(r0, r1);
        addSplitTaskFragments(r2, r3);

        mSplitController.updateCallbackIfNecessary();
        assertEquals(2, mSplitInfos.size());
    }

    @Test
    public void testSplitInfoCallback_doNotReportIfInIntermediateState() {
        final Activity r0 = createMockActivity();
        final Activity r1 = createMockActivity();
        addSplitTaskFragments(r0, r1);
        final TaskFragmentContainer tf0 = mSplitController.getContainerWithActivity(r0);
        final TaskFragmentContainer tf1 = mSplitController.getContainerWithActivity(r1);
        spyOn(tf0);
        spyOn(tf1);

        // Do not report if activity has not appeared in the TaskFragmentContainer in split.
        doReturn(true).when(tf0).isInIntermediateState();
        mSplitController.updateCallbackIfNecessary();
        verify(mEmbeddingCallback, never()).accept(any());

        doReturn(false).when(tf0).isInIntermediateState();
        mSplitController.updateCallbackIfNecessary();
        verify(mEmbeddingCallback).accept(any());
    }

    @Test
    public void testLaunchPlaceholderIfNecessary_nonEmbeddedActivity() {
        // Launch placeholder for non embedded activity.
        setupPlaceholderRule(mActivity);
        mTransactionManager.startNewTransaction();
        mSplitController.launchPlaceholderIfNecessary(mTransaction, mActivity,
                true /* isOnCreated */);

        verify(mTransaction).startActivityInTaskFragment(any(), any(), eq(PLACEHOLDER_INTENT),
                any());
    }

    @Test
    public void testLaunchPlaceholderIfNecessary_embeddedInTopTaskFragment() {
        // Launch placeholder for activity in top TaskFragment.
        setupPlaceholderRule(mActivity);
        mTransactionManager.startNewTransaction();
        final TaskFragmentContainer container = createTfContainer(mSplitController, mActivity);
        mSplitController.launchPlaceholderIfNecessary(mTransaction, mActivity,
                true /* isOnCreated */);

        assertTrue(container.hasActivity(mActivity.getActivityToken()));
        verify(mTransaction).startActivityInTaskFragment(any(), any(), eq(PLACEHOLDER_INTENT),
                any());
    }

    @Test
    public void testLaunchPlaceholderIfNecessary_embeddedBelowTaskFragment() {
        // Do not launch placeholder for invisible activity below the top TaskFragment.
        setupPlaceholderRule(mActivity);
        mTransactionManager.startNewTransaction();
        final TaskFragmentContainer bottomTf = createTfContainer(mSplitController, mActivity);
        final TaskFragmentContainer topTf =
                new TaskFragmentContainer.Builder(mSplitController, TASK_ID, mActivity)
                        .setPendingAppearedIntent(new Intent()).build();
        bottomTf.setInfo(mTransaction, createMockTaskFragmentInfo(bottomTf, mActivity,
                false /* isVisible */));
        topTf.setInfo(mTransaction, createMockTaskFragmentInfo(topTf, createMockActivity()));
        assertFalse(bottomTf.isVisible());
        mSplitController.launchPlaceholderIfNecessary(mTransaction, mActivity,
                true /* isOnCreated */);

        verify(mTransaction, never()).startActivityInTaskFragment(any(), any(), any(), any());
    }

    @Test
    public void testLaunchPlaceholderIfNecessary_embeddedBelowTransparentTaskFragment() {
        // Launch placeholder for visible activity below the top TaskFragment.
        setupPlaceholderRule(mActivity);
        mTransactionManager.startNewTransaction();
        final TaskFragmentContainer bottomTf = createTfContainer(mSplitController, mActivity);
        final TaskFragmentContainer topTf =
                new TaskFragmentContainer.Builder(mSplitController, TASK_ID, mActivity)
                        .setPendingAppearedIntent(new Intent()).build();
        bottomTf.setInfo(mTransaction, createMockTaskFragmentInfo(bottomTf, mActivity,
                true /* isVisible */));
        topTf.setInfo(mTransaction, createMockTaskFragmentInfo(topTf, createMockActivity()));
        assertTrue(bottomTf.isVisible());
        mSplitController.launchPlaceholderIfNecessary(mTransaction, mActivity,
                true /* isOnCreated */);

        verify(mTransaction).startActivityInTaskFragment(any(), any(), any(), any());
    }

    @Test
    public void testFinishActivityStacks_emptySet_earlyReturn() {
        mSplitController.finishActivityStacks(Collections.emptySet());

        verify(mSplitController, never()).updateContainersInTaskIfVisible(any(), anyInt());
    }

    @Test
    public void testFinishActivityStacks_invalidStacks_earlyReturn() {
        mSplitController.finishActivityStacks(Collections.singleton(new Binder()));

        verify(mSplitController, never()).updateContainersInTaskIfVisible(any(), anyInt());
    }

    @Test
    public void testFinishActivityStacks_finishSingleActivityStack() {
        TaskFragmentContainer tf = createTfContainer(mSplitController, mActivity);
        tf.setInfo(mTransaction, createMockTaskFragmentInfo(tf, mActivity));

        final TaskContainer taskContainer = mSplitController.mTaskContainers.get(TASK_ID);
        assertEquals(taskContainer.getTaskFragmentContainers().get(0), tf);

        mSplitController.finishActivityStacks(Collections.singleton(tf.getTaskFragmentToken()));

        verify(mSplitPresenter).deleteTaskFragment(any(), eq(tf.getTaskFragmentToken()));
        assertTrue(taskContainer.getTaskFragmentContainers().isEmpty());
    }

    @Test
    public void testFinishActivityStacks_finishActivityStacksInOrder() {
        TaskFragmentContainer bottomTf = createTfContainer(mSplitController, mActivity);
        TaskFragmentContainer topTf = createTfContainer(mSplitController, mActivity);
        bottomTf.setInfo(mTransaction, createMockTaskFragmentInfo(bottomTf, mActivity));
        topTf.setInfo(mTransaction, createMockTaskFragmentInfo(topTf, createMockActivity()));

        final TaskContainer taskContainer = mSplitController.mTaskContainers.get(TASK_ID);
        assertEquals(taskContainer.getTaskFragmentContainers().size(), 2);

        Set<IBinder> activityStackTokens = new ArraySet<>(new IBinder[]{
                topTf.getTaskFragmentToken(), bottomTf.getTaskFragmentToken()});

        mSplitController.finishActivityStacks(activityStackTokens);

        ArgumentCaptor<IBinder> argumentCaptor = ArgumentCaptor.forClass(IBinder.class);

        verify(mSplitPresenter, times(2)).deleteTaskFragment(any(), argumentCaptor.capture());

        List<IBinder> fragmentTokens = argumentCaptor.getAllValues();
        assertEquals("The ActivityStack must be deleted from the lowest z-order "
                + "regardless of the order in ActivityStack set",
                bottomTf.getTaskFragmentToken(), fragmentTokens.get(0));
        assertEquals("The ActivityStack must be deleted from the lowest z-order "
                        + "regardless of the order in ActivityStack set",
                topTf.getTaskFragmentToken(), fragmentTokens.get(1));

        assertTrue(taskContainer.getTaskFragmentContainers().isEmpty());
    }

    @Test
    public void testUpdateSplitAttributes_invalidSplitContainerToken_earlyReturn() {
        mSplitController.updateSplitAttributes(new Binder(), SPLIT_ATTRIBUTES);

        verify(mTransactionManager, never()).startNewTransaction();
    }

    @Test
    public void testUpdateSplitAttributes_nullParams_throwException() {
        assertThrows(NullPointerException.class,
                () -> mSplitController.updateSplitAttributes((IBinder) null, SPLIT_ATTRIBUTES));

        final SplitContainer splitContainer = mock(SplitContainer.class);
        final IBinder token = new Binder();
        doReturn(token).when(splitContainer).getToken();
        doReturn(splitContainer).when(mSplitController).getSplitContainer(eq(token));

        assertThrows(NullPointerException.class,
                () -> mSplitController.updateSplitAttributes(token, null));
    }

    @Test
    public void testUpdateSplitAttributes_doNotNeedToUpdateSplitContainer_doNotApplyTransaction() {
        final SplitContainer splitContainer = mock(SplitContainer.class);
        final IBinder token = new Binder();
        doReturn(token).when(splitContainer).getToken();
        doReturn(splitContainer).when(mSplitController).getSplitContainer(eq(token));
        doReturn(false).when(mSplitController).updateSplitContainerIfNeeded(
                eq(splitContainer), any(), eq(SPLIT_ATTRIBUTES));
        TransactionManager.TransactionRecord testRecord =
                mock(TransactionManager.TransactionRecord.class);
        doReturn(testRecord).when(mTransactionManager).startNewTransaction();

        mSplitController.updateSplitAttributes(token, SPLIT_ATTRIBUTES);

        verify(splitContainer).updateDefaultSplitAttributes(eq(SPLIT_ATTRIBUTES));
        verify(testRecord).abort();
    }

    @Test
    public void testUpdateSplitAttributes_splitContainerUpdated_updateAttrs() {
        final SplitContainer splitContainer = mock(SplitContainer.class);
        final IBinder token = new Binder();
        doReturn(token).when(splitContainer).getToken();
        doReturn(splitContainer).when(mSplitController).getSplitContainer(eq(token));
        doReturn(true).when(mSplitController).updateSplitContainerIfNeeded(
                eq(splitContainer), any(), eq(SPLIT_ATTRIBUTES));
        TransactionManager.TransactionRecord testRecord =
                mock(TransactionManager.TransactionRecord.class);
        doReturn(testRecord).when(mTransactionManager).startNewTransaction();

        mSplitController.updateSplitAttributes(token, SPLIT_ATTRIBUTES);

        verify(splitContainer).updateDefaultSplitAttributes(eq(SPLIT_ATTRIBUTES));
        verify(testRecord).apply(eq(false));
    }

    @Test
    public void testPinTopActivityStack() {
        // Create two activities.
        final Activity primaryActivity = createMockActivity();
        final Activity secondaryActivity = createMockActivity();

        // Unable to pin if not being embedded.
        SplitPinRule splitPinRule = new SplitPinRule.Builder(new SplitAttributes.Builder().build(),
                parentWindowMetrics -> true /* parentWindowMetricsPredicate */).build();
        assertFalse(mSplitController.pinTopActivityStack(TASK_ID, splitPinRule));

        // Split the two activities.
        addSplitTaskFragments(primaryActivity, secondaryActivity);
        final TaskFragmentContainer primaryContainer =
                mSplitController.getContainerWithActivity(primaryActivity);
        spyOn(primaryContainer);

        // Unable to pin if no valid TaskFragment.
        doReturn(true).when(primaryContainer).isFinished();
        assertFalse(mSplitController.pinTopActivityStack(TASK_ID, splitPinRule));

        // Otherwise, should pin successfully.
        doReturn(false).when(primaryContainer).isFinished();
        assertTrue(mSplitController.pinTopActivityStack(TASK_ID, splitPinRule));

        // Unable to pin if there is already a pinned TaskFragment
        assertFalse(mSplitController.pinTopActivityStack(TASK_ID, splitPinRule));

        // Unable to pin on an unknown Task.
        assertFalse(mSplitController.pinTopActivityStack(TASK_ID + 1, splitPinRule));

        // Gets the current size of all the SplitContainers.
        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);
        final int splitContainerCount = taskContainer.getSplitContainers().size();

        // Create another activity and split with primary activity.
        final Activity thirdActivity = createMockActivity();
        addSplitTaskFragments(primaryActivity, thirdActivity);

        // Ensure another SplitContainer is added and the pinned TaskFragment still on top
        assertEquals(taskContainer.getSplitContainers().size(), splitContainerCount + +1);
        assertSame(taskContainer.getTopNonFinishingTaskFragmentContainer()
                .getTopNonFinishingActivity(), secondaryActivity);
    }

    @Test
    public void testIsActivityEmbedded() {
        mSetFlagRule.enableFlags(Flags.FLAG_ACTIVITY_WINDOW_INFO_FLAG);

        assertFalse(mSplitController.isActivityEmbedded(mActivity));

        doReturn(true).when(mActivityWindowInfo).isEmbedded();

        assertTrue(mSplitController.isActivityEmbedded(mActivity));
    }

    @Test
    public void testGetEmbeddedActivityWindowInfo() {
        mSetFlagRule.enableFlags(Flags.FLAG_ACTIVITY_WINDOW_INFO_FLAG);

        final boolean isEmbedded = true;
        final Rect taskBounds = new Rect(0, 0, 1000, 2000);
        final Rect activityStackBounds = new Rect(0, 0, 500, 2000);
        doReturn(isEmbedded).when(mActivityWindowInfo).isEmbedded();
        doReturn(taskBounds).when(mActivityWindowInfo).getTaskBounds();
        doReturn(activityStackBounds).when(mActivityWindowInfo).getTaskFragmentBounds();

        final EmbeddedActivityWindowInfo expected = new EmbeddedActivityWindowInfo(mActivity,
                isEmbedded, taskBounds, activityStackBounds);
        assertEquals(expected, mSplitController.getEmbeddedActivityWindowInfo(mActivity));
    }

    @Test
    public void testSetEmbeddedActivityWindowInfoCallback() {
        mSetFlagRule.enableFlags(Flags.FLAG_ACTIVITY_WINDOW_INFO_FLAG);

        final ClientTransactionListenerController controller = ClientTransactionListenerController
                .getInstance();
        spyOn(controller);
        doNothing().when(controller).registerActivityWindowInfoChangedListener(any());
        doReturn(mActivityWindowInfoListener).when(mSplitController)
                .getActivityWindowInfoListener();
        final Executor executor = Runnable::run;

        // Register to ClientTransactionListenerController
        mSplitController.setEmbeddedActivityWindowInfoCallback(executor,
                mEmbeddedActivityWindowInfoCallback);

        verify(controller).registerActivityWindowInfoChangedListener(mActivityWindowInfoListener);
        verify(mEmbeddedActivityWindowInfoCallback, never()).accept(any());

        // Test onActivityWindowInfoChanged triggered.
        mSplitController.onActivityWindowInfoChanged(mActivity.getActivityToken(),
                mActivityWindowInfo);

        verify(mEmbeddedActivityWindowInfoCallback).accept(any());

        // Unregister to ClientTransactionListenerController
        mSplitController.clearEmbeddedActivityWindowInfoCallback();

        verify(controller).unregisterActivityWindowInfoChangedListener(mActivityWindowInfoListener);

        // Test onActivityWindowInfoChanged triggered as no-op after clear callback.
        clearInvocations(mEmbeddedActivityWindowInfoCallback);
        mSplitController.onActivityWindowInfoChanged(mActivity.getActivityToken(),
                mActivityWindowInfo);

        verify(mEmbeddedActivityWindowInfoCallback, never()).accept(any());
    }

    @Test
    public void testTaskFragmentParentInfoChanged() {
        // Making a split
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(mActivity, secondaryActivity, false /* clearTop */);

        // Updates the parent info.
        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);
        final Configuration configuration = new Configuration();
        final TaskFragmentParentInfo originalInfo = new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */);
        mSplitController.onTaskFragmentParentInfoChanged(mock(WindowContainerTransaction.class),
                TASK_ID, originalInfo);
        assertTrue(taskContainer.isVisible());

        // Making a public configuration change while the Task is invisible.
        configuration.densityDpi += 100;
        final TaskFragmentParentInfo invisibleInfo = new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, false /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */);
        mSplitController.onTaskFragmentParentInfoChanged(mock(WindowContainerTransaction.class),
                TASK_ID, invisibleInfo);

        // Ensure the TaskContainer is inivisible, but the configuration is not updated.
        assertFalse(taskContainer.isVisible());
        assertTrue(taskContainer.getTaskFragmentParentInfo().getConfiguration().diffPublicOnly(
                configuration) > 0);

        // Updates when Task to become visible
        final TaskFragmentParentInfo visibleInfo = new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */);
        mSplitController.onTaskFragmentParentInfoChanged(mock(WindowContainerTransaction.class),
                TASK_ID, visibleInfo);

        // Ensure the Task is visible and configuration is updated.
        assertTrue(taskContainer.isVisible());
        assertFalse(taskContainer.getTaskFragmentParentInfo().getConfiguration().diffPublicOnly(
                configuration) > 0);
    }

    /** Creates a mock activity in the organizer process. */
    private Activity createMockActivity() {
        return createMockActivity(TASK_ID);
    }

    /** Creates a mock activity in the organizer process. */
    private Activity createMockActivity(int taskId) {
        final Activity activity = mock(Activity.class);
        final ActivityThread.ActivityClientRecord activityClientRecord =
                mock(ActivityThread.ActivityClientRecord.class);
        doReturn(mActivityResources).when(activity).getResources();
        final IBinder activityToken = new Binder();
        doReturn(activityToken).when(activity).getActivityToken();
        doReturn(activity).when(mSplitController).getActivity(activityToken);
        doReturn(activityClientRecord).when(mSplitController).getActivityClientRecord(activity);
        doReturn(taskId).when(activity).getTaskId();
        doReturn(new ActivityInfo()).when(activity).getActivityInfo();
        doReturn(DEFAULT_DISPLAY).when(activity).getDisplayId();
        doReturn(mActivityWindowInfo).when(activityClientRecord).getActivityWindowInfo();
        return activity;
    }

    /** Creates a mock TaskFragment that has been registered and appeared in the organizer. */
    private TaskFragmentContainer createMockTaskFragmentContainer(@NonNull Activity activity) {
        final TaskFragmentContainer container = createTfContainer(mSplitController,
                activity.getTaskId(), activity);
        setupTaskFragmentInfo(container, activity);
        return container;
    }

    /** Setups the given TaskFragment as it has appeared in the server. */
    private void setupTaskFragmentInfo(@NonNull TaskFragmentContainer container,
            @NonNull Activity activity) {
        final TaskFragmentInfo info = createMockTaskFragmentInfo(container, activity);
        container.setInfo(mTransaction, info);
        mSplitPresenter.mFragmentInfos.put(container.getTaskFragmentToken(), info);
    }

    /** Setups a rule to always expand the given intent. */
    private void setupExpandRule(@NonNull Intent expandIntent) {
        final ActivityRule expandRule = createActivityBuilder(r -> false, expandIntent::equals)
                .setShouldAlwaysExpand(true)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(expandRule));
    }

    /** Setups a rule to always expand the given activity. */
    private void setupExpandRule(@NonNull Activity expandActivity) {
        final ActivityRule expandRule = createActivityBuilder(expandActivity::equals, i -> false)
                .setShouldAlwaysExpand(true)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(expandRule));
    }

    /** Setups a rule to launch placeholder for the given activity. */
    private void setupPlaceholderRule(@NonNull Activity primaryActivity) {
        final SplitRule placeholderRule = createSplitPlaceholderRuleBuilder(PLACEHOLDER_INTENT,
                primaryActivity::equals, i -> false, w -> true)
                .setDefaultSplitAttributes(SPLIT_ATTRIBUTES)
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
                rule,
                SPLIT_ATTRIBUTES);

        // We need to set those in case we are not respecting clear top.
        // TODO(b/231845476) we should always respect clearTop.
        final int windowingMode = mSplitController.getTaskContainer(primaryContainer.getTaskId())
                .getWindowingModeForTaskFragment(TASK_BOUNDS);
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
