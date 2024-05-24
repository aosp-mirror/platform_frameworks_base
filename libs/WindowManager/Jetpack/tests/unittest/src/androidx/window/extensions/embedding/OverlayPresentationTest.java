/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.extensions.embedding.ActivityEmbeddingOptionsProperties.KEY_OVERLAY_TAG;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.SPLIT_ATTRIBUTES;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_BOUNDS;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_ID;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.TEST_TAG;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createMockTaskFragmentInfo;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitPairRuleBuilder;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitPlaceholderRuleBuilder;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createSplitRule;
import static androidx.window.extensions.embedding.SplitPresenter.sanitizeBounds;
import static androidx.window.extensions.embedding.WindowAttributes.DIM_AREA_ON_TASK;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

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
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.Size;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test class for overlay presentation feature.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:OverlayPresentationTest
 */
// Suppress GuardedBy warning on unit tests
@SuppressWarnings("GuardedBy")
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class OverlayPresentationTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final Intent PLACEHOLDER_INTENT = new Intent().setComponent(
            new ComponentName("test", "placeholder"));

    @Rule
    public final SetFlagsRule mSetFlagRule = new SetFlagsRule();

    private SplitController.ActivityStartMonitor mMonitor;

    private Intent mIntent;

    private TaskFragmentContainer mOverlayContainer1;

    private TaskFragmentContainer mOverlayContainer2;

    private Activity mActivity;
    @Mock
    private Resources mActivityResources;

    @Mock
    private WindowContainerTransaction mTransaction;
    @Mock
    private Handler mHandler;
    @Mock
    private WindowLayoutComponentImpl mWindowLayoutComponent;

    private SplitController mSplitController;
    private SplitPresenter mSplitPresenter;

    @Before
    public void setUp() {
        doReturn(new WindowLayoutInfo(new ArrayList<>())).when(mWindowLayoutComponent)
                .getCurrentWindowLayoutInfo(anyInt(), any());
        DeviceStateManagerFoldingFeatureProducer producer =
                mock(DeviceStateManagerFoldingFeatureProducer.class);
        mSplitController = new SplitController(mWindowLayoutComponent, producer);
        mSplitPresenter = mSplitController.mPresenter;
        mMonitor = mSplitController.getActivityStartMonitor();
        mIntent = new Intent();

        spyOn(mSplitController);
        spyOn(mSplitPresenter);
        spyOn(mMonitor);

        doNothing().when(mSplitPresenter).applyTransaction(any(), anyInt(), anyBoolean());
        final Configuration activityConfig = new Configuration();
        activityConfig.windowConfiguration.setBounds(TASK_BOUNDS);
        activityConfig.windowConfiguration.setMaxBounds(TASK_BOUNDS);
        doReturn(activityConfig).when(mActivityResources).getConfiguration();
        doReturn(mHandler).when(mSplitController).getHandler();
        mActivity = createMockActivity();

        mSetFlagRule.enableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_OVERLAY_PRESENTATION_FLAG);
    }

    /** Creates a mock activity in the organizer process. */
    @NonNull
    private Activity createMockActivity() {
        final Activity activity = mock(Activity.class);
        doReturn(mActivityResources).when(activity).getResources();
        final IBinder activityToken = new Binder();
        doReturn(activityToken).when(activity).getActivityToken();
        doReturn(activity).when(mSplitController).getActivity(activityToken);
        doReturn(TASK_ID).when(activity).getTaskId();
        doReturn(new ActivityInfo()).when(activity).getActivityInfo();
        doReturn(DEFAULT_DISPLAY).when(activity).getDisplayId();
        return activity;
    }

    @Test
    public void testStartActivity_overlayFeatureDisabled_notInvokeCreateOverlayContainer() {
        mSetFlagRule.disableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_OVERLAY_PRESENTATION_FLAG);

        final Bundle optionsBundle = ActivityOptions.makeBasic().toBundle();
        optionsBundle.putString(KEY_OVERLAY_TAG, "test");
        mMonitor.onStartActivity(mActivity, mIntent, optionsBundle);

        verify(mSplitController, never()).createOrUpdateOverlayTaskFragmentIfNeeded(any(), any(),
                any(), any());
    }

    @Test
    public void testSetIsolatedNavigation_overlayFeatureDisabled_earlyReturn() {
        mSetFlagRule.disableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_OVERLAY_PRESENTATION_FLAG);

        final TaskFragmentContainer container = createTestOverlayContainer(TASK_ID, "test");

        mSplitPresenter.setTaskFragmentIsolatedNavigation(mTransaction, container,
                !container.isIsolatedNavigationEnabled());

        verify(mSplitPresenter, never()).setTaskFragmentIsolatedNavigation(any(),
                any(IBinder.class), anyBoolean());
    }

    @Test
    public void testSetPinned_overlayFeatureDisabled_earlyReturn() {
        mSetFlagRule.disableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_OVERLAY_PRESENTATION_FLAG);

        final TaskFragmentContainer container = createTestOverlayContainer(TASK_ID, "test");

        mSplitPresenter.setTaskFragmentPinned(mTransaction, container,
                !container.isPinned());

        verify(mSplitPresenter, never()).setTaskFragmentPinned(any(), any(IBinder.class),
                anyBoolean());
    }

    @Test
    public void testGetAllNonFinishingOverlayContainers() {
        assertThat(mSplitController.getAllNonFinishingOverlayContainers()).isEmpty();

        final TaskFragmentContainer overlayContainer1 =
                createTestOverlayContainer(TASK_ID, "test1");

        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(overlayContainer1);

        final TaskFragmentContainer overlayContainer2 =
                createTestOverlayContainer(TASK_ID, "test2");

        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(overlayContainer1, overlayContainer2);

        final TaskFragmentContainer overlayContainer3 =
                createTestOverlayContainer(TASK_ID + 1, "test3");

        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(overlayContainer1, overlayContainer2, overlayContainer3);

        final TaskFragmentContainer finishingOverlayContainer =
                createTestOverlayContainer(TASK_ID, "test4");
        spyOn(finishingOverlayContainer);
        doReturn(true).when(finishingOverlayContainer).isFinished();

        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(overlayContainer1, overlayContainer2, overlayContainer3);
    }

    @Test
    public void testCreateOrUpdateOverlayTaskFragmentIfNeeded_anotherTagInTask() {
        createExistingOverlayContainers(false /* visible */);
        createMockTaskFragmentContainer(mActivity);

        final TaskFragmentContainer overlayContainer =
                createOrUpdateOverlayTaskFragmentIfNeeded("test3");

        assertWithMessage("overlayContainer1 is still there since it's not visible.")
                .that(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(mOverlayContainer1, mOverlayContainer2, overlayContainer);
    }

    @Test
    public void testCreateOrUpdateOverlay_visibleOverlaySameTagInTask_dismissOverlay() {
        createExistingOverlayContainers();

        final TaskFragmentContainer overlayContainer =
                createOrUpdateOverlayTaskFragmentIfNeeded("test3");

        assertWithMessage("overlayContainer1 must be dismissed since it's visible"
                + " in the same task.")
                .that(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(mOverlayContainer2, overlayContainer);
    }

    @Test
    public void testCreateOrUpdateOverlayTaskFragmentIfNeeded_sameTagAnotherTask_dismissOverlay() {
        createExistingOverlayContainers();

        doReturn(TASK_ID + 2).when(mActivity).getTaskId();
        final TaskFragmentContainer overlayContainer =
                createOrUpdateOverlayTaskFragmentIfNeeded("test1");

        assertWithMessage("overlayContainer1 must be dismissed since the new overlay container"
                + " is launched with the same tag as an existing overlay container in a different "
                + "task")
                .that(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(mOverlayContainer2, overlayContainer);
    }

    @Test
    public void testCreateOrUpdateOverlay_sameTagTaskAndActivity_updateOverlay() {
        createExistingOverlayContainers();

        final Rect bounds = new Rect(0, 0, 100, 100);
        mSplitController.setActivityStackAttributesCalculator(params ->
                new ActivityStackAttributes.Builder().setRelativeBounds(bounds).build());
        final TaskFragmentContainer overlayContainer = createOrUpdateOverlayTaskFragmentIfNeeded(
                mOverlayContainer1.getOverlayTag());

        assertWithMessage("overlayContainer1 must be updated since the new overlay container"
                + " is launched with the same tag and task")
                .that(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(mOverlayContainer1, mOverlayContainer2);

        assertThat(overlayContainer).isEqualTo(mOverlayContainer1);
        verify(mSplitPresenter).resizeTaskFragment(eq(mTransaction),
                eq(mOverlayContainer1.getTaskFragmentToken()), eq(bounds));
    }

    @Test
    public void testCreateOrUpdateOverlay_sameTagAndTaskButNotActivity_dismissOverlay() {
        createExistingOverlayContainers();

        final Rect bounds = new Rect(0, 0, 100, 100);
        mSplitController.setActivityStackAttributesCalculator(params ->
                new ActivityStackAttributes.Builder().setRelativeBounds(bounds).build());
        final TaskFragmentContainer overlayContainer = createOrUpdateOverlayTaskFragmentIfNeeded(
                mOverlayContainer1.getOverlayTag(), createMockActivity());

        assertWithMessage("overlayContainer1 must be dismissed since the new overlay container"
                + " is associated with different launching activity")
                .that(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(mOverlayContainer2, overlayContainer);
        assertThat(overlayContainer).isNotEqualTo(mOverlayContainer1);
    }

    @Test
    public void testCreateOrUpdateOverlayTaskFragmentIfNeeded_dismissOverlay() {
        createExistingOverlayContainers(false /* visible */);
        createMockTaskFragmentContainer(mActivity);

        final TaskFragmentContainer overlayContainer =
                createOrUpdateOverlayTaskFragmentIfNeeded("test2");

        // OverlayContainer2 is dismissed since new container is launched with the
        // same tag in different task.
        assertWithMessage("overlayContainer1 must be dismissed")
                .that(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(mOverlayContainer1, overlayContainer);
    }

    @Test
    public void testCreateOrUpdateOverlayTaskFragmentIfNeeded_dismissMultipleOverlays() {
        createExistingOverlayContainers();

        final TaskFragmentContainer overlayContainer =
                createOrUpdateOverlayTaskFragmentIfNeeded("test2");

        // OverlayContainer1 is dismissed since new container is launched in the same task with
        // different tag. OverlayContainer2 is dismissed since new container is launched with the
        // same tag in different task.
        assertWithMessage("overlayContainer1 and overlayContainer2 must be dismissed")
                .that(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(overlayContainer);
    }

    @Test
    public void testCreateOrUpdateOverlay_launchFromSplit_returnNull() {
        final Activity primaryActivity = createMockActivity();
        final Activity secondaryActivity = createMockActivity();
        final TaskFragmentContainer primaryContainer =
                createMockTaskFragmentContainer(primaryActivity);
        final TaskFragmentContainer secondaryContainer =
                createMockTaskFragmentContainer(secondaryActivity);
        final SplitPairRule splitPairRule = createSplitPairRuleBuilder(
                activityActivityPair -> true /* activityPairPredicate */,
                activityIntentPair -> true  /* activityIntentPairPredicate */,
                parentWindowMetrics -> true /* parentWindowMetricsPredicate */).build();
        mSplitController.registerSplit(mTransaction, primaryContainer, primaryActivity,
                secondaryContainer, splitPairRule,  splitPairRule.getDefaultSplitAttributes());

        assertThat(createOrUpdateOverlayTaskFragmentIfNeeded("test", primaryActivity)).isNull();
        assertThat(createOrUpdateOverlayTaskFragmentIfNeeded("test", secondaryActivity)).isNull();
    }

    private void createExistingOverlayContainers() {
        createExistingOverlayContainers(true /* visible */);
    }

    private void createExistingOverlayContainers(boolean visible) {
        mOverlayContainer1 = createTestOverlayContainer(TASK_ID, "test1", visible,
                true /* associatedLaunchingActivity */, mActivity);
        mOverlayContainer2 = createTestOverlayContainer(TASK_ID + 1, "test2", visible);
        List<TaskFragmentContainer> overlayContainers = mSplitController
                .getAllNonFinishingOverlayContainers();
        assertThat(overlayContainers).containsExactly(mOverlayContainer1, mOverlayContainer2);
    }

    @Test
    public void testSanitizeBounds_smallerThanMinDimens_expandOverlay() {
        mIntent.setComponent(new ComponentName(ApplicationProvider.getApplicationContext(),
                MinimumDimensionActivity.class));
        final Rect bounds = new Rect(0, 0, 100, 100);
        final TaskFragmentContainer overlayContainer =
                createTestOverlayContainer(TASK_ID, "test1");

        assertThat(sanitizeBounds(bounds, SplitPresenter.getMinDimensions(mIntent),
                overlayContainer).isEmpty()).isTrue();
    }

    @Test
    public void testSanitizeBounds_notInTaskBounds_expandOverlay() {
        final Rect bounds = new Rect(TASK_BOUNDS);
        bounds.offset(10, 10);
        final TaskFragmentContainer overlayContainer =
                createTestOverlayContainer(TASK_ID, "test1");

        assertThat(sanitizeBounds(bounds, null, overlayContainer)
                .isEmpty()).isTrue();
    }

    @Test
    public void testCreateOrUpdateOverlayTaskFragmentIfNeeded_createOverlay() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        mSplitController.setActivityStackAttributesCalculator(params ->
                new ActivityStackAttributes.Builder().setRelativeBounds(bounds).build());
        final TaskFragmentContainer overlayContainer =
                createOrUpdateOverlayTaskFragmentIfNeeded("test");
        setupTaskFragmentInfo(overlayContainer, mActivity, true /* isVisible */);

        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .containsExactly(overlayContainer);
        assertThat(overlayContainer.getTaskId()).isEqualTo(TASK_ID);
        assertThat(overlayContainer.areLastRequestedBoundsEqual(bounds)).isTrue();
        assertThat(overlayContainer.getOverlayTag()).isEqualTo("test");
    }

    @Test
    public void testGetTopNonFishingTaskFragmentContainerWithoutAssociatedOverlay() {
        final Activity primaryActivity = createMockActivity();
        final Activity secondaryActivity = createMockActivity();
        final Rect bounds = new Rect(0, 0, 100, 100);
        mSplitController.setActivityStackAttributesCalculator(params ->
                new ActivityStackAttributes.Builder().setRelativeBounds(bounds).build());
        final TaskFragmentContainer overlayContainer =
                createTestOverlayContainer(TASK_ID, "test1", true /* isVisible */,
                        false /* shouldAssociateWithActivity */);
        overlayContainer.setIsolatedNavigationEnabled(true);
        final TaskFragmentContainer primaryContainer =
                createMockTaskFragmentContainer(primaryActivity);
        final TaskFragmentContainer secondaryContainer =
                createMockTaskFragmentContainer(secondaryActivity);
        final SplitPairRule splitPairRule = createSplitPairRuleBuilder(
                activityActivityPair -> true /* activityPairPredicate */,
                activityIntentPair -> true  /* activityIntentPairPredicate */,
                parentWindowMetrics -> true /* parentWindowMetricsPredicate */).build();
        mSplitController.registerSplit(mTransaction, primaryContainer, primaryActivity,
                secondaryContainer, splitPairRule,  splitPairRule.getDefaultSplitAttributes());
        SplitPinRule splitPinRule = new SplitPinRule.Builder(new SplitAttributes.Builder().build(),
                parentWindowMetrics -> true /* parentWindowMetricsPredicate */).build();
        mSplitController.pinTopActivityStack(TASK_ID, splitPinRule);
        final TaskFragmentContainer topPinnedContainer = mSplitController.getTaskContainer(TASK_ID)
                .getSplitPinContainer().getSecondaryContainer();

        // Add a normal container after the overlay, the overlay should still on top,
        // and the SplitPinContainer should also on top of the normal one.
        final TaskFragmentContainer container = createMockTaskFragmentContainer(mActivity);

        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);

        assertThat(taskContainer.getTaskFragmentContainers())
                .containsExactly(primaryContainer, container, secondaryContainer, overlayContainer)
                .inOrder();

        assertWithMessage("The pinned container must be returned excluding the overlay")
                .that(taskContainer.getTopNonFinishingTaskFragmentContainer())
                .isEqualTo(topPinnedContainer);

        assertThat(taskContainer.getTopNonFinishingTaskFragmentContainer(false))
                .isEqualTo(container);

        assertWithMessage("The overlay container must be returned since it's always on top")
                .that(taskContainer.getTopNonFinishingTaskFragmentContainer(
                        false /* includePin */, true /* includeOverlay */))
                .isEqualTo(overlayContainer);
    }

    @Test
    public void testGetTopNonFinishingActivityWithOverlay() {
        final Activity activity = createMockActivity();
        final TaskFragmentContainer container = createMockTaskFragmentContainer(activity);
        final TaskFragmentContainer overlayContainer = createTestOverlayContainer(TASK_ID,
                "test1");
        final TaskContainer task = container.getTaskContainer();

        assertThat(task.getTopNonFinishingActivity(true /* includeOverlay */))
                .isEqualTo(overlayContainer.getTopNonFinishingActivity());
        assertThat(task.getTopNonFinishingActivity(false /* includeOverlay */)).isEqualTo(activity);
    }

    @Test
    public void testUpdateContainer_dontInvokeUpdateOverlayForNonOverlayContainer() {
        TaskFragmentContainer taskFragmentContainer = createMockTaskFragmentContainer(mActivity);

        mSplitController.updateContainer(mTransaction, taskFragmentContainer);
        verify(mSplitController, never()).updateOverlayContainer(any(), any());
    }

    @Test
    public void testUpdateOverlayContainer_dismissNonAssociatedOverlayIfNeeded() {
        TaskFragmentContainer overlayContainer = createTestOverlayContainer(TASK_ID, "test",
                true /* isVisible */, false /* associatedLaunchingActivity */);

        mSplitController.updateOverlayContainer(mTransaction, overlayContainer);

        final TaskContainer taskContainer = overlayContainer.getTaskContainer();
        assertThat(taskContainer.getTaskFragmentContainers()).containsExactly(overlayContainer);

        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(Configuration.EMPTY,
                DEFAULT_DISPLAY, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */));

        mSplitController.updateOverlayContainer(mTransaction, overlayContainer);

        assertWithMessage("The overlay must be dismissed since there's no activity"
                + " in the task and other taskFragment.")
                .that(taskContainer.getTaskFragmentContainers()).isEmpty();
    }

    @Test
    public void testUpdateActivityStackAttributes_nullParams_throwException() {
        assertThrows(NullPointerException.class, () ->
                mSplitController.updateActivityStackAttributes(null,
                        new ActivityStackAttributes.Builder().build()));

        assertThrows(NullPointerException.class, () ->
                mSplitController.updateActivityStackAttributes(
                        ActivityStack.Token.createFromBinder(new Binder()), null));

        verify(mSplitPresenter, never()).applyActivityStackAttributes(any(), any(), any(), any());
    }

    @Test
    public void testUpdateActivityStackAttributes_nullContainer_earlyReturn() {
        final TaskFragmentContainer container = mSplitController.newContainer(mActivity,
                mActivity.getTaskId());
        mSplitController.updateActivityStackAttributes(
                ActivityStack.Token.createFromBinder(container.getTaskFragmentToken()),
                new ActivityStackAttributes.Builder().build());

        verify(mSplitPresenter, never()).applyActivityStackAttributes(any(), any(), any(), any());
    }

    @Test
    public void testUpdateActivityStackAttributes_notOverlay_earlyReturn() {
        final TaskFragmentContainer container = createMockTaskFragmentContainer(mActivity);

        mSplitController.updateActivityStackAttributes(
                ActivityStack.Token.createFromBinder(container.getTaskFragmentToken()),
                new ActivityStackAttributes.Builder().build());

        verify(mSplitPresenter, never()).applyActivityStackAttributes(any(), any(), any(), any());
    }

    @Test
    public void testUpdateActivityStackAttributes() {
        final TaskFragmentContainer container = createTestOverlayContainer(TASK_ID, "test");
        doNothing().when(mSplitPresenter).applyActivityStackAttributes(any(), any(), any(), any());
        final ActivityStackAttributes attrs = new ActivityStackAttributes.Builder().build();
        final IBinder token = container.getTaskFragmentToken();

        mSplitController.updateActivityStackAttributes(ActivityStack.Token.createFromBinder(token),
                attrs);

        verify(mSplitPresenter).applyActivityStackAttributes(any(), eq(container), eq(attrs),
                any());
    }

    @Test
    public void testOnTaskFragmentParentInfoChanged_positionOnlyChange_earlyReturn() {
        final TaskFragmentContainer overlayContainer = createTestOverlayContainer(TASK_ID, "test",
                true /* isVisible */, false /* associatedLaunchingActivity */);
        final TaskContainer taskContainer = overlayContainer.getTaskContainer();

        spyOn(taskContainer);
        final TaskContainer.TaskProperties taskProperties = taskContainer.getTaskProperties();
        final TaskFragmentParentInfo parentInfo = new TaskFragmentParentInfo(
                new Configuration(taskProperties.getConfiguration()), taskProperties.getDisplayId(),
                true /* visible */, false /* hasDirectActivity */, null /* decorSurface */);
        parentInfo.getConfiguration().windowConfiguration.getBounds().offset(10, 10);

        mSplitController.onTaskFragmentParentInfoChanged(mTransaction, TASK_ID, parentInfo);

        // The parent info must be applied to the task container
        verify(taskContainer).updateTaskFragmentParentInfo(parentInfo);
        verify(mSplitController, never()).updateContainer(any(), any());

        assertWithMessage("The overlay container must still be dismissed even if "
                + "#updateContainer is not called")
                .that(taskContainer.getTaskFragmentContainers()).isEmpty();
    }

    @Test
    public void testOnTaskFragmentParentInfoChanged_invisibleTask_callDismissNonAssocOverlay() {
        final TaskFragmentContainer overlayContainer = createTestOverlayContainer(TASK_ID, "test",
                true /* isVisible */, false /* associatedLaunchingActivity */);
        final TaskContainer taskContainer = overlayContainer.getTaskContainer();

        spyOn(taskContainer);
        final TaskContainer.TaskProperties taskProperties = taskContainer.getTaskProperties();
        final TaskFragmentParentInfo parentInfo = new TaskFragmentParentInfo(
                new Configuration(taskProperties.getConfiguration()), taskProperties.getDisplayId(),
                true /* visible */, false /* hasDirectActivity */, null /* decorSurface */);

        mSplitController.onTaskFragmentParentInfoChanged(mTransaction, TASK_ID, parentInfo);

        // The parent info must be applied to the task container
        verify(taskContainer).updateTaskFragmentParentInfo(parentInfo);
        verify(mSplitController, never()).updateContainer(any(), any());

        assertWithMessage("The overlay container must still be dismissed even if "
                + "#updateContainer is not called")
                .that(taskContainer.getTaskFragmentContainers()).isEmpty();
    }

    @Test
    public void testApplyActivityStackAttributesForExpandedContainer() {
        final TaskFragmentContainer container = createMockTaskFragmentContainer(mActivity);
        final IBinder token = container.getTaskFragmentToken();
        final ActivityStackAttributes attributes = new ActivityStackAttributes.Builder().build();

        mSplitPresenter.applyActivityStackAttributes(mTransaction, container, attributes,
                null /* minDimensions */);

        verify(mSplitPresenter).resizeTaskFragmentIfRegistered(mTransaction, container,
                attributes.getRelativeBounds());
        verify(mSplitPresenter).updateTaskFragmentWindowingModeIfRegistered(mTransaction, container,
                WINDOWING_MODE_UNDEFINED);
        verify(mSplitPresenter).updateAnimationParams(mTransaction, token,
                TaskFragmentAnimationParams.DEFAULT);
        verify(mSplitPresenter).setTaskFragmentDimOnTask(mTransaction, token, false);
        verify(mSplitPresenter, never()).setTaskFragmentPinned(any(),
                any(TaskFragmentContainer.class), anyBoolean());
        verify(mSplitPresenter, never()).setTaskFragmentIsolatedNavigation(any(),
                any(TaskFragmentContainer.class), anyBoolean());
    }

    @Test
    public void testApplyActivityStackAttributesForOverlayContainerAssociatedWithActivity() {
        final TaskFragmentContainer container = createTestOverlayContainer(TASK_ID, TEST_TAG);
        final IBinder token = container.getTaskFragmentToken();
        final ActivityStackAttributes attributes = new ActivityStackAttributes.Builder()
                .setRelativeBounds(new Rect(0, 0, 200, 200))
                .setWindowAttributes(new WindowAttributes(DIM_AREA_ON_TASK))
                .build();

        mSplitPresenter.applyActivityStackAttributes(mTransaction, container, attributes,
                null /* minDimensions */);

        verify(mSplitPresenter).resizeTaskFragmentIfRegistered(mTransaction, container,
                attributes.getRelativeBounds());
        verify(mSplitPresenter).updateTaskFragmentWindowingModeIfRegistered(mTransaction, container,
                WINDOWING_MODE_MULTI_WINDOW);
        verify(mSplitPresenter).updateAnimationParams(mTransaction, token,
                TaskFragmentAnimationParams.DEFAULT);
        verify(mSplitPresenter).setTaskFragmentIsolatedNavigation(mTransaction, container, true);
        verify(mSplitPresenter, never()).setTaskFragmentPinned(any(),
                any(TaskFragmentContainer.class), anyBoolean());
        verify(mSplitPresenter).setTaskFragmentDimOnTask(mTransaction, token, true);
    }

    @Test
    public void testApplyActivityStackAttributesForOverlayContainerWithoutAssociatedActivity() {
        final TaskFragmentContainer container = createTestOverlayContainer(TASK_ID, TEST_TAG,
                true, /* isVisible */ false /* associatedWithLaunchingActivity */);
        final IBinder token = container.getTaskFragmentToken();
        final ActivityStackAttributes attributes = new ActivityStackAttributes.Builder()
                .setRelativeBounds(new Rect(0, 0, 200, 200))
                .setWindowAttributes(new WindowAttributes(DIM_AREA_ON_TASK))
                .build();

        mSplitPresenter.applyActivityStackAttributes(mTransaction, container,
                attributes, null /* minDimensions */);

        verify(mSplitPresenter).resizeTaskFragmentIfRegistered(mTransaction, container,
                attributes.getRelativeBounds());
        verify(mSplitPresenter).updateTaskFragmentWindowingModeIfRegistered(mTransaction,
                container, WINDOWING_MODE_MULTI_WINDOW);
        verify(mSplitPresenter).updateAnimationParams(mTransaction, token,
                TaskFragmentAnimationParams.DEFAULT);
        verify(mSplitPresenter, never()).setTaskFragmentIsolatedNavigation(any(),
                any(TaskFragmentContainer.class), anyBoolean());
        verify(mSplitPresenter).setTaskFragmentPinned(mTransaction, container, true);
        verify(mSplitPresenter).setTaskFragmentDimOnTask(mTransaction, token, true);
    }

    @Test
    public void testApplyActivityStackAttributesForExpandedOverlayContainer() {
        final TaskFragmentContainer container = createTestOverlayContainer(TASK_ID, TEST_TAG);
        final IBinder token = container.getTaskFragmentToken();
        final ActivityStackAttributes attributes = new ActivityStackAttributes.Builder().build();

        mSplitPresenter.applyActivityStackAttributes(mTransaction, container, attributes,
                null /* minDimensions */);

        verify(mSplitPresenter).resizeTaskFragmentIfRegistered(mTransaction, container,
                attributes.getRelativeBounds());
        verify(mSplitPresenter).updateTaskFragmentWindowingModeIfRegistered(mTransaction, container,
                WINDOWING_MODE_UNDEFINED);
        verify(mSplitPresenter).updateAnimationParams(mTransaction, token,
                TaskFragmentAnimationParams.DEFAULT);
        verify(mSplitPresenter).setTaskFragmentIsolatedNavigation(mTransaction, container, false);
        verify(mSplitPresenter, never()).setTaskFragmentPinned(any(),
                any(TaskFragmentContainer.class), anyBoolean());
        verify(mSplitPresenter).setTaskFragmentDimOnTask(mTransaction, token, false);
    }

    @Test
    public void testApplyActivityStackAttributesForOverlayContainer_exceedsMinDimensions() {
        final TaskFragmentContainer container = createTestOverlayContainer(TASK_ID, TEST_TAG);
        final IBinder token = container.getTaskFragmentToken();
        final Rect relativeBounds = new Rect(0, 0, 200, 200);
        final ActivityStackAttributes attributes = new ActivityStackAttributes.Builder()
                .setRelativeBounds(relativeBounds)
                .setWindowAttributes(new WindowAttributes(DIM_AREA_ON_TASK))
                .build();

        mSplitPresenter.applyActivityStackAttributes(mTransaction, container, attributes,
                new Size(relativeBounds.width() + 1, relativeBounds.height()));

        verify(mSplitPresenter).resizeTaskFragmentIfRegistered(mTransaction, container, new Rect());
        verify(mSplitPresenter).updateTaskFragmentWindowingModeIfRegistered(mTransaction, container,
                WINDOWING_MODE_UNDEFINED);
        verify(mSplitPresenter).updateAnimationParams(mTransaction, token,
                TaskFragmentAnimationParams.DEFAULT);
        verify(mSplitPresenter).setTaskFragmentIsolatedNavigation(mTransaction, container, false);
        verify(mSplitPresenter).setTaskFragmentDimOnTask(mTransaction, token, false);
    }

    @Test
    public void testFinishSelfWithActivityIfNeeded() {
        TaskFragmentContainer container = createMockTaskFragmentContainer(mActivity);

        container.finishSelfWithActivityIfNeeded(mTransaction, mActivity.getActivityToken());

        verify(mSplitPresenter, never()).cleanupContainer(any(), any(), anyBoolean());

        TaskFragmentContainer overlayWithoutAssociation = createTestOverlayContainer(TASK_ID,
                "test", false /* associateLaunchingActivity */);

        overlayWithoutAssociation.finishSelfWithActivityIfNeeded(mTransaction,
                mActivity.getActivityToken());

        verify(mSplitPresenter, never()).cleanupContainer(any(), any(), anyBoolean());
        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .contains(overlayWithoutAssociation);

        TaskFragmentContainer overlayWithAssociation =
                createOrUpdateOverlayTaskFragmentIfNeeded("test");
        overlayWithAssociation.setInfo(mTransaction, createMockTaskFragmentInfo(
                overlayWithAssociation, mActivity, true /* isVisible */));
        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .contains(overlayWithAssociation);
        clearInvocations(mSplitPresenter);

        overlayWithAssociation.finishSelfWithActivityIfNeeded(mTransaction, new Binder());

        verify(mSplitPresenter, never()).cleanupContainer(any(), any(), anyBoolean());

        overlayWithAssociation.finishSelfWithActivityIfNeeded(mTransaction,
                mActivity.getActivityToken());

        verify(mSplitPresenter).cleanupContainer(mTransaction, overlayWithAssociation, false);

        assertThat(mSplitController.getAllNonFinishingOverlayContainers())
                .doesNotContain(overlayWithAssociation);
    }

    @Test
    public void testLaunchPlaceholderIfNecessary_skipIfActivityAssociateOverlay() {
        setupPlaceholderRule(mActivity);
        createTestOverlayContainer(TASK_ID, "test", true /* isVisible */,
                true /* associateLaunchingActivity */, mActivity);


        mSplitController.mTransactionManager.startNewTransaction();
        assertThat(mSplitController.launchPlaceholderIfNecessary(mTransaction, mActivity,
                false /* isOnCreated */)).isFalse();

        verify(mTransaction, never()).startActivityInTaskFragment(any(), any(), any(), any());
    }

    @Test
    public void testLaunchPlaceholderIfNecessary_skipIfActivityInOverlay() {
        setupPlaceholderRule(mActivity);
        createOrUpdateOverlayTaskFragmentIfNeeded("test1", mActivity);

        mSplitController.mTransactionManager.startNewTransaction();
        assertThat(mSplitController.launchPlaceholderIfNecessary(mTransaction, mActivity,
                false /* isOnCreated */)).isFalse();

        verify(mTransaction, never()).startActivityInTaskFragment(any(), any(), any(), any());
    }

    /** Setups a rule to launch placeholder for the given activity. */
    private void setupPlaceholderRule(@NonNull Activity primaryActivity) {
        final SplitRule placeholderRule = createSplitPlaceholderRuleBuilder(PLACEHOLDER_INTENT,
                primaryActivity::equals, i -> false, w -> true)
                .setDefaultSplitAttributes(SPLIT_ATTRIBUTES)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(placeholderRule));
    }

    @Test
    public void testResolveStartActivityIntent_skipIfAssociateOverlay() {
        final Intent intent = new Intent();
        mSplitController.setEmbeddingRules(Collections.singleton(
                createSplitRule(mActivity, intent)));
        createTestOverlayContainer(TASK_ID, "test", true /* isVisible */,
                true /* associateLaunchingActivity */, mActivity);
        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);

        assertThat(container).isNull();
        verify(mSplitController, never()).resolveStartActivityIntentByRule(any(), anyInt(), any(),
                any());
    }

    @Test
    public void testResolveStartActivityIntent_skipIfLaunchingActivityInOverlay() {
        final Intent intent = new Intent();
        mSplitController.setEmbeddingRules(Collections.singleton(
                createSplitRule(mActivity, intent)));
        createOrUpdateOverlayTaskFragmentIfNeeded("test1", mActivity);
        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);

        assertThat(container).isNull();
        verify(mSplitController, never()).resolveStartActivityIntentByRule(any(), anyInt(), any(),
                any());
    }

    @Test
    public void testOnActivityReparentedToTask_overlayRestoration() {
        mSetFlagRule.enableFlags(Flags.FLAG_FIX_PIP_RESTORE_TO_OVERLAY);

        // Prepares and mock the data necessary for the test.
        final IBinder activityToken = mActivity.getActivityToken();
        final Intent intent = new Intent();
        final IBinder fillTaskActivityToken = new Binder();
        final IBinder lastOverlayToken = new Binder();
        final TaskFragmentContainer overlayContainer = mSplitController.newContainer(intent,
                mActivity, TASK_ID);
        final TaskFragmentContainer.OverlayContainerRestoreParams params = mock(
                TaskFragmentContainer.OverlayContainerRestoreParams.class);
        doReturn(params).when(mSplitController).getOverlayContainerRestoreParams(any(), any());
        doReturn(overlayContainer).when(mSplitController).createOrUpdateOverlayTaskFragmentIfNeeded(
                any(), any(), any(), any());

        // Verify the activity should be reparented to the overlay container.
        mSplitController.onActivityReparentedToTask(mTransaction, TASK_ID, intent, activityToken,
                fillTaskActivityToken, lastOverlayToken);
        verify(mTransaction).reparentActivityToTaskFragment(
                eq(overlayContainer.getTaskFragmentToken()), eq(activityToken));
    }

    /**
     * A simplified version of {@link SplitController#createOrUpdateOverlayTaskFragmentIfNeeded}
     */
    @Nullable
    private TaskFragmentContainer createOrUpdateOverlayTaskFragmentIfNeeded(@NonNull String tag) {
        final Bundle launchOptions = new Bundle();
        launchOptions.putString(KEY_OVERLAY_TAG, tag);
        return createOrUpdateOverlayTaskFragmentIfNeeded(tag, mActivity);
    }

    /**
     * A simplified version of {@link SplitController#createOrUpdateOverlayTaskFragmentIfNeeded}
     */
    @Nullable
    private TaskFragmentContainer createOrUpdateOverlayTaskFragmentIfNeeded(
            @NonNull String tag, @NonNull Activity activity) {
        final Bundle launchOptions = new Bundle();
        launchOptions.putString(KEY_OVERLAY_TAG, tag);
        return mSplitController.createOrUpdateOverlayTaskFragmentIfNeeded(mTransaction,
                launchOptions, mIntent, activity);
    }

    /** Creates a mock TaskFragment that has been registered and appeared in the organizer. */
    @NonNull
    private TaskFragmentContainer createMockTaskFragmentContainer(@NonNull Activity activity) {
        return createMockTaskFragmentContainer(activity, false /* isVisible */);
    }

    /** Creates a mock TaskFragment that has been registered and appeared in the organizer. */
    @NonNull
    private TaskFragmentContainer createMockTaskFragmentContainer(
            @NonNull Activity activity, boolean isVisible) {
        final TaskFragmentContainer container = mSplitController.newContainer(activity,
                activity.getTaskId());
        setupTaskFragmentInfo(container, activity, isVisible);
        return container;
    }

    @NonNull
    private TaskFragmentContainer createTestOverlayContainer(int taskId, @NonNull String tag) {
        return createTestOverlayContainer(taskId, tag, false /* isVisible */,
                true /* associateLaunchingActivity */);
    }

    @NonNull
    private TaskFragmentContainer createTestOverlayContainer(int taskId, @NonNull String tag,
            boolean isVisible) {
        return createTestOverlayContainer(taskId, tag, isVisible,
                true /* associateLaunchingActivity */);
    }

    @NonNull
    private TaskFragmentContainer createTestOverlayContainer(int taskId, @NonNull String tag,
                boolean isVisible, boolean associateLaunchingActivity) {
        return createTestOverlayContainer(taskId, tag, isVisible, associateLaunchingActivity,
                null /* launchingActivity */);
    }

    // TODO(b/243518738): add more test coverage on overlay container without activity association
    //  once we have use cases.
    @NonNull
    private TaskFragmentContainer createTestOverlayContainer(int taskId, @NonNull String tag,
            boolean isVisible, boolean associateLaunchingActivity,
            @Nullable Activity launchingActivity) {
        final Activity activity = launchingActivity != null
                ? launchingActivity : createMockActivity();
        TaskFragmentContainer overlayContainer = mSplitController.newContainer(
                null /* pendingAppearedActivity */, mIntent, activity, taskId,
                null /* pairedPrimaryContainer */, tag, Bundle.EMPTY,
                associateLaunchingActivity);
        setupTaskFragmentInfo(overlayContainer, createMockActivity(), isVisible);
        return overlayContainer;
    }

    private void setupTaskFragmentInfo(@NonNull TaskFragmentContainer container,
                                       @NonNull Activity activity,
                                       boolean isVisible) {
        final TaskFragmentInfo info = createMockTaskFragmentInfo(container, activity, isVisible);
        container.setInfo(mTransaction, info);
        mSplitPresenter.mFragmentInfos.put(container.getTaskFragmentToken(), info);
    }
}
