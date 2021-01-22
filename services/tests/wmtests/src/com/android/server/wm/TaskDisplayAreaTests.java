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
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.Task.ActivityState.RESUMED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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

    private Task mPinnedTask;

    @Before
    public void setUp() throws Exception {
        mPinnedTask = createTaskStackOnDisplay(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        // Stack should contain visible app window to be considered visible.
        assertFalse(mPinnedTask.isVisible());
        final ActivityRecord pinnedApp = createNonAttachedActivityRecord(mDisplayContent);
        mPinnedTask.addChild(pinnedApp, 0 /* addPos */);
        assertTrue(mPinnedTask.isVisible());
    }

    @After
    public void tearDown() throws Exception {
        mPinnedTask.removeImmediately();
    }

    @Test
    public void testActivityWithZBoost_taskDisplayAreaDoesNotMoveUp() {
        final Task stack = createTaskStackOnDisplay(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
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
    public void testStackPositionChildAt() {
        // Test that always-on-top stack can't be moved to position other than top.
        final Task stack1 = createTaskStackOnDisplay(mDisplayContent);
        final Task stack2 = createTaskStackOnDisplay(mDisplayContent);

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stack1Pos = taskStackContainer.mChildren.indexOf(stack1);
        final int stack2Pos = taskStackContainer.mChildren.indexOf(stack2);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(mPinnedTask);
        assertThat(pinnedStackPos).isGreaterThan(stack2Pos);
        assertThat(stack2Pos).isGreaterThan(stack1Pos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_BOTTOM, mPinnedTask, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedTask);

        taskStackContainer.positionChildAt(1, mPinnedTask, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedTask);
    }

    @Test
    public void testStackPositionBelowPinnedStack() {
        // Test that no stack can be above pinned stack.
        final Task stack1 = createTaskStackOnDisplay(mDisplayContent);

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stackPos = taskStackContainer.mChildren.indexOf(stack1);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(mPinnedTask);
        assertThat(pinnedStackPos).isGreaterThan(stackPos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_TOP, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedTask);

        taskStackContainer.positionChildAt(taskStackContainer.mChildren.size() - 1, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedTask);
    }

    @Test
    public void testDisplayPositionWithPinnedStack() {
        // Make sure the display is trusted display which capable to move the stack to top.
        spyOn(mDisplayContent);
        doReturn(true).when(mDisplayContent).isTrusted();

        // The display contains pinned stack that was added in {@link #setUp}.
        final Task stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);

        // Add another display at top.
        mWm.mRoot.positionChildAt(WindowContainer.POSITION_TOP, createNewDisplay(),
                false /* includingParents */);

        // Move the task of {@code mDisplayContent} to top.
        stack.positionChildAt(WindowContainer.POSITION_TOP, task, true /* includingParents */);
        final int indexOfDisplayWithPinnedStack = mWm.mRoot.mChildren.indexOf(mDisplayContent);

        assertEquals("The testing DisplayContent should be moved to top with task",
                mWm.mRoot.getChildCount() - 1, indexOfDisplayWithPinnedStack);
    }

    @Test
    public void testReuseTaskAsRootTask() {
        final Task candidateTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final int type = ACTIVITY_TYPE_STANDARD;
        assertGetOrCreateRootTask(WINDOWING_MODE_FULLSCREEN, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateRootTask(WINDOWING_MODE_UNDEFINED, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateRootTask(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateRootTask(WINDOWING_MODE_FREEFORM, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateRootTask(WINDOWING_MODE_MULTI_WINDOW, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateRootTask(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, type, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateRootTask(WINDOWING_MODE_PINNED, type, candidateTask,
                true /* reuseCandidate */);

        final int windowingMode = WINDOWING_MODE_FULLSCREEN;
        assertGetOrCreateRootTask(windowingMode, ACTIVITY_TYPE_HOME, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateRootTask(windowingMode, ACTIVITY_TYPE_RECENTS, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateRootTask(windowingMode, ACTIVITY_TYPE_ASSISTANT, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateRootTask(windowingMode, ACTIVITY_TYPE_DREAM, candidateTask,
                false /* reuseCandidate */);
    }

    @Test
    public void testGetOrientation_nonResizableHomeStackWithHomeActivityPendingVisibilityChange() {
        final RootWindowContainer rootWindowContainer = mWm.mAtmService.mRootWindowContainer;
        final TaskDisplayArea defaultTaskDisplayArea =
                rootWindowContainer.getDefaultTaskDisplayArea();

        final Task rootHomeTask = defaultTaskDisplayArea.getRootHomeTask();
        rootHomeTask.mResizeMode = RESIZE_MODE_UNRESIZEABLE;

        final Task primarySplitTask = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(defaultTaskDisplayArea)
                .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .setOnTop(true)
                .setCreateActivity(true)
                .build();
        ActivityRecord primarySplitActivity = primarySplitTask.getTopNonFinishingActivity();
        assertNotNull(primarySplitActivity);
        primarySplitActivity.setState(RESUMED,
                "testGetOrientation_nonResizableHomeStackWithHomeActivityPendingVisibilityChange");

        ActivityRecord homeActivity = rootHomeTask.getTopNonFinishingActivity();
        if (homeActivity == null) {
            homeActivity = new ActivityBuilder(mWm.mAtmService)
                    .setParentTask(rootHomeTask).setCreateTask(true).build();
        }
        homeActivity.setVisible(false);
        homeActivity.mVisibleRequested = true;
        assertFalse(rootHomeTask.isVisible());

        assertEquals(defaultTaskDisplayArea.getOrientation(), rootHomeTask.getOrientation());
    }

    @Test
    public void testIsLastFocused() {
        final TaskDisplayArea firstTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                mDisplayContent, mRootWindowContainer.mWmService, "TestTaskDisplayArea",
                FEATURE_VENDOR_FIRST);
        final Task firstStack = firstTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task secondStack = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(firstStack).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(secondStack).build();

        // Activity on TDA1 is focused
        mDisplayContent.setFocusedApp(firstActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation()).isTrue();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation()).isFalse();

        // No focused app, TDA1 is still recorded as last focused.
        mDisplayContent.setFocusedApp(null);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation()).isTrue();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation()).isFalse();

        // Activity on TDA2 is focused
        mDisplayContent.setFocusedApp(secondActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation()).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation()).isTrue();
    }

    @Test
    public void testIsLastFocused_onlyCountIfTaskDisplayAreaHandlesOrientationRequest() {
        final TaskDisplayArea firstTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                mDisplayContent, mRootWindowContainer.mWmService, "TestTaskDisplayArea",
                FEATURE_VENDOR_FIRST);
        final Task firstStack = firstTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task secondStack = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(firstStack).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(secondStack).build();
        firstTaskDisplayArea.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        secondTaskDisplayArea.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);

        // Activity on TDA1 is focused, but TDA1 doesn't respect orientation request
        mDisplayContent.setFocusedApp(firstActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation()).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation()).isFalse();

        // Activity on TDA2 is focused, and TDA2 respects orientation request
        mDisplayContent.setFocusedApp(secondActivity);

        assertThat(firstTaskDisplayArea.canSpecifyOrientation()).isFalse();
        assertThat(secondTaskDisplayArea.canSpecifyOrientation()).isTrue();
    }

    @Test
    public void testIgnoreOrientationRequest() {
        final TaskDisplayArea taskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final Task stack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(stack).build();

        mDisplayContent.setFocusedApp(activity);
        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);

        taskDisplayArea.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSET);
    }

    @Test
    @UseTestDisplay
    public void testRemove_reparentToDefault() {
        final Task task = createTaskStackOnDisplay(mDisplayContent);
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
    public void testRemove_stackCreatedByOrganizer() {
        final Task task = createTaskStackOnDisplay(mDisplayContent);
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
                false /* onTop */, null /* intent */, candidateTask /* candidateTask */,
                null /* activityOptions */);
        assertEquals(reuseCandidate, rootTask == candidateTask);
    }
}
