/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.wm.LaunchParamsController.LaunchParams;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Tests for the {@link TaskDisplayArea} container.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskDisplayAreaTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskDisplayAreaTests extends WindowTestsBase {

    @Test
    public void getLaunchRootTask_checksLaunchAdjacentFlagRoot() {
        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        rootTask.mCreatedByOrganizer = true;
        final Task adjacentRootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        adjacentRootTask.mCreatedByOrganizer = true;
        final TaskDisplayArea taskDisplayArea = rootTask.getDisplayArea();
        adjacentRootTask.setAdjacentTaskFragment(rootTask);

        taskDisplayArea.setLaunchAdjacentFlagRootTask(adjacentRootTask);
        Task actualRootTask = taskDisplayArea.getLaunchRootTask(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, null /* options */,
                null /* sourceTask */, FLAG_ACTIVITY_LAUNCH_ADJACENT);
        assertSame(adjacentRootTask, actualRootTask.getRootTask());

        taskDisplayArea.setLaunchAdjacentFlagRootTask(null);
        actualRootTask = taskDisplayArea.getLaunchRootTask(WINDOWING_MODE_UNDEFINED,
                ACTIVITY_TYPE_STANDARD, null /* options */, null /* sourceTask */,
                FLAG_ACTIVITY_LAUNCH_ADJACENT);
        assertNull(actualRootTask);
    }

    @Test
    public void getLaunchRootTask_checksFocusedRootTask() {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask = createTaskWithActivity(
                taskDisplayArea,
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, ON_TOP, true);
        rootTask.mCreatedByOrganizer = true;

        final Task adjacentRootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        adjacentRootTask.mCreatedByOrganizer = true;
        adjacentRootTask.setAdjacentTaskFragment(rootTask);

        taskDisplayArea.setLaunchRootTask(rootTask,
                new int[]{WINDOWING_MODE_MULTI_WINDOW}, new int[]{ACTIVITY_TYPE_STANDARD});

        Task actualRootTask = taskDisplayArea.getLaunchRootTask(
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, null /* options */,
                null /* sourceTask */, 0 /*launchFlags*/);
        assertTrue(actualRootTask.isFocusedRootTaskOnDisplay());
    }

    @Test
    public void getLaunchRootTask_fromLaunchAdjacentFlagRoot_checksAdjacentRoot() {
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        rootTask.mCreatedByOrganizer = true;
        final Task adjacentRootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        adjacentRootTask.mCreatedByOrganizer = true;
        createActivityRecord(adjacentRootTask);
        final TaskDisplayArea taskDisplayArea = rootTask.getDisplayArea();
        adjacentRootTask.setAdjacentTaskFragment(rootTask);

        taskDisplayArea.setLaunchAdjacentFlagRootTask(adjacentRootTask);
        final Task actualRootTask = taskDisplayArea.getLaunchRootTask(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, null /* options */,
                adjacentRootTask /* sourceTask */, FLAG_ACTIVITY_LAUNCH_ADJACENT);

        assertSame(rootTask, actualRootTask.getRootTask());
    }

    @Test
    public void getOrCreateLaunchRootRespectsResolvedWindowingMode() {
        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        rootTask.mCreatedByOrganizer = true;
        final TaskDisplayArea taskDisplayArea = rootTask.getDisplayArea();
        taskDisplayArea.setLaunchRootTask(
                rootTask, new int[]{WINDOWING_MODE_FREEFORM}, new int[]{ACTIVITY_TYPE_STANDARD});

        final Task candidateRootTask = createTask(
                mDisplayContent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        final LaunchParams launchParams = new LaunchParams();
        launchParams.mWindowingMode = WINDOWING_MODE_FREEFORM;

        final Task actualRootTask = taskDisplayArea.getOrCreateRootTask(
                activity, null /* options */, candidateRootTask, null /* sourceTask */,
                launchParams, 0 /* launchFlags */, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertSame(rootTask, actualRootTask.getRootTask());
    }

    @Test
    public void getOrCreateLaunchRootUsesActivityOptionsWindowingMode() {
        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        rootTask.mCreatedByOrganizer = true;
        final TaskDisplayArea taskDisplayArea = rootTask.getDisplayArea();
        taskDisplayArea.setLaunchRootTask(
                rootTask, new int[]{WINDOWING_MODE_FREEFORM}, new int[]{ACTIVITY_TYPE_STANDARD});

        final Task candidateRootTask = createTask(
                mDisplayContent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

        final Task actualRootTask = taskDisplayArea.getOrCreateRootTask(
                activity, options, candidateRootTask, null /* sourceTask */,
                null /* launchParams */, 0 /* launchFlags */, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        assertSame(rootTask, actualRootTask.getRootTask());
    }

    @Test
    public void testActivityWithZBoost_taskDisplayAreaDoesNotMoveUp() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        task.addChild(activity, 0 /* addPos */);
        final TaskDisplayArea taskDisplayArea = activity.getDisplayArea();
        activity.mNeedsAnimationBoundsLayer = true;
        activity.mNeedsZBoost = true;
        spyOn(taskDisplayArea.mSurfaceAnimator);

        mDisplayContent.assignChildLayers(mTransaction);

        assertThat(activity.needsZBoost()).isTrue();
        assertThat(taskDisplayArea.needsZBoost()).isFalse();
        verify(taskDisplayArea.mSurfaceAnimator, never()).setLayer(eq(mTransaction), anyInt());
    }

    @Test
    public void testRootTaskPositionChildAt() {
        Task pinnedTask = createTask(
                mDisplayContent, WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);
        // Root task should contain visible app window to be considered visible.
        assertFalse(pinnedTask.isVisible());
        final ActivityRecord pinnedApp = createNonAttachedActivityRecord(mDisplayContent);
        pinnedTask.addChild(pinnedApp, 0 /* addPos */);
        assertTrue(pinnedTask.isVisible());

        // Test that always-on-top root task can't be moved to position other than top.
        final Task rootTask1 = createTask(mDisplayContent);
        final Task rootTask2 = createTask(mDisplayContent);

        final WindowContainer taskContainer = rootTask1.getParent();

        final int rootTask1Pos = taskContainer.mChildren.indexOf(rootTask1);
        final int rootTask2Pos = taskContainer.mChildren.indexOf(rootTask2);
        final int pinnedTaskPos = taskContainer.mChildren.indexOf(pinnedTask);
        assertThat(pinnedTaskPos).isGreaterThan(rootTask2Pos);
        assertThat(rootTask2Pos).isGreaterThan(rootTask1Pos);

        taskContainer.positionChildAt(WindowContainer.POSITION_BOTTOM, pinnedTask, false);
        assertEquals(taskContainer.mChildren.get(rootTask1Pos), rootTask1);
        assertEquals(taskContainer.mChildren.get(rootTask2Pos), rootTask2);
        assertEquals(taskContainer.mChildren.get(pinnedTaskPos), pinnedTask);

        taskContainer.positionChildAt(1, pinnedTask, false);
        assertEquals(taskContainer.mChildren.get(rootTask1Pos), rootTask1);
        assertEquals(taskContainer.mChildren.get(rootTask2Pos), rootTask2);
        assertEquals(taskContainer.mChildren.get(pinnedTaskPos), pinnedTask);
    }

    @Test
    public void testRootTaskPositionBelowPinnedRootTask() {
        Task pinnedTask = createTask(
                mDisplayContent, WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);
        // Root task should contain visible app window to be considered visible.
        assertFalse(pinnedTask.isVisible());
        final ActivityRecord pinnedApp = createNonAttachedActivityRecord(mDisplayContent);
        pinnedTask.addChild(pinnedApp, 0 /* addPos */);
        assertTrue(pinnedTask.isVisible());

        // Test that no root task can be above pinned root task.
        final Task rootTask1 = createTask(mDisplayContent);

        final WindowContainer taskContainer = rootTask1.getParent();

        final int rootTaskPos = taskContainer.mChildren.indexOf(rootTask1);
        final int pinnedTaskPos = taskContainer.mChildren.indexOf(pinnedTask);
        assertThat(pinnedTaskPos).isGreaterThan(rootTaskPos);

        taskContainer.positionChildAt(WindowContainer.POSITION_TOP, rootTask1, false);
        assertEquals(taskContainer.mChildren.get(rootTaskPos), rootTask1);
        assertEquals(taskContainer.mChildren.get(pinnedTaskPos), pinnedTask);

        taskContainer.positionChildAt(taskContainer.mChildren.size() - 1, rootTask1, false);
        assertEquals(taskContainer.mChildren.get(rootTaskPos), rootTask1);
        assertEquals(taskContainer.mChildren.get(pinnedTaskPos), pinnedTask);
    }

    @Test
    public void testDisplayPositionWithPinnedRootTask() {
        // Make sure the display is trusted display which capable to move the root task to top.
        spyOn(mDisplayContent);
        doReturn(true).when(mDisplayContent).isTrusted();

        // Allow child root task to move to top.
        mDisplayContent.mDontMoveToTop = false;

        // The display contains pinned root task that was added in {@link #setUp}.
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);

        // Add another display at top.
        mWm.mRoot.positionChildAt(WindowContainer.POSITION_TOP, createNewDisplay(),
                false /* includingParents */);

        // Move the task of {@code mDisplayContent} to top.
        rootTask.positionChildAt(WindowContainer.POSITION_TOP, task, true /* includingParents */);
        final int indexOfDisplayWithPinnedRootTask = mWm.mRoot.mChildren.indexOf(mDisplayContent);

        assertEquals("The testing DisplayContent should be moved to top with task",
                mWm.mRoot.getChildCount() - 1, indexOfDisplayWithPinnedRootTask);
    }

    @Test
    public void testMovingChildTaskOnTop() {
        // Make sure the display is trusted display which capable to move the root task to top.
        spyOn(mDisplayContent);
        doReturn(true).when(mDisplayContent).isTrusted();

        // Allow child root task to move to top.
        mDisplayContent.mDontMoveToTop = false;

        // The display contains pinned root task that was added in {@link #setUp}.
        Task rootTask = createTask(mDisplayContent);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);

        // Add another display at top.
        mWm.mRoot.positionChildAt(WindowContainer.POSITION_TOP, createNewDisplay(),
                false /* includingParents */);

        // Ensure that original display ({@code mDisplayContent}) is not on top.
        assertEquals("Testing DisplayContent should not be on the top",
                mWm.mRoot.getChildCount() - 2, mWm.mRoot.mChildren.indexOf(mDisplayContent));

        // Move the task of {@code mDisplayContent} to top.
        rootTask.positionChildAt(WindowContainer.POSITION_TOP, task, true /* includingParents */);

        // Ensure that original display ({@code mDisplayContent}) is now on the top.
        assertEquals("The testing DisplayContent should be moved to top with task",
                mWm.mRoot.getChildCount() - 1, mWm.mRoot.mChildren.indexOf(mDisplayContent));
    }

    @Test
    public void testDontMovingChildTaskOnTop() {
        // Make sure the display is trusted display which capable to move the root task to top.
        spyOn(mDisplayContent);
        doReturn(true).when(mDisplayContent).isTrusted();

        // Allow child root task to move to top.
        mDisplayContent.mDontMoveToTop = true;

        // The display contains pinned root task that was added in {@link #setUp}.
        Task rootTask = createTask(mDisplayContent);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);

        // Add another display at top.
        mWm.mRoot.positionChildAt(WindowContainer.POSITION_TOP, createNewDisplay(),
                false /* includingParents */);

        // Ensure that original display ({@code mDisplayContent}) is not on top.
        assertEquals("Testing DisplayContent should not be on the top",
                mWm.mRoot.getChildCount() - 2, mWm.mRoot.mChildren.indexOf(mDisplayContent));

        // Try moving the task of {@code mDisplayContent} to top.
        rootTask.positionChildAt(WindowContainer.POSITION_TOP, task, true /* includingParents */);

        // Ensure that original display ({@code mDisplayContent}) hasn't moved and is not
        // on the top.
        assertEquals("The testing DisplayContent should not be moved to top with task",
                mWm.mRoot.getChildCount() - 2, mWm.mRoot.mChildren.indexOf(mDisplayContent));
    }

    @Test
    public void testReuseTaskAsRootTask() {
        final Task candidateTask = createTask(mDisplayContent);
        List<Integer> activityTypesWithReusableRootTask = List.of(ACTIVITY_TYPE_STANDARD,
                ACTIVITY_TYPE_RECENTS);
        for (Integer type : activityTypesWithReusableRootTask) {
            assertGetOrCreateRootTask(WINDOWING_MODE_FULLSCREEN, type, candidateTask,
                    true /* reuseCandidate */);
            assertGetOrCreateRootTask(WINDOWING_MODE_UNDEFINED, type, candidateTask,
                    true /* reuseCandidate */);
            assertGetOrCreateRootTask(WINDOWING_MODE_FREEFORM, type, candidateTask,
                    true /* reuseCandidate */);
            assertGetOrCreateRootTask(WINDOWING_MODE_MULTI_WINDOW, type, candidateTask,
                    true /* reuseCandidate */);
            assertGetOrCreateRootTask(WINDOWING_MODE_PINNED, type, candidateTask,
                    true /* reuseCandidate */);
        }

        final int windowingMode = WINDOWING_MODE_FULLSCREEN;
        assertGetOrCreateRootTask(windowingMode, ACTIVITY_TYPE_HOME, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateRootTask(windowingMode, ACTIVITY_TYPE_ASSISTANT, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateRootTask(windowingMode, ACTIVITY_TYPE_DREAM, candidateTask,
                false /* reuseCandidate */);
    }

    @Test
    public void testGetOrientation_nonResizableHomeTaskWithHomeActivityPendingVisibilityChange() {
        final RootWindowContainer rootWindowContainer = mWm.mAtmService.mRootWindowContainer;
        final TaskDisplayArea defaultTaskDisplayArea =
                rootWindowContainer.getDefaultTaskDisplayArea();

        final Task rootHomeTask = defaultTaskDisplayArea.getRootHomeTask();
        rootHomeTask.mResizeMode = RESIZE_MODE_UNRESIZEABLE;

        final Task primarySplitTask = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(defaultTaskDisplayArea)
                .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW)
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .setOnTop(true)
                .setCreateActivity(true)
                .build();
        ActivityRecord primarySplitActivity = primarySplitTask.getTopNonFinishingActivity();
        assertNotNull(primarySplitActivity);
        primarySplitActivity.setState(RESUMED,
                "testGetOrientation_nonResizableHomeTaskWithHomeActivityPendingVisibilityChange");

        ActivityRecord homeActivity = rootHomeTask.getTopNonFinishingActivity();
        if (homeActivity == null) {
            homeActivity = new ActivityBuilder(mWm.mAtmService)
                    .setParentTask(rootHomeTask).setCreateTask(true).build();
        }
        homeActivity.setVisible(false);
        homeActivity.setVisibleRequested(true);
        assertFalse(rootHomeTask.isVisible());

        assertEquals(defaultTaskDisplayArea.getOrientation(), rootHomeTask.getOrientation());
    }

    @Test
    public void testIsLastFocused() {
        final TaskDisplayArea firstTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                mDisplayContent, mRootWindowContainer.mWmService, "TestTaskDisplayArea",
                FEATURE_VENDOR_FIRST);
        final Task firstRootTask = firstTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task secondRootTask = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(firstRootTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(secondRootTask).build();

        // Activity on TDA1 is focused
        mDisplayContent.setFocusedApp(firstActivity);

        final int testOrientation = SCREEN_ORIENTATION_PORTRAIT;

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();

        // No focused app, TDA1 is still recorded as last focused.
        mDisplayContent.setFocusedApp(null);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();

        // Activity on TDA2 is focused
        mDisplayContent.setFocusedApp(secondActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
    }

    @Test
    public void testCanSpecifyOrientation() {
        final TaskDisplayArea firstTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                mDisplayContent, mRootWindowContainer.mWmService, "TestTaskDisplayArea",
                FEATURE_VENDOR_FIRST);
        final Task firstRootTask = firstTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task secondRootTask = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(firstRootTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(secondRootTask).build();
        firstTaskDisplayArea.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        secondTaskDisplayArea.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);

        final int testOrientation = SCREEN_ORIENTATION_PORTRAIT;

        // Activity on TDA1 is focused, but TDA1 cannot specify orientation because
        // ignoreOrientationRequest is true
        // Activity on TDA2 has ignoreOrientationRequest false but it doesn't have focus so it
        // cannot specify orientation
        mDisplayContent.setFocusedApp(firstActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();

        // Activity on TDA1 is not focused, and so it cannot specify orientation
        // Activity on TDA2 is focused, and it can specify orientation because
        // ignoreOrientationRequest is false
        mDisplayContent.setFocusedApp(secondActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
    }

    @Test
    public void testCanSpecifyOrientationNoSensor() {
        final TaskDisplayArea firstTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                mDisplayContent, mRootWindowContainer.mWmService, "TestTaskDisplayArea",
                FEATURE_VENDOR_FIRST);
        final Task firstRootTask = firstTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task secondRootTask = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(firstRootTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(secondRootTask).build();
        firstTaskDisplayArea.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        secondTaskDisplayArea.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);

        final int testOrientation = SCREEN_ORIENTATION_NOSENSOR;

        // ignoreOrientationRequest is always false for SCREEN_ORIENTATION_NOSENSOR so
        // only the TDAs with focus can specify orientations
        mDisplayContent.setFocusedApp(firstActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();

        mDisplayContent.setFocusedApp(secondActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
    }

    @Test
    public void testCanSpecifyOrientationLocked() {
        final TaskDisplayArea firstTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                mDisplayContent, mRootWindowContainer.mWmService, "TestTaskDisplayArea",
                FEATURE_VENDOR_FIRST);
        final Task firstRootTask = firstTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task secondRootTask = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(firstRootTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(secondRootTask).build();
        firstTaskDisplayArea.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        secondTaskDisplayArea.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);

        final int testOrientation = SCREEN_ORIENTATION_LOCKED;

        // ignoreOrientationRequest is always false for SCREEN_ORIENTATION_NOSENSOR so
        // only the TDAs with focus can specify orientations
        mDisplayContent.setFocusedApp(firstActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();

        mDisplayContent.setFocusedApp(secondActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation(testOrientation)).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation(testOrientation)).isTrue();
    }

    @Test
    @UseTestDisplay
    public void testRemove_reparentToDefault() {
        final Task task = createTask(mDisplayContent);
        final TaskDisplayArea displayArea = task.getDisplayArea();
        displayArea.remove();
        assertTrue(displayArea.isRemoved());
        assertFalse(displayArea.hasChild());

        final RootWindowContainer rootWindowContainer = mWm.mAtmService.mRootWindowContainer;
        final TaskDisplayArea defaultTaskDisplayArea =
                rootWindowContainer.getDefaultTaskDisplayArea();
        assertTrue(defaultTaskDisplayArea.mChildren.contains(task));
    }

    @Test
    @UseTestDisplay
    public void testRemove_rootTaskCreatedByOrganizer() {
        final Task task = createTask(mDisplayContent);
        task.mCreatedByOrganizer = true;
        final TaskDisplayArea displayArea = task.getDisplayArea();
        displayArea.remove();
        assertTrue(displayArea.isRemoved());
        assertFalse(displayArea.hasChild());

        final RootWindowContainer rootWindowContainer = mWm.mAtmService.mRootWindowContainer;
        final TaskDisplayArea defaultTaskDisplayArea =
                rootWindowContainer.getDefaultTaskDisplayArea();
        assertFalse(defaultTaskDisplayArea.mChildren.contains(task));
    }

    private void assertGetOrCreateRootTask(int windowingMode, int activityType, Task candidateTask,
            boolean reuseCandidate) {
        final TaskDisplayArea taskDisplayArea = candidateTask.getDisplayArea();
        final Task rootTask = taskDisplayArea.getOrCreateRootTask(windowingMode, activityType,
                false /* onTop */, candidateTask /* candidateTask */, null /* sourceTask */,
                null /* activityOptions */, 0 /* launchFlags */);
        assertEquals(reuseCandidate, rootTask == candidateTask);
    }

    @Test
    public void testGetOrCreateRootHomeTask_defaultDisplay() {
        TaskDisplayArea defaultTaskDisplayArea = mWm.mRoot.getDefaultTaskDisplayArea();

        // Remove the current home root task if it exists so a new one can be created below.
        Task homeTask = defaultTaskDisplayArea.getRootHomeTask();
        if (homeTask != null) {
            defaultTaskDisplayArea.removeChild(homeTask);
        }
        assertNull(defaultTaskDisplayArea.getRootHomeTask());

        assertNotNull(defaultTaskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_supportedSecondaryDisplay() {
        DisplayContent display = createNewDisplay();
        doReturn(true).when(display).supportsSystemDecorations();

        // Remove the current home root task if it exists so a new one can be created below.
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();
        Task homeTask = taskDisplayArea.getRootHomeTask();
        if (homeTask != null) {
            taskDisplayArea.removeChild(homeTask);
        }
        assertNull(taskDisplayArea.getRootHomeTask());

        assertNotNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_unsupportedSystemDecorations() {
        DisplayContent display = createNewDisplay();
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();
        doReturn(false).when(display).supportsSystemDecorations();

        assertNull(taskDisplayArea.getRootHomeTask());
        assertNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_untrustedDisplay() {
        DisplayContent display = createNewDisplay();
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();
        doReturn(false).when(display).isTrusted();

        assertNull(taskDisplayArea.getRootHomeTask());
        assertNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_dontMoveToTop() {
        DisplayContent display = createNewDisplay();
        display.mDontMoveToTop = true;
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();

        assertNull(taskDisplayArea.getRootHomeTask());
        assertNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testLastFocusedRootTaskIsUpdatedWhenMovingRootTask() {
        // Create a root task at bottom.
        final TaskDisplayArea taskDisplayAreas =
                mRootWindowContainer.getDefaultDisplay().getDefaultTaskDisplayArea();
        final Task rootTask =
                new TaskBuilder(mSupervisor).setOnTop(!ON_TOP).setCreateActivity(true).build();
        final Task prevFocusedRootTask = taskDisplayAreas.getFocusedRootTask();

        rootTask.moveToFront("moveRootTaskToFront");
        // After moving the root task to front, the previous focused should be the last focused.
        assertTrue(rootTask.isFocusedRootTaskOnDisplay());
        assertEquals(prevFocusedRootTask, taskDisplayAreas.getLastFocusedRootTask());

        rootTask.moveToBack("moveRootTaskToBack", null /* task */);
        // After moving the root task to back, the root task should be the last focused.
        assertEquals(rootTask, taskDisplayAreas.getLastFocusedRootTask());
    }

    /**
     * This test simulates the picture-in-picture menu activity launches an activity to fullscreen
     * root task. The fullscreen root task should be the top focused for resuming correctly.
     */
    @Test
    public void testFullscreenRootTaskCanBeFocusedWhenFocusablePinnedRootTaskExists() {
        // Create a pinned root task and move to front.
        final Task pinnedRootTask = mRootWindowContainer.getDefaultTaskDisplayArea()
                .createRootTask(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task pinnedTask = new TaskBuilder(mAtm.mTaskSupervisor)
                .setParentTask(pinnedRootTask).build();
        new ActivityBuilder(mAtm).setActivityFlags(FLAG_ALWAYS_FOCUSABLE)
                .setTask(pinnedTask).build();
        pinnedRootTask.moveToFront("movePinnedRootTaskToFront");

        // The focused root task should be the pinned root task.
        assertTrue(pinnedRootTask.isFocusedRootTaskOnDisplay());

        // Create a fullscreen root task and move to front.
        final Task fullscreenRootTask = createTaskWithActivity(
                mRootWindowContainer.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, ON_TOP, true);
        fullscreenRootTask.moveToFront("moveFullscreenRootTaskToFront");

        // The focused root task should be the fullscreen root task.
        assertTrue(fullscreenRootTask.isFocusedRootTaskOnDisplay());
    }

    /**
     * Test {@link TaskDisplayArea#mPreferredTopFocusableRootTask} will be cleared when
     * the root task is removed or moved to back, and the focused root task will be according to
     * z-order.
     */
    @Test
    public void testRootTaskShouldNotBeFocusedAfterMovingToBackOrRemoving() {
        // Create a display which only contains 2 root task.
        final DisplayContent display = addNewDisplayContentAt(POSITION_TOP);
        final Task rootTask1 = createTaskWithActivity(display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, ON_TOP, true /* twoLevelTask */);
        final Task rootTask2 = createTaskWithActivity(display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, ON_TOP, true /* twoLevelTask */);

        // Put rootTask1 and rootTask2 on top.
        rootTask1.moveToFront("moveRootTask1ToFront");
        rootTask2.moveToFront("moveRootTask2ToFront");
        assertTrue(rootTask2.isFocusedRootTaskOnDisplay());

        // rootTask1 should be focused after moving rootTask2 to back.
        rootTask2.moveToBack("moveRootTask2ToBack", null /* task */);
        assertTrue(rootTask1.isFocusedRootTaskOnDisplay());

        // rootTask2 should be focused after removing rootTask1.
        rootTask1.getDisplayArea().removeRootTask(rootTask1);
        assertTrue(rootTask2.isFocusedRootTaskOnDisplay());
    }

    /**
     * This test enforces that alwaysOnTop root task is placed at proper position.
     */
    @Test
    public void testAlwaysOnTopRootTaskLocation() {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task alwaysOnTopRootTask = taskDisplayArea.createRootTask(WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(alwaysOnTopRootTask).build();
        alwaysOnTopRootTask.setAlwaysOnTop(true);
        taskDisplayArea.positionChildAt(POSITION_TOP, alwaysOnTopRootTask,
                false /* includingParents */);
        assertTrue(alwaysOnTopRootTask.isAlwaysOnTop());
        assertEquals(alwaysOnTopRootTask, taskDisplayArea.getTopRootTask());

        final Task pinnedRootTask = taskDisplayArea.createRootTask(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(pinnedRootTask, taskDisplayArea.getRootPinnedTask());
        assertEquals(pinnedRootTask, taskDisplayArea.getTopRootTask());

        final Task anotherAlwaysOnTopRootTask = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        anotherAlwaysOnTopRootTask.setAlwaysOnTop(true);
        taskDisplayArea.positionChildAt(POSITION_TOP, anotherAlwaysOnTopRootTask,
                false /* includingParents */);
        assertTrue(anotherAlwaysOnTopRootTask.isAlwaysOnTop());
        int topPosition = taskDisplayArea.getRootTaskCount() - 1;
        // Ensure the new alwaysOnTop root task is put below the pinned root task, but on top of the
        // existing alwaysOnTop root task.
        assertEquals(topPosition - 1, getTaskIndexOf(taskDisplayArea, anotherAlwaysOnTopRootTask));

        final Task nonAlwaysOnTopRootTask = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(taskDisplayArea, nonAlwaysOnTopRootTask.getDisplayArea());
        topPosition = taskDisplayArea.getRootTaskCount() - 1;
        // Ensure the non-alwaysOnTop root task is put below the three alwaysOnTop root tasks, but
        // above the existing other non-alwaysOnTop root tasks.
        assertEquals(topPosition - 3, getTaskIndexOf(taskDisplayArea, nonAlwaysOnTopRootTask));

        anotherAlwaysOnTopRootTask.setAlwaysOnTop(false);
        taskDisplayArea.positionChildAt(POSITION_TOP, anotherAlwaysOnTopRootTask,
                false /* includingParents */);
        assertFalse(anotherAlwaysOnTopRootTask.isAlwaysOnTop());
        // Ensure, when always on top is turned off for a root task, the root task is put just below
        // all other always on top root tasks.
        assertEquals(topPosition - 2, getTaskIndexOf(taskDisplayArea, anotherAlwaysOnTopRootTask));
        anotherAlwaysOnTopRootTask.setAlwaysOnTop(true);

        // Ensure always on top state changes properly when windowing mode changes.
        anotherAlwaysOnTopRootTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertFalse(anotherAlwaysOnTopRootTask.isAlwaysOnTop());
        assertEquals(topPosition - 2, getTaskIndexOf(taskDisplayArea, anotherAlwaysOnTopRootTask));
        anotherAlwaysOnTopRootTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTrue(anotherAlwaysOnTopRootTask.isAlwaysOnTop());
        assertEquals(topPosition - 1, getTaskIndexOf(taskDisplayArea, anotherAlwaysOnTopRootTask));

        final Task dreamRootTask = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_DREAM, true /* onTop */);
        assertEquals(taskDisplayArea, dreamRootTask.getDisplayArea());
        assertTrue(dreamRootTask.isAlwaysOnTop());
        topPosition = taskDisplayArea.getRootTaskCount() - 1;
        // Ensure dream shows above all activities, including PiP
        assertEquals(dreamRootTask, taskDisplayArea.getTopRootTask());
        assertEquals(topPosition - 1, getTaskIndexOf(taskDisplayArea, pinnedRootTask));

        final Task assistRootTask = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT, true /* onTop */);
        assertEquals(taskDisplayArea, assistRootTask.getDisplayArea());
        assertFalse(assistRootTask.isAlwaysOnTop());
        topPosition = taskDisplayArea.getRootTaskCount() - 1;

        // Ensure Assistant shows as a non-always-on-top activity when config_assistantOnTopOfDream
        // is false and on top of everything when true.
        final boolean isAssistantOnTop = mContext.getResources()
                .getBoolean(com.android.internal.R.bool.config_assistantOnTopOfDream);
        assertEquals(isAssistantOnTop ? topPosition : topPosition - 4,
                getTaskIndexOf(taskDisplayArea, assistRootTask));
    }

    /**
     * This test verifies proper launch root based on source and candidate task for split screen.
     * If a task is launching from a created-by-organizer task, it should be launched into the
     * same created-by-organizer task as well. Unless, the candidate task is already positioned in
     * the split.
     */
    @Test
    public void getLaunchRootTaskInSplit() {
        final Task rootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        rootTask.mCreatedByOrganizer = true;
        final Task adjacentRootTask = createTask(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        adjacentRootTask.mCreatedByOrganizer = true;
        final Task candidateTask = createTaskInRootTask(rootTask, 0 /* userId*/);
        final TaskDisplayArea taskDisplayArea = rootTask.getDisplayArea();
        adjacentRootTask.setAdjacentTaskFragment(rootTask);

        // Verify the launch root with candidate task
        Task actualRootTask = taskDisplayArea.getLaunchRootTask(WINDOWING_MODE_UNDEFINED,
                ACTIVITY_TYPE_STANDARD, null /* options */, adjacentRootTask /* sourceTask */,
                0 /* launchFlags */, candidateTask);
        assertSame(rootTask, actualRootTask);

        // Verify the launch root task without candidate task
        actualRootTask = taskDisplayArea.getLaunchRootTask(WINDOWING_MODE_UNDEFINED,
                ACTIVITY_TYPE_STANDARD, null /* options */, adjacentRootTask /* sourceTask */,
                0 /* launchFlags */);
        assertSame(adjacentRootTask, actualRootTask);

        final Task pinnedTask = createTask(
                mDisplayContent, WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);
        // Verify not adjusting launch target for pinned candidate task
        actualRootTask = taskDisplayArea.getLaunchRootTask(WINDOWING_MODE_UNDEFINED,
                ACTIVITY_TYPE_STANDARD, null /* options */, adjacentRootTask /* sourceTask */,
                0 /* launchFlags */, pinnedTask /* candidateTask */);
        assertNull(actualRootTask);
    }

    @Test
    public void testMovedRootTaskToFront() {
        final TaskDisplayArea tda = mDefaultDisplay.getDefaultTaskDisplayArea();
        final Task rootTask = createTask(tda, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */, true /* createActivity */, true /* twoLevelTask */);
        final Task leafTask = rootTask.getTopLeafTask();

        clearInvocations(tda);
        tda.onTaskMoved(rootTask, true /* toTop */, false /* toBottom */);
        verify(tda).onLeafTaskMoved(eq(leafTask), anyBoolean(), anyBoolean());
    }
}
