/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_IME_PLACEHOLDER;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;
import static android.window.DisplayAreaOrganizer.FEATURE_WINDOWED_MAGNIFICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.SizeCompatTests.prepareLimitedBounds;
import static com.android.server.wm.SizeCompatTests.prepareUnresizable;
import static com.android.server.wm.SizeCompatTests.rotateDisplay;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.Display;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the Dual DisplayAreaGroup device behavior.
 *
 * Build/Install/Run:
 *  atest WmTests:DualDisplayAreaGroupPolicyTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DualDisplayAreaGroupPolicyTest extends WindowTestsBase {
    private static final int FEATURE_FIRST_ROOT = FEATURE_VENDOR_FIRST;
    private static final int FEATURE_FIRST_TASK_CONTAINER = FEATURE_DEFAULT_TASK_CONTAINER;
    private static final int FEATURE_SECOND_ROOT = FEATURE_VENDOR_FIRST + 1;
    private static final int FEATURE_SECOND_TASK_CONTAINER = FEATURE_VENDOR_FIRST + 2;

    private DualDisplayContent mDisplay;
    private DisplayAreaGroup mFirstRoot;
    private DisplayAreaGroup mSecondRoot;
    private TaskDisplayArea mFirstTda;
    private TaskDisplayArea mSecondTda;
    private Task mFirstTask;
    private Task mSecondTask;
    private ActivityRecord mFirstActivity;
    private ActivityRecord mSecondActivity;

    @Before
    public void setUp() {
        // Let the Display to be created with the DualDisplay policy.
        final DisplayAreaPolicy.Provider policyProvider = new DualDisplayTestPolicyProvider();
        doReturn(policyProvider).when(mWm).getDisplayAreaPolicyProvider();

        // Display: 1920x1200 (landscape). First and second display are both 860x1200 (portrait).
        mDisplay = new DualDisplayContent.Builder(mAtm, 1920, 1200).build();
        mFirstRoot = mDisplay.mFirstRoot;
        mSecondRoot = mDisplay.mSecondRoot;
        mFirstTda = mDisplay.getTaskDisplayArea(FEATURE_FIRST_TASK_CONTAINER);
        mSecondTda = mDisplay.getTaskDisplayArea(FEATURE_SECOND_TASK_CONTAINER);
        mFirstTask = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(mFirstTda)
                .setCreateActivity(true)
                .build()
                .getBottomMostTask();
        mSecondTask = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(mSecondTda)
                .setCreateActivity(true)
                .build()
                .getBottomMostTask();
        mFirstActivity = mFirstTask.getTopNonFinishingActivity();
        mSecondActivity = mSecondTask.getTopNonFinishingActivity();

        spyOn(mDisplay);
        spyOn(mFirstRoot);
        spyOn(mSecondRoot);
    }

    @Test
    public void testNotIgnoreOrientationRequest_differentOrientationFromDisplay_reversesRequest() {
        mFirstRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_PORTRAIT);
        assertThat(mFirstActivity.getConfiguration().orientation).isEqualTo(ORIENTATION_LANDSCAPE);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
        assertThat(mFirstActivity.getConfiguration().orientation).isEqualTo(ORIENTATION_PORTRAIT);
    }

    @Test
    public void testNotIgnoreOrientationRequest_onlyRespectsFocusedTaskDisplayArea() {
        mFirstRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        // Second TDA is not focused, so Display won't get the request
        prepareUnresizable(mSecondActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);

        // First TDA is focused, so Display gets the request
        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    public void testIgnoreOrientationRequest_displayDoesNotReceiveOrientationChange() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);

        verify(mFirstRoot).onDescendantOrientationChanged(any());
        verify(mDisplay, never()).onDescendantOrientationChanged(any());
    }

    @Test
    public void testLaunchPortraitApp_fillsDisplayAreaGroup() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_PORTRAIT);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect taskBounds = new Rect(mFirstTask.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        // DAG is portrait (860x1200), so Task and Activity fill DAG.
        assertThat(mFirstActivity.isLetterboxedForFixedOrientationAndAspectRatio()).isFalse();
        assertThat(mFirstActivity.inSizeCompatMode()).isFalse();
        assertThat(taskBounds).isEqualTo(dagBounds);
        assertThat(activityBounds).isEqualTo(taskBounds);
    }

    @Test
    public void testLaunchPortraitApp_sizeCompatAfterRotation() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_PORTRAIT);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        rotateDisplay(mDisplay, ROTATION_90);
        final Rect newDagBounds = new Rect(mFirstRoot.getBounds());
        final Rect newTaskBounds = new Rect(mFirstTask.getBounds());
        final Rect activitySizeCompatBounds = new Rect(mFirstActivity.getBounds());
        final Rect activityConfigBounds =
                new Rect(mFirstActivity.getConfiguration().windowConfiguration.getBounds());

        // DAG is landscape (1200x860), no fixed orientation letterbox
        assertThat(mFirstActivity.isLetterboxedForFixedOrientationAndAspectRatio()).isFalse();
        assertThat(mFirstActivity.inSizeCompatMode()).isTrue();
        assertThat(newDagBounds.width()).isEqualTo(dagBounds.height());
        assertThat(newDagBounds.height()).isEqualTo(dagBounds.width());
        assertThat(newTaskBounds).isEqualTo(newDagBounds);

        // Activity config bounds is unchanged, size compat bounds is (860x[860x860/1200=616])
        assertThat(mFirstActivity.getSizeCompatScale()).isLessThan(1f);
        assertThat(activityConfigBounds.width()).isEqualTo(activityBounds.width());
        assertThat(activityConfigBounds.height()).isEqualTo(activityBounds.height());
        assertThat(activitySizeCompatBounds.height()).isEqualTo(newTaskBounds.height());
        assertThat(activitySizeCompatBounds.width()).isEqualTo(
                newTaskBounds.height() * newTaskBounds.height() / newTaskBounds.width());
    }

    @Test
    public void testLaunchLandscapeApp_activityIsLetterboxForFixedOrientationInDisplayAreaGroup() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect taskBounds = new Rect(mFirstTask.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        // DAG is portrait (860x1200), and activity is letterboxed for fixed orientation
        // (860x[860x860/1200=616]). Task fills DAG.
        assertThat(mFirstActivity.isLetterboxedForFixedOrientationAndAspectRatio()).isTrue();
        assertThat(mFirstActivity.inSizeCompatMode()).isFalse();
        assertThat(taskBounds).isEqualTo(dagBounds);
        assertThat(activityBounds.width()).isEqualTo(dagBounds.width());
        assertThat(activityBounds.height())
                .isEqualTo(dagBounds.width() * dagBounds.width() / dagBounds.height());
    }

    @Test
    public void testLaunchLandscapeApp_fixedOrientationLetterboxBecomesSizeCompatAfterRotation() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        rotateDisplay(mDisplay, ROTATION_90);
        final Rect newDagBounds = new Rect(mFirstRoot.getBounds());
        final Rect newTaskBounds = new Rect(mFirstTask.getBounds());
        final Rect newActivityBounds = new Rect(mFirstActivity.getBounds());

        // DAG is landscape (1200x860), no fixed orientation letterbox
        assertThat(mFirstActivity.isLetterboxedForFixedOrientationAndAspectRatio()).isFalse();
        assertThat(mFirstActivity.inSizeCompatMode()).isTrue();
        assertThat(newDagBounds.width()).isEqualTo(dagBounds.height());
        assertThat(newDagBounds.height()).isEqualTo(dagBounds.width());
        assertThat(newTaskBounds).isEqualTo(newDagBounds);

        // Because we don't scale up, there is no size compat bounds and app bounds is the same as
        // the previous bounds.
        assertThat(mFirstActivity.hasSizeCompatBounds()).isFalse();
        assertThat(newActivityBounds.width()).isEqualTo(activityBounds.width());
        assertThat(newActivityBounds.height()).isEqualTo(activityBounds.height());
    }

    @Test
    public void testPlaceImeContainer_reparentToTargetDisplayAreaGroup() {
        setupImeWindow();
        final DisplayArea.Tokens imeContainer = mDisplay.getImeContainer();
        final WindowToken imeToken = tokenOfType(TYPE_INPUT_METHOD);

        // By default, the ime container is attached to DC as defined in DAPolicy.
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(mDisplay);
        assertThat(mDisplay.findAreaForTokenInLayer(imeToken)).isEqualTo(imeContainer);

        final WindowState firstActivityWin =
                createWindow(null /* parent */, TYPE_APPLICATION_STARTING, mFirstActivity,
                        "firstActivityWin");
        spyOn(firstActivityWin);
        final WindowState secondActivityWin =
                createWindow(null /* parent */, TYPE_APPLICATION_STARTING, mSecondActivity,
                        "firstActivityWin");
        spyOn(secondActivityWin);

        // firstActivityWin should be the target
        doReturn(true).when(firstActivityWin).canBeImeTarget();
        doReturn(false).when(secondActivityWin).canBeImeTarget();

        WindowState imeTarget = mDisplay.computeImeTarget(true /* updateImeTarget */);

        assertThat(imeTarget).isEqualTo(firstActivityWin);
        verify(mFirstRoot).placeImeContainer(imeContainer);
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(mFirstRoot);
        assertThat(imeContainer.getParent().asDisplayArea().mFeatureId)
                .isEqualTo(FEATURE_IME_PLACEHOLDER);
        assertThat(mDisplay.findAreaForTokenInLayer(imeToken)).isNull();
        assertThat(mFirstRoot.findAreaForTokenInLayer(imeToken)).isEqualTo(imeContainer);
        assertThat(mSecondRoot.findAreaForTokenInLayer(imeToken)).isNull();

        // secondActivityWin should be the target
        doReturn(false).when(firstActivityWin).canBeImeTarget();
        doReturn(true).when(secondActivityWin).canBeImeTarget();

        imeTarget = mDisplay.computeImeTarget(true /* updateImeTarget */);

        assertThat(imeTarget).isEqualTo(secondActivityWin);
        verify(mSecondRoot).placeImeContainer(imeContainer);
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(mSecondRoot);
        assertThat(imeContainer.getParent().asDisplayArea().mFeatureId)
                .isEqualTo(FEATURE_IME_PLACEHOLDER);
        assertThat(mDisplay.findAreaForTokenInLayer(imeToken)).isNull();
        assertThat(mFirstRoot.findAreaForTokenInLayer(imeToken)).isNull();
        assertThat(mSecondRoot.findAreaForTokenInLayer(imeToken)).isEqualTo(imeContainer);
    }

    @Test
    public void testPlaceImeContainer_hidesImeWhenParentChanges() {
        setupImeWindow();
        final DisplayArea.Tokens imeContainer = mDisplay.getImeContainer();
        final WindowToken imeToken = tokenOfType(TYPE_INPUT_METHOD);
        final WindowState firstActivityWin =
                createWindow(null /* parent */, TYPE_APPLICATION_STARTING, mFirstActivity,
                        "firstActivityWin");
        spyOn(firstActivityWin);
        final WindowState secondActivityWin =
                createWindow(null /* parent */, TYPE_APPLICATION_STARTING, mSecondActivity,
                        "secondActivityWin");
        spyOn(secondActivityWin);

        // firstActivityWin should be the target
        doReturn(true).when(firstActivityWin).canBeImeTarget();
        doReturn(false).when(secondActivityWin).canBeImeTarget();

        WindowState imeTarget = mDisplay.computeImeTarget(true /* updateImeTarget */);
        assertThat(imeTarget).isEqualTo(firstActivityWin);
        verify(mFirstRoot).placeImeContainer(imeContainer);

        // secondActivityWin should be the target
        doReturn(false).when(firstActivityWin).canBeImeTarget();
        doReturn(true).when(secondActivityWin).canBeImeTarget();

        spyOn(mDisplay.mInputMethodWindow);
        imeTarget = mDisplay.computeImeTarget(true /* updateImeTarget */);

        assertThat(imeTarget).isEqualTo(secondActivityWin);
        verify(mSecondRoot).placeImeContainer(imeContainer);
        // verify hide() was called on InputMethodWindow.
        verify(mDisplay.mInputMethodWindow).hide(false /* doAnimation */, false /* requestAnim */);
    }

    @Test
    public void testResizableFixedOrientationApp_fixedOrientationLetterboxing() {
        mFirstRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);

        // Launch portrait on first DAG
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);
        prepareLimitedBounds(mFirstActivity, SCREEN_ORIENTATION_PORTRAIT,
                false /* isUnresizable */);

        // Display in landscape (as opposite to DAG), first DAG and activity in portrait
        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
        assertThat(mFirstRoot.getConfiguration().orientation).isEqualTo(ORIENTATION_PORTRAIT);
        assertThat(mFirstActivity.getConfiguration().orientation).isEqualTo(ORIENTATION_PORTRAIT);
        assertThat(mFirstActivity.isLetterboxedForFixedOrientationAndAspectRatio()).isFalse();
        assertThat(mFirstActivity.inSizeCompatMode()).isFalse();

        // Launch portrait on second DAG
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mSecondTda);
        prepareLimitedBounds(mSecondActivity, SCREEN_ORIENTATION_LANDSCAPE,
                false /* isUnresizable */);

        // Display in portrait (as opposite to DAG), first DAG and activity in landscape
        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_PORTRAIT);
        assertThat(mSecondRoot.getConfiguration().orientation).isEqualTo(ORIENTATION_LANDSCAPE);
        assertThat(mSecondActivity.getConfiguration().orientation).isEqualTo(ORIENTATION_LANDSCAPE);
        assertThat(mSecondActivity.isLetterboxedForFixedOrientationAndAspectRatio()).isFalse();
        assertThat(mSecondActivity.inSizeCompatMode()).isFalse();

        // First activity is letterboxed in portrait as requested.
        assertThat(mFirstRoot.getConfiguration().orientation).isEqualTo(ORIENTATION_LANDSCAPE);
        assertThat(mFirstActivity.getConfiguration().orientation).isEqualTo(ORIENTATION_PORTRAIT);
        assertThat(mFirstActivity.isLetterboxedForFixedOrientationAndAspectRatio()).isTrue();
        assertThat(mFirstActivity.inSizeCompatMode()).isFalse();

    }

    private void setupImeWindow() {
        final WindowState imeWindow = createWindow(null /* parent */,
                TYPE_INPUT_METHOD, mDisplay, "mImeWindow");
        imeWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        mDisplay.mInputMethodWindow = imeWindow;
    }

    private WindowToken tokenOfType(int type) {
        return new WindowToken.Builder(mWm, new Binder(), type)
                .setDisplayContent(mDisplay).build();
    }

    /** Display with two {@link DisplayAreaGroup}. Each of them take half of the screen. */
    static class DualDisplayContent extends TestDisplayContent {
        final DisplayAreaGroup mFirstRoot;
        final DisplayAreaGroup mSecondRoot;
        final Rect mLastDisplayBounds;

        /** Please use the {@link Builder} to create. */
        DualDisplayContent(RootWindowContainer rootWindowContainer,
                Display display) {
            super(rootWindowContainer, display);

            mFirstRoot = getGroupRoot(FEATURE_FIRST_ROOT);
            mSecondRoot = getGroupRoot(FEATURE_SECOND_ROOT);
            mLastDisplayBounds = new Rect(getBounds());
            updateDisplayAreaGroupBounds();
        }

        DisplayAreaGroup getGroupRoot(int rootFeatureId) {
            DisplayArea da = getDisplayArea(rootFeatureId);
            assertThat(da).isInstanceOf(DisplayAreaGroup.class);
            return (DisplayAreaGroup) da;
        }

        TaskDisplayArea getTaskDisplayArea(int tdaFeatureId) {
            DisplayArea da = getDisplayArea(tdaFeatureId);
            assertThat(da).isInstanceOf(TaskDisplayArea.class);
            return (TaskDisplayArea) da;
        }

        DisplayArea getDisplayArea(int featureId) {
            final DisplayArea displayArea =
                    getItemFromDisplayAreas(da -> da.mFeatureId == featureId ? da : null);
            assertThat(displayArea).isNotNull();
            return displayArea;
        }

        @Override
        public void onConfigurationChanged(Configuration newParentConfig) {
            super.onConfigurationChanged(newParentConfig);

            final Rect curBounds = getBounds();
            if (mLastDisplayBounds != null && !mLastDisplayBounds.equals(curBounds)) {
                mLastDisplayBounds.set(curBounds);
                updateDisplayAreaGroupBounds();
            }
        }

        /** Updates first and second {@link DisplayAreaGroup} to take half of the screen. */
        private void updateDisplayAreaGroupBounds() {
            if (mFirstRoot == null || mSecondRoot == null) {
                return;
            }

            final Rect bounds = mLastDisplayBounds;
            Rect groupBounds1, groupBounds2;
            if (bounds.width() >= bounds.height()) {
                groupBounds1 = new Rect(bounds.left, bounds.top,
                        (bounds.right + bounds.left) / 2, bounds.bottom);

                groupBounds2 = new Rect((bounds.right + bounds.left) / 2, bounds.top,
                        bounds.right, bounds.bottom);
            } else {
                groupBounds1 = new Rect(bounds.left, bounds.top,
                        bounds.right, (bounds.top + bounds.bottom) / 2);

                groupBounds2 = new Rect(bounds.left,
                        (bounds.top + bounds.bottom) / 2, bounds.right, bounds.bottom);
            }
            mFirstRoot.setBounds(groupBounds1);
            mSecondRoot.setBounds(groupBounds2);
        }

        static class Builder extends TestDisplayContent.Builder {

            Builder(ActivityTaskManagerService service, int width, int height) {
                super(service, width, height);
            }

            @Override
            TestDisplayContent createInternal(Display display) {
                return new DualDisplayContent(mService.mRootWindowContainer, display);
            }

            DualDisplayContent build() {
                return (DualDisplayContent) super.build();
            }
        }
    }

    /** Policy to create a dual {@link DisplayAreaGroup} policy in test. */
    static class DualDisplayTestPolicyProvider implements DisplayAreaPolicy.Provider {

        @Override
        public DisplayAreaPolicy instantiate(WindowManagerService wmService, DisplayContent content,
                RootDisplayArea root, DisplayArea.Tokens imeContainer) {
            // Root
            // Include FEATURE_WINDOWED_MAGNIFICATION because it will be used as the screen rotation
            // layer
            DisplayAreaPolicyBuilder.HierarchyBuilder rootHierarchy =
                    new DisplayAreaPolicyBuilder.HierarchyBuilder(root)
                            .setImeContainer(imeContainer)
                            .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(
                                    wmService.mPolicy,
                                    "WindowedMagnification", FEATURE_WINDOWED_MAGNIFICATION)
                                    .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                                    .except(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                                    .setNewDisplayAreaSupplier(DisplayArea.Dimmable::new)
                                    .build())
                            .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(
                                    wmService.mPolicy,
                                    "ImePlaceholder", FEATURE_IME_PLACEHOLDER)
                                    .and(TYPE_INPUT_METHOD, TYPE_INPUT_METHOD_DIALOG)
                                    .build());

            // First
            final RootDisplayArea firstRoot = new DisplayAreaGroup(wmService, "FirstRoot",
                    FEATURE_FIRST_ROOT);
            final TaskDisplayArea firstTaskDisplayArea = new TaskDisplayArea(content, wmService,
                    "FirstTaskDisplayArea", FEATURE_FIRST_TASK_CONTAINER);
            final List<TaskDisplayArea> firstTdaList = new ArrayList<>();
            firstTdaList.add(firstTaskDisplayArea);
            DisplayAreaPolicyBuilder.HierarchyBuilder firstHierarchy =
                    new DisplayAreaPolicyBuilder.HierarchyBuilder(firstRoot)
                            .setTaskDisplayAreas(firstTdaList)
                            .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(
                                    wmService.mPolicy,
                                    "ImePlaceholder", FEATURE_IME_PLACEHOLDER)
                                    .and(TYPE_INPUT_METHOD, TYPE_INPUT_METHOD_DIALOG)
                                    .build());

            // Second
            final RootDisplayArea secondRoot = new DisplayAreaGroup(wmService, "SecondRoot",
                    FEATURE_SECOND_ROOT);
            final TaskDisplayArea secondTaskDisplayArea = new TaskDisplayArea(content, wmService,
                    "SecondTaskDisplayArea", FEATURE_SECOND_TASK_CONTAINER);
            final List<TaskDisplayArea> secondTdaList = new ArrayList<>();
            secondTdaList.add(secondTaskDisplayArea);
            DisplayAreaPolicyBuilder.HierarchyBuilder secondHierarchy =
                    new DisplayAreaPolicyBuilder.HierarchyBuilder(secondRoot)
                            .setTaskDisplayAreas(secondTdaList)
                            .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(
                                    wmService.mPolicy,
                                    "ImePlaceholder", FEATURE_IME_PLACEHOLDER)
                                    .and(TYPE_INPUT_METHOD, TYPE_INPUT_METHOD_DIALOG)
                                    .build());

            return new DisplayAreaPolicyBuilder()
                    .setRootHierarchy(rootHierarchy)
                    .addDisplayAreaGroupHierarchy(firstHierarchy)
                    .addDisplayAreaGroupHierarchy(secondHierarchy)
                    .build(wmService);
        }
    }
}
