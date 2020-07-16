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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    private ActivityStack mPinnedStack;

    @Before
    public void setUp() throws Exception {
        mPinnedStack = createTaskStackOnDisplay(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        // Stack should contain visible app window to be considered visible.
        final Task pinnedTask = createTaskInStack(mPinnedStack, 0 /* userId */);
        assertFalse(mPinnedStack.isVisible());
        final ActivityRecord pinnedApp =
                WindowTestUtils.createTestActivityRecord(mDisplayContent);
        pinnedTask.addChild(pinnedApp, 0 /* addPos */);
        assertTrue(mPinnedStack.isVisible());
    }

    @After
    public void tearDown() throws Exception {
        mPinnedStack.removeImmediately();
    }

    @Test
    public void testActivityWithZBoost_taskDisplayAreaDoesNotMoveUp() {
        final ActivityStack stack = createTaskStackOnDisplay(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ActivityRecord activity = WindowTestUtils.createTestActivityRecord(mDisplayContent);
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
        final ActivityStack stack1 = createTaskStackOnDisplay(mDisplayContent);
        final ActivityStack stack2 = createTaskStackOnDisplay(mDisplayContent);

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stack1Pos = taskStackContainer.mChildren.indexOf(stack1);
        final int stack2Pos = taskStackContainer.mChildren.indexOf(stack2);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(mPinnedStack);
        assertThat(pinnedStackPos).isGreaterThan(stack2Pos);
        assertThat(stack2Pos).isGreaterThan(stack1Pos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_BOTTOM, mPinnedStack, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);

        taskStackContainer.positionChildAt(1, mPinnedStack, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);
    }

    @Test
    public void testStackPositionBelowPinnedStack() {
        // Test that no stack can be above pinned stack.
        final ActivityStack stack1 = createTaskStackOnDisplay(mDisplayContent);

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stackPos = taskStackContainer.mChildren.indexOf(stack1);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(mPinnedStack);
        assertThat(pinnedStackPos).isGreaterThan(stackPos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_TOP, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);

        taskStackContainer.positionChildAt(taskStackContainer.mChildren.size() - 1, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);
    }

    @Test
    public void testDisplayPositionWithPinnedStack() {
        // Make sure the display is trusted display which capable to move the stack to top.
        spyOn(mDisplayContent);
        doReturn(true).when(mDisplayContent).isTrusted();

        // The display contains pinned stack that was added in {@link #setUp}.
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
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
    public void testReuseTaskAsStack() {
        final Task candidateTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task newStack = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final TaskDisplayArea taskDisplayArea = candidateTask.getDisplayArea();
        doReturn(newStack).when(taskDisplayArea).createStack(anyInt(), anyInt(), anyBoolean(),
                any(), any(), anyBoolean());

        final int type = ACTIVITY_TYPE_STANDARD;
        assertGetOrCreateStack(WINDOWING_MODE_FULLSCREEN, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateStack(WINDOWING_MODE_UNDEFINED, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateStack(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateStack(WINDOWING_MODE_FREEFORM, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateStack(WINDOWING_MODE_MULTI_WINDOW, type, candidateTask,
                true /* reuseCandidate */);
        assertGetOrCreateStack(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, type, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateStack(WINDOWING_MODE_PINNED, type, candidateTask,
                true /* reuseCandidate */);

        final int windowingMode = WINDOWING_MODE_FULLSCREEN;
        assertGetOrCreateStack(windowingMode, ACTIVITY_TYPE_HOME, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateStack(windowingMode, ACTIVITY_TYPE_RECENTS, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateStack(windowingMode, ACTIVITY_TYPE_ASSISTANT, candidateTask,
                false /* reuseCandidate */);
        assertGetOrCreateStack(windowingMode, ACTIVITY_TYPE_DREAM, candidateTask,
                false /* reuseCandidate */);
    }

    @Test
    public void testGetOrientation_nonResizableHomeStackWithHomeActivityPendingVisibilityChange() {
        final RootWindowContainer rootWindowContainer = mWm.mAtmService.mRootWindowContainer;
        final TaskDisplayArea defaultTaskDisplayArea =
                rootWindowContainer.getDefaultTaskDisplayArea();

        final ActivityStack rootHomeTask = defaultTaskDisplayArea.getRootHomeTask();
        rootHomeTask.mResizeMode = RESIZE_MODE_UNRESIZEABLE;

        final ActivityStack primarySplitTask =
                new ActivityTestsBase.StackBuilder(rootWindowContainer)
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
            homeActivity = new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                    .setStack(rootHomeTask).setCreateTask(true).build();
        }
        homeActivity.setVisible(false);
        homeActivity.mVisibleRequested = true;
        assertFalse(rootHomeTask.isVisible());

        assertEquals(rootWindowContainer.getOrientation(), rootHomeTask.getOrientation());
    }

    private void assertGetOrCreateStack(int windowingMode, int activityType, Task candidateTask,
            boolean reuseCandidate) {
        final TaskDisplayArea taskDisplayArea = candidateTask.getDisplayArea();
        final ActivityStack stack = taskDisplayArea.getOrCreateStack(windowingMode, activityType,
                false /* onTop */, null /* intent */, candidateTask /* candidateTask */);
        assertEquals(reuseCandidate, stack == candidateTask);
    }
}
