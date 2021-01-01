/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
import static com.android.server.wm.Task.ActivityState.DESTROYING;
import static com.android.server.wm.Task.ActivityState.FINISHING;
import static com.android.server.wm.Task.ActivityState.PAUSING;
import static com.android.server.wm.Task.ActivityState.RESUMED;
import static com.android.server.wm.Task.ActivityState.STOPPED;
import static com.android.server.wm.Task.ActivityState.STOPPING;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;
import static com.android.server.wm.Task.REPARENT_KEEP_ROOT_TASK_AT_FRONT;
import static com.android.server.wm.Task.REPARENT_MOVE_ROOT_TASK_TO_FRONT;
import static com.android.server.wm.Task.TASK_VISIBILITY_INVISIBLE;
import static com.android.server.wm.Task.TASK_VISIBILITY_VISIBLE;
import static com.android.server.wm.Task.TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
import static com.android.server.wm.TaskDisplayArea.getRootTaskAbove;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.wm.TaskDisplayArea.OnRootTaskOrderChangedListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Tests for the {@link ActivityStack} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStackTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityStackTests extends WindowTestsBase {
    private TaskDisplayArea mDefaultTaskDisplayArea;

    @Before
    public void setUp() throws Exception {
        mDefaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
    }

    @Test
    public void testResumedActivity() {
        final ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = r.getTask();
        assertNull(task.getResumedActivity());
        r.setState(RESUMED, "testResumedActivity");
        assertEquals(r, task.getResumedActivity());
        r.setState(PAUSING, "testResumedActivity");
        assertNull(task.getResumedActivity());
    }

    @Test
    public void testResumedActivityFromTaskReparenting() {
        final Task parentTask = new TaskBuilder(mSupervisor).setOnTop(true).build();
        final ActivityRecord r = new ActivityBuilder(mAtm)
                .setCreateTask(true).setParentTask(parentTask).build();
        final Task task = r.getTask();
        // Ensure moving task between two stacks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromTaskReparenting");
        assertEquals(r, parentTask.getResumedActivity());

        final Task destStack = new TaskBuilder(mSupervisor).setOnTop(true).build();
        task.reparent(destStack, true /* toTop */, REPARENT_KEEP_ROOT_TASK_AT_FRONT,
                false /* animate */, true /* deferResume*/,
                "testResumedActivityFromTaskReparenting");

        assertNull(parentTask.getResumedActivity());
        assertEquals(r, destStack.getResumedActivity());
    }

    @Test
    public void testResumedActivityFromActivityReparenting() {
        final Task parentTask = new TaskBuilder(mSupervisor).setOnTop(true).build();
        final ActivityRecord r = new ActivityBuilder(mAtm)
                .setCreateTask(true).setParentTask(parentTask).build();
        final Task task = r.getTask();
        // Ensure moving task between two stacks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromActivityReparenting");
        assertEquals(r, parentTask.getResumedActivity());

        final Task destStack = new TaskBuilder(mSupervisor).setOnTop(true).build();
        task.reparent(destStack, true /*toTop*/, REPARENT_MOVE_ROOT_TASK_TO_FRONT,
                false /* animate */, false /* deferResume*/,
                "testResumedActivityFromActivityReparenting");

        assertNull(parentTask.getResumedActivity());
        assertEquals(r, destStack.getResumedActivity());
    }

    @Test
    public void testPrimarySplitScreenMoveToBack() {
        TestSplitOrganizer organizer = new TestSplitOrganizer(mAtm);
        // We're testing an edge case here where we have primary + fullscreen rather than secondary.
        organizer.setMoveToSecondaryOnEnter(false);

        // Create primary splitscreen stack.
        final Task primarySplitScreen = mDefaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Assert windowing mode.
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, primarySplitScreen.getWindowingMode());

        // Move primary to back.
        primarySplitScreen.moveToBack("testPrimarySplitScreenToFullscreenWhenMovedToBack",
                null /* task */);

        // Assert that stack is at the bottom.
        assertEquals(0, getTaskIndexOf(mDefaultTaskDisplayArea, primarySplitScreen));

        // Ensure no longer in splitscreen.
        assertEquals(WINDOWING_MODE_FULLSCREEN, primarySplitScreen.getWindowingMode());

        // Ensure that the override mode is restored to undefined
        assertEquals(WINDOWING_MODE_UNDEFINED,
                primarySplitScreen.getRequestedOverrideWindowingMode());
    }

    @Test
    public void testMoveToPrimarySplitScreenThenMoveToBack() {
        TestSplitOrganizer organizer = new TestSplitOrganizer(mAtm);
        // This time, start with a fullscreen activitystack
        final Task primarySplitScreen = mDefaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        primarySplitScreen.reparent(organizer.mPrimary, POSITION_TOP,
                false /*moveParents*/, "test");

        // Assert windowing mode.
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, primarySplitScreen.getWindowingMode());

        // Move primary to back.
        primarySplitScreen.moveToBack("testPrimarySplitScreenToFullscreenWhenMovedToBack",
                null /* task */);

        // Assert that stack is at the bottom.
        assertEquals(primarySplitScreen, organizer.mSecondary.getChildAt(0));

        // Ensure that the override mode is restored to what it was (fullscreen)
        assertEquals(WINDOWING_MODE_UNDEFINED,
                primarySplitScreen.getRequestedOverrideWindowingMode());
    }

    @Test
    public void testSplitScreenMoveToBack() {
        TestSplitOrganizer organizer = new TestSplitOrganizer(mAtm);
        // Set up split-screen with primary on top and secondary containing the home task below
        // another stack.
        final Task primaryTask = mDefaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task homeRoot = mDefaultTaskDisplayArea.getRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        final Task secondaryTask = mDefaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mDefaultTaskDisplayArea.positionChildAt(POSITION_TOP, organizer.mPrimary,
                false /* includingParents */);

        // Move primary to back.
        primaryTask.moveToBack("test", null /* task */);

        // Assert that the primaryTask is now below home in its parent but primary is left alone.
        assertEquals(0, organizer.mPrimary.getChildCount());
        assertEquals(primaryTask, organizer.mSecondary.getChildAt(0));
        assertEquals(1, organizer.mPrimary.compareTo(organizer.mSecondary));
        assertEquals(1, homeRoot.compareTo(primaryTask));
        assertEquals(homeRoot.getParent(), primaryTask.getParent());

        // Make sure windowing modes are correct
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, organizer.mPrimary.getWindowingMode());
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, primaryTask.getWindowingMode());

        // Move secondary to back via parent (should be equivalent)
        organizer.mSecondary.moveToBack("test", secondaryTask);

        // Assert that it is now in back but still in secondary split
        assertEquals(1, homeRoot.compareTo(primaryTask));
        assertEquals(secondaryTask, organizer.mSecondary.getChildAt(0));
        assertEquals(1, primaryTask.compareTo(secondaryTask));
        assertEquals(homeRoot.getParent(), secondaryTask.getParent());
    }

    @Test
    public void testRemoveOrganizedTask_UpdateStackReference() {
        final Task rootHomeTask = mDefaultTaskDisplayArea.getRootHomeTask();
        final ActivityRecord homeActivity = new ActivityBuilder(mAtm)
                .setTask(rootHomeTask)
                .build();
        final Task secondaryStack = mAtm.mTaskOrganizerController.createRootTask(
                rootHomeTask.getDisplayContent(), WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, null);

        rootHomeTask.reparent(secondaryStack, POSITION_TOP);
        assertEquals(secondaryStack, rootHomeTask.getParent());

        // This should call to {@link TaskDisplayArea#removeStackReferenceIfNeeded}.
        homeActivity.removeImmediately();
        assertNull(mDefaultTaskDisplayArea.getRootHomeTask());
    }

    @Test
    public void testStackInheritsDisplayWindowingMode() {
        final Task primarySplitScreen = mDefaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        assertEquals(WINDOWING_MODE_FULLSCREEN, primarySplitScreen.getWindowingMode());
        assertEquals(WINDOWING_MODE_UNDEFINED,
                primarySplitScreen.getRequestedOverrideWindowingMode());

        mDefaultTaskDisplayArea.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(WINDOWING_MODE_FREEFORM, primarySplitScreen.getWindowingMode());
        assertEquals(WINDOWING_MODE_UNDEFINED,
                primarySplitScreen.getRequestedOverrideWindowingMode());
    }

    @Test
    public void testStackOverridesDisplayWindowingMode() {
        final Task primarySplitScreen = mDefaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        assertEquals(WINDOWING_MODE_FULLSCREEN, primarySplitScreen.getWindowingMode());
        assertEquals(WINDOWING_MODE_UNDEFINED,
                primarySplitScreen.getRequestedOverrideWindowingMode());

        primarySplitScreen.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        // setting windowing mode should still work even though resolved mode is already fullscreen
        assertEquals(WINDOWING_MODE_FULLSCREEN,
                primarySplitScreen.getRequestedOverrideWindowingMode());

        mDefaultTaskDisplayArea.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(WINDOWING_MODE_FULLSCREEN, primarySplitScreen.getWindowingMode());
    }

    @Test
    public void testStopActivityWhenActivityDestroyed() {
        final ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();
        r.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        r.getTask().moveToFront("testStopActivityWithDestroy");
        r.stopIfPossible();
        // Mostly testing to make sure there is a crash in the call part, so if we get here we are
        // good-to-go!
    }

    @Test
    public void testFindTaskWithOverlay() {
        final ActivityRecord r = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setUid(0)
                .build();
        final Task task = r.getTask();
        // Overlay must be for a different user to prevent recognizing a matching top activity
        final ActivityRecord taskOverlay = new ActivityBuilder(mAtm).setTask(task)
                .setUid(UserHandle.PER_USER_RANGE * 2).build();
        taskOverlay.setTaskOverlay(true);

        final RootWindowContainer.FindTaskResult result =
                new RootWindowContainer.FindTaskResult();
        result.process(r, task);

        assertEquals(r, task.getTopNonFinishingActivity(false /* includeOverlays */));
        assertEquals(taskOverlay, task.getTopNonFinishingActivity(true /* includeOverlays */));
        assertNotNull(result.mRecord);
    }

    @Test
    public void testFindTaskAlias() {
        final String targetActivity = "target.activity";
        final String aliasActivity = "alias.activity";
        final ComponentName target = new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME,
                targetActivity);
        final ComponentName alias = new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME,
                aliasActivity);
        final Task parentTask = new TaskBuilder(mAtm.mTaskSupervisor).build();
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor).setParentTask(parentTask).build();
        task.origActivity = alias;
        task.realActivity = target;
        new ActivityBuilder(mAtm).setComponent(target).setTask(task).setTargetActivity(
                targetActivity).build();

        // Using target activity to find task.
        final ActivityRecord r1 = new ActivityBuilder(mAtm).setComponent(
                target).setTargetActivity(targetActivity).build();
        RootWindowContainer.FindTaskResult result = new RootWindowContainer.FindTaskResult();
        result.process(r1, parentTask);
        assertThat(result.mRecord).isNotNull();

        // Using alias activity to find task.
        final ActivityRecord r2 = new ActivityBuilder(mAtm).setComponent(
                alias).setTargetActivity(targetActivity).build();
        result = new RootWindowContainer.FindTaskResult();
        result.process(r2, parentTask);
        assertThat(result.mRecord).isNotNull();
    }

    @Test
    public void testMoveStackToBackIncludingParent() {
        final TaskDisplayArea taskDisplayArea = addNewDisplayContentAt(DisplayContent.POSITION_TOP)
                .getDefaultTaskDisplayArea();
        final Task stack1 = createStackForShouldBeVisibleTest(taskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */,
                true /* twoLevelTask */);
        final Task stack2 = createStackForShouldBeVisibleTest(taskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */,
                true /* twoLevelTask */);

        // Do not move display to back because there is still another stack.
        stack2.moveToBack("testMoveStackToBackIncludingParent", stack2.getTopMostTask());
        verify(stack2).positionChildAtBottom(any(), eq(false) /* includingParents */);

        // Also move display to back because there is only one stack left.
        taskDisplayArea.removeRootTask(stack1);
        stack2.moveToBack("testMoveStackToBackIncludingParent", stack2.getTopMostTask());
        verify(stack2).positionChildAtBottom(any(), eq(true) /* includingParents */);
    }

    @Test
    public void testShouldBeVisible_Fullscreen() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // Add an activity to the pinned stack so it isn't considered empty for visibility check.
        final ActivityRecord pinnedActivity = new ActivityBuilder(mAtm)
                .setTask(pinnedStack)
                .build();

        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));

        final Task fullscreenStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        // Home stack shouldn't be visible behind an opaque fullscreen stack, but pinned stack
        // should be visible since it is always on-top.
        doReturn(false).when(fullscreenStack).isTranslucent(any());
        assertFalse(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));
        assertTrue(fullscreenStack.shouldBeVisible(null /* starting */));

        // Home stack should be visible behind a translucent fullscreen stack.
        doReturn(true).when(fullscreenStack).isTranslucent(any());
        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_SplitScreen() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        // Home stack should always be fullscreen for this test.
        doReturn(false).when(homeStack).supportsSplitScreenWindowingMode();
        final Task splitScreenPrimary =
                createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task splitScreenSecondary =
                createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Home stack shouldn't be visible if both halves of split-screen are opaque.
        doReturn(false).when(splitScreenPrimary).isTranslucent(any());
        doReturn(false).when(splitScreenSecondary).isTranslucent(any());
        assertFalse(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertEquals(TASK_VISIBILITY_INVISIBLE, homeStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));

        // Home stack should be visible if one of the halves of split-screen is translucent.
        doReturn(true).when(splitScreenPrimary).isTranslucent(any());
        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                homeStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));

        final Task splitScreenSecondary2 =
                createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // First split-screen secondary shouldn't be visible behind another opaque split-split
        // secondary.
        doReturn(false).when(splitScreenSecondary2).isTranslucent(any());
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_VISIBILITY_INVISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // First split-screen secondary should be visible behind another translucent split-screen
        // secondary.
        doReturn(true).when(splitScreenSecondary2).isTranslucent(any());
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        final Task assistantStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT,
                true /* onTop */);

        // Split-screen stacks shouldn't be visible behind an opaque fullscreen stack.
        doReturn(false).when(assistantStack).isTranslucent(any());
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                assistantStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_INVISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_INVISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_INVISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // Split-screen stacks should be visible behind a translucent fullscreen stack.
        doReturn(true).when(assistantStack).isTranslucent(any());
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                assistantStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // Assistant stack shouldn't be visible behind translucent split-screen stack,
        // unless it is configured to show on top of everything.
        doReturn(false).when(assistantStack).isTranslucent(any());
        doReturn(true).when(splitScreenPrimary).isTranslucent(any());
        doReturn(true).when(splitScreenSecondary2).isTranslucent(any());
        splitScreenSecondary2.moveToFront("testShouldBeVisible_SplitScreen");
        splitScreenPrimary.moveToFront("testShouldBeVisible_SplitScreen");

        if (isAssistantOnTop()) {
            assertTrue(assistantStack.shouldBeVisible(null /* starting */));
            assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
            assertFalse(splitScreenSecondary2.shouldBeVisible(null /* starting */));
            assertEquals(TASK_VISIBILITY_VISIBLE,
                    assistantStack.getVisibility(null /* starting */));
            assertEquals(TASK_VISIBILITY_INVISIBLE,
                    splitScreenPrimary.getVisibility(null /* starting */));
            assertEquals(TASK_VISIBILITY_INVISIBLE,
                    splitScreenSecondary.getVisibility(null /* starting */));
            assertEquals(TASK_VISIBILITY_INVISIBLE,
                    splitScreenSecondary2.getVisibility(null /* starting */));
        } else {
            assertFalse(assistantStack.shouldBeVisible(null /* starting */));
            assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
            assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
            assertEquals(TASK_VISIBILITY_INVISIBLE,
                    assistantStack.getVisibility(null /* starting */));
            assertEquals(TASK_VISIBILITY_VISIBLE,
                    splitScreenPrimary.getVisibility(null /* starting */));
            assertEquals(TASK_VISIBILITY_INVISIBLE,
                    splitScreenSecondary.getVisibility(null /* starting */));
            assertEquals(TASK_VISIBILITY_VISIBLE,
                    splitScreenSecondary2.getVisibility(null /* starting */));
        }
    }

    @Test
    public void testGetVisibility_MultiLevel() {
        final Task homeStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME,
                true /* onTop */);
        final Task splitPrimary = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                ACTIVITY_TYPE_UNDEFINED, true /* onTop */);
        final Task splitSecondary = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                ACTIVITY_TYPE_UNDEFINED, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(splitPrimary).isTranslucent(any());
        doReturn(false).when(splitSecondary).isTranslucent(any());


        // Re-parent home to split secondary.
        homeStack.reparent(splitSecondary, POSITION_TOP);
        // Current tasks should be visible.
        assertEquals(TASK_VISIBILITY_VISIBLE, splitPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE, splitSecondary.getVisibility(null /* starting */));
        // Home task should still be visible even though it is a child of another visible task.
        assertEquals(TASK_VISIBILITY_VISIBLE, homeStack.getVisibility(null /* starting */));


        // Add fullscreen translucent task that partially occludes split tasks
        final Task translucentStack = createStandardStackForVisibilityTest(
                WINDOWING_MODE_FULLSCREEN, true /* translucent */);
        // Fullscreen translucent task should be visible
        assertEquals(TASK_VISIBILITY_VISIBLE, translucentStack.getVisibility(null /* starting */));
        // Split tasks should be visible behind translucent
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitSecondary.getVisibility(null /* starting */));
        // Home task should be visible behind translucent since its parent is visible behind
        // translucent.
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                homeStack.getVisibility(null /* starting */));


        // Hide split-secondary
        splitSecondary.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, true /* set */);
        // Home split secondary and home task should be invisible.
        assertEquals(TASK_VISIBILITY_INVISIBLE, splitSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_INVISIBLE, homeStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucent() {
        final Task bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucentAndOpaque() {
        final Task bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task opaqueStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);

        assertEquals(TASK_VISIBILITY_INVISIBLE, bottomStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_INVISIBLE,
                translucentStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE, opaqueStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindOpaqueAndTranslucent() {
        final Task bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task opaqueStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(TASK_VISIBILITY_INVISIBLE, bottomStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                opaqueStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenTranslucentBehindTranslucent() {
        final Task bottomTranslucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomTranslucentStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenTranslucentBehindOpaque() {
        final Task bottomTranslucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task opaqueStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);

        assertEquals(TASK_VISIBILITY_INVISIBLE,
                bottomTranslucentStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE, opaqueStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucentAndPip() {
        final Task bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        assertEquals(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomStack.getVisibility(null /* starting */));
        assertEquals(TASK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
        // Add an activity to the pinned stack so it isn't considered empty for visibility check.
        final ActivityRecord pinnedActivity = new ActivityBuilder(mAtm)
                .setTask(pinnedStack)
                .build();
        assertEquals(TASK_VISIBILITY_VISIBLE, pinnedStack.getVisibility(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_Finishing() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        ActivityRecord topRunningHomeActivity = homeStack.topRunningActivity();
        if (topRunningHomeActivity == null) {
            topRunningHomeActivity = new ActivityBuilder(mAtm)
                    .setTask(homeStack)
                    .build();
        }

        final Task translucentStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        doReturn(true).when(translucentStack).isTranslucent(any());

        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(translucentStack.shouldBeVisible(null /* starting */));

        topRunningHomeActivity.finishing = true;
        final ActivityRecord topRunningTranslucentActivity =
                translucentStack.topRunningActivity();
        topRunningTranslucentActivity.finishing = true;

        // Home stack should be visible even there are no running activities.
        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        // Home should be visible if we are starting an activity within it.
        assertTrue(homeStack.shouldBeVisible(topRunningHomeActivity /* starting */));
        // The translucent stack shouldn't be visible since its activity marked as finishing.
        assertFalse(translucentStack.shouldBeVisible(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_FullscreenBehindTranslucentInHomeStack() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                    .setParentTask(homeStack)
                    .setCreateTask(true)
                    .build();
        final Task task = firstActivity.getTask();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();

        doReturn(false).when(secondActivity).occludesParent();
        homeStack.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */);

        assertTrue(firstActivity.shouldBeVisible());
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeBehindFullscreen() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack).isTranslucent(any());

        // Ensure that we don't move the home stack if it is already behind the top fullscreen stack
        int homeStackIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeStack);
        assertEquals(fullscreenStack, getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeStack);
        assertEquals(homeStackIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeStack));
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeBehindTranslucent() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(true).when(fullscreenStack).isTranslucent(any());

        // Ensure that we don't move the home stack if it is already behind the top fullscreen stack
        int homeStackIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeStack);
        assertEquals(fullscreenStack, getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeStack);
        assertEquals(homeStackIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeStack));
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeOnTop() {
        final Task fullscreenStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack).isTranslucent(any());

        // Ensure we don't move the home stack if it is already on top
        int homeStackIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeStack);
        assertNull(getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeStack);
        assertEquals(homeStackIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeStack));
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_MoveHomeBehindFullscreen() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack1).isTranslucent(any());
        doReturn(false).when(fullscreenStack2).isTranslucent(any());

        // Ensure that we move the home stack behind the bottom most fullscreen stack, ignoring the
        // pinned stack
        assertEquals(fullscreenStack1, getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeStack);
        assertEquals(fullscreenStack2, getRootTaskAbove(homeStack));
    }

    @Test
    public void
            testMoveHomeStackBehindBottomMostVisibleStack_MoveHomeBehindFullscreenAndTranslucent() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack1).isTranslucent(any());
        doReturn(true).when(fullscreenStack2).isTranslucent(any());

        // Ensure that we move the home stack behind the bottom most non-translucent fullscreen
        // stack
        assertEquals(fullscreenStack1, getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeStack);
        assertEquals(fullscreenStack1, getRootTaskAbove(homeStack));
    }

    @Test
    public void testMoveHomeStackBehindStack_BehindHomeStack() {
        final Task fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack1).isTranslucent(any());
        doReturn(false).when(fullscreenStack2).isTranslucent(any());

        // Ensure we don't move the home stack behind itself
        int homeStackIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeStack);
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeStack, homeStack);
        assertEquals(homeStackIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeStack));
    }

    @Test
    public void testMoveHomeStackBehindStack() {
        final Task fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task fullscreenStack3 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task fullscreenStack4 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeStack, fullscreenStack1);
        assertEquals(fullscreenStack1, getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeStack, fullscreenStack2);
        assertEquals(fullscreenStack2, getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeStack, fullscreenStack4);
        assertEquals(fullscreenStack4, getRootTaskAbove(homeStack));
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeStack, fullscreenStack2);
        assertEquals(fullscreenStack2, getRootTaskAbove(homeStack));
    }

    @Test
    public void testSetAlwaysOnTop() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(pinnedStack, getRootTaskAbove(homeStack));

        final Task alwaysOnTopStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        alwaysOnTopStack.setAlwaysOnTop(true);
        assertTrue(alwaysOnTopStack.isAlwaysOnTop());
        // Ensure (non-pinned) always on top stack is put below pinned stack.
        assertEquals(pinnedStack, getRootTaskAbove(alwaysOnTopStack));

        final Task nonAlwaysOnTopStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        // Ensure non always on top stack is put below always on top stacks.
        assertEquals(alwaysOnTopStack, getRootTaskAbove(nonAlwaysOnTopStack));

        final Task alwaysOnTopStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        alwaysOnTopStack2.setAlwaysOnTop(true);
        assertTrue(alwaysOnTopStack2.isAlwaysOnTop());
        // Ensure newly created always on top stack is placed above other all always on top stacks.
        assertEquals(pinnedStack, getRootTaskAbove(alwaysOnTopStack2));

        alwaysOnTopStack2.setAlwaysOnTop(false);
        // Ensure, when always on top is turned off for a stack, the stack is put just below all
        // other always on top stacks.
        assertEquals(alwaysOnTopStack, getRootTaskAbove(alwaysOnTopStack2));
        alwaysOnTopStack2.setAlwaysOnTop(true);

        // Ensure always on top state changes properly when windowing mode changes.
        alwaysOnTopStack2.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertFalse(alwaysOnTopStack2.isAlwaysOnTop());
        assertEquals(alwaysOnTopStack, getRootTaskAbove(alwaysOnTopStack2));
        alwaysOnTopStack2.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTrue(alwaysOnTopStack2.isAlwaysOnTop());
        assertEquals(pinnedStack, getRootTaskAbove(alwaysOnTopStack2));
    }

    @Test
    public void testSplitScreenMoveToFront() {
        final Task splitScreenPrimary = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task splitScreenSecondary = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task assistantStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT,
                true /* onTop */);

        doReturn(false).when(splitScreenPrimary).isTranslucent(any());
        doReturn(false).when(splitScreenSecondary).isTranslucent(any());
        doReturn(false).when(assistantStack).isTranslucent(any());

        assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));

        splitScreenSecondary.moveToFront("testSplitScreenMoveToFront");

        if (isAssistantOnTop()) {
            assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
            assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
            assertTrue(assistantStack.shouldBeVisible(null /* starting */));
        } else {
            assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
            assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
            assertFalse(assistantStack.shouldBeVisible(null /* starting */));
        }
    }

    private Task createStandardStackForVisibilityTest(int windowingMode,
            boolean translucent) {
        final Task stack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                windowingMode, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        doReturn(translucent).when(stack).isTranslucent(any());
        return stack;
    }

    private Task createStackForShouldBeVisibleTest(
            TaskDisplayArea taskDisplayArea, int windowingMode, int activityType, boolean onTop) {
        return createStackForShouldBeVisibleTest(taskDisplayArea,
                windowingMode, activityType, onTop, false /* twoLevelTask */);
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    private Task createStackForShouldBeVisibleTest(TaskDisplayArea taskDisplayArea,
            int windowingMode, int activityType, boolean onTop, boolean twoLevelTask) {
        final Task task;
        if (activityType == ACTIVITY_TYPE_HOME) {
            task = mDefaultTaskDisplayArea.getRootTask(WINDOWING_MODE_FULLSCREEN,
                    ACTIVITY_TYPE_HOME);
            mDefaultTaskDisplayArea.positionChildAt(onTop ? POSITION_TOP : POSITION_BOTTOM, task,
                    false /* includingParents */);
        } else if (twoLevelTask) {
            task = new TaskBuilder(mSupervisor)
                    .setTaskDisplayArea(taskDisplayArea)
                    .setWindowingMode(windowingMode)
                    .setActivityType(activityType)
                    .setOnTop(onTop)
                    .setCreateActivity(true)
                    .setCreateParentTask(true)
                    .build().getRootTask();
        } else {
            task = new TaskBuilder(mSupervisor)
                    .setTaskDisplayArea(taskDisplayArea)
                    .setWindowingMode(windowingMode)
                    .setActivityType(activityType)
                    .setOnTop(onTop)
                    .setCreateActivity(true)
                    .build();
        }
        return task;
    }

    @Test
    public void testFinishDisabledPackageActivities_FinishAliveActivities() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm).setTask(task).build();
        firstActivity.setState(STOPPED, "testFinishDisabledPackageActivities");
        secondActivity.setState(RESUMED, "testFinishDisabledPackageActivities");
        task.setResumedActivity(secondActivity, "test");

        // Note the activities have non-null ActivityRecord.app, so it won't remove directly.
        mRootWindowContainer.mFinishDisabledPackageActivitiesHelper.process(
                firstActivity.packageName, null /* filterByClasses */, true /* doit */,
                true /* evenPersistent */, UserHandle.USER_ALL, false /* onlyRemoveNoProcess */);

        // If the activity is disabled with {@link android.content.pm.PackageManager#DONT_KILL_APP}
        // the activity should still follow the normal flow to finish and destroy.
        assertThat(firstActivity.getState()).isEqualTo(DESTROYING);
        assertThat(secondActivity.getState()).isEqualTo(PAUSING);
        assertTrue(secondActivity.finishing);
    }

    @Test
    public void testFinishDisabledPackageActivities_RemoveNonAliveActivities() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        // The overlay activity is not in the disabled package but it is in the same task.
        final ActivityRecord overlayActivity = new ActivityBuilder(mAtm).setTask(task)
                .setComponent(new ComponentName("package.overlay", ".OverlayActivity")).build();
        // If the task only remains overlay activity, the task should also be removed.
        // See {@link ActivityStack#removeFromHistory}.
        overlayActivity.setTaskOverlay(true);

        // The activity without an app means it will be removed immediately.
        // See {@link ActivityStack#destroyActivityLocked}.
        activity.app = null;
        overlayActivity.app = null;

        assertEquals(2, task.getChildCount());

        mRootWindowContainer.mFinishDisabledPackageActivitiesHelper.process(
                activity.packageName, null  /* filterByClasses */, true /* doit */,
                true /* evenPersistent */, UserHandle.USER_ALL, false /* onlyRemoveNoProcess */);

        // Although the overlay activity is in another package, the non-overlay activities are
        // removed from the task. Since the overlay activity should be removed as well, the task
        // should be empty.
        assertFalse(task.hasChild());
    }

    @Test
    public void testHandleAppDied() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm).setTask(task).build();

        // Making the first activity a task overlay means it will be removed from the task's
        // activities as well once second activity is removed as handleAppDied processes the
        // activity list in reverse.
        firstActivity.setTaskOverlay(true);
        firstActivity.app = null;

        // second activity will be immediately removed as it has no state.
        secondActivity.setSavedState(null /* savedState */);

        assertEquals(2, task.getChildCount());

        secondActivity.app.handleAppDied();

        assertFalse(task.hasChild());
    }

    @Test
    public void testHandleAppDied_RelaunchesAfterCrashDuringWindowingModeResize() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        activity.mRelaunchReason = RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
        activity.launchCount = 1;
        activity.setSavedState(null /* savedState */);

        activity.app.handleAppDied();

        assertEquals(1, task.getChildCount());
    }

    @Test
    public void testHandleAppDied_NotRelaunchAfterThreeCrashesDuringWindowingModeResize() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        activity.mRelaunchReason = RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
        activity.launchCount = 3;
        activity.setSavedState(null /* savedState */);

        activity.app.handleAppDied();

        assertFalse(task.hasChild());
    }

    @Test
    public void testHandleAppDied_RelaunchesAfterCrashDuringFreeResize() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        activity.mRelaunchReason = RELAUNCH_REASON_FREE_RESIZE;
        activity.launchCount = 1;
        activity.setSavedState(null /* savedState */);

        activity.app.handleAppDied();

        assertEquals(1, task.getChildCount());
    }

    @Test
    public void testHandleAppDied_NotRelaunchAfterThreeCrashesDuringFreeResize() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        activity.mRelaunchReason = RELAUNCH_REASON_FREE_RESIZE;
        activity.launchCount = 3;
        activity.setSavedState(null /* savedState */);

        activity.app.handleAppDied();

        assertFalse(task.hasChild());
    }

    @Test
    public void testCompletePauseOnResumeWhilePausingActivity() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord bottomActivity = new ActivityBuilder(mAtm).setTask(task).build();
        doReturn(true).when(bottomActivity).attachedToProcess();
        task.setPausingActivity(null);
        task.setResumedActivity(bottomActivity, "test");
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        topActivity.info.flags |= FLAG_RESUME_WHILE_PAUSING;

        task.startPausingLocked(false /* userLeaving */, false /* uiSleeping */, topActivity,
                "test");
        verify(task).completePauseLocked(anyBoolean(), eq(topActivity));
    }

    @Test
    public void testWontFinishHomeStackImmediately() {
        final Task homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        ActivityRecord activity = homeStack.topRunningActivity();
        if (activity == null) {
            activity = new ActivityBuilder(mAtm)
                    .setParentTask(homeStack)
                    .setCreateTask(true)
                    .build();
        }

        // Home stack should not be destroyed immediately.
        final ActivityRecord activity1 = finishTopActivity(homeStack);
        assertEquals(FINISHING, activity1.getState());
    }

    @Test
    public void testFinishCurrentActivity() {
        // Create 2 activities on a new display.
        final DisplayContent display = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        final Task stack1 = createStackForShouldBeVisibleTest(
                display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task stack2 = createStackForShouldBeVisibleTest(
                display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // There is still an activity1 in stack1 so the activity2 should be added to finishing list
        // that will be destroyed until idle.
        stack2.getTopNonFinishingActivity().mVisibleRequested = true;
        final ActivityRecord activity2 = finishTopActivity(stack2);
        assertEquals(STOPPING, activity2.getState());
        assertThat(mSupervisor.mStoppingActivities).contains(activity2);

        // The display becomes empty. Since there is no next activity to be idle, the activity
        // should be destroyed immediately with updating configuration to restore original state.
        final ActivityRecord activity1 = finishTopActivity(stack1);
        assertEquals(DESTROYING, activity1.getState());
        verify(mRootWindowContainer).ensureVisibilityAndConfig(eq(null) /* starting */,
                eq(display.mDisplayId), anyBoolean(), anyBoolean());
    }

    private ActivityRecord finishTopActivity(Task stack) {
        final ActivityRecord activity = stack.topRunningActivity();
        assertNotNull(activity);
        activity.setState(STOPPED, "finishTopActivity");
        activity.makeFinishingLocked();
        activity.completeFinishing("finishTopActivity");
        return activity;
    }

    @Test
    public void testShouldSleepActivities() {
        // When focused activity and keyguard is going away, we should not sleep regardless
        // of the display state, but keyguard-going-away should only take effects on default
        // display since there is no keyguard on secondary displays (yet).
        verifyShouldSleepActivities(true /* focusedStack */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* isDefaultDisplay */, false /* expected */);
        verifyShouldSleepActivities(true /* focusedStack */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, false /* isDefaultDisplay */, true /* expected */);

        // When not the focused stack, defer to display sleeping state.
        verifyShouldSleepActivities(false /* focusedStack */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* isDefaultDisplay */, true /* expected */);

        // If keyguard is going away, defer to the display sleeping state.
        verifyShouldSleepActivities(true /* focusedStack */, false /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* isDefaultDisplay */, true /* expected */);
        verifyShouldSleepActivities(true /* focusedStack */, false /*keyguardGoingAway*/,
                false /* displaySleeping */, true /* isDefaultDisplay */, false /* expected */);
    }

    @Test
    public void testStackOrderChangedOnRemoveStack() {
        final Task task = new TaskBuilder(mSupervisor).build();
        RootTaskOrderChangedListener listener = new RootTaskOrderChangedListener();
        mDefaultTaskDisplayArea.registerRootTaskOrderChangedListener(listener);
        try {
            mDefaultTaskDisplayArea.removeRootTask(task);
        } finally {
            mDefaultTaskDisplayArea.unregisterRootTaskOrderChangedListener(listener);
        }
        assertTrue(listener.mChanged);
    }

    @Test
    public void testStackOrderChangedOnAddPositionStack() {
        final Task task = new TaskBuilder(mSupervisor).build();
        mDefaultTaskDisplayArea.removeRootTask(task);

        RootTaskOrderChangedListener listener = new RootTaskOrderChangedListener();
        mDefaultTaskDisplayArea.registerRootTaskOrderChangedListener(listener);
        try {
            task.mReparenting = true;
            mDefaultTaskDisplayArea.addChild(task, 0);
        } finally {
            mDefaultTaskDisplayArea.unregisterRootTaskOrderChangedListener(listener);
        }
        assertTrue(listener.mChanged);
    }

    @Test
    public void testStackOrderChangedOnPositionStack() {
        RootTaskOrderChangedListener listener = new RootTaskOrderChangedListener();
        try {
            final Task fullscreenStack1 = createStackForShouldBeVisibleTest(
                    mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                    true /* onTop */);
            mDefaultTaskDisplayArea.registerRootTaskOrderChangedListener(listener);
            mDefaultTaskDisplayArea.positionChildAt(POSITION_BOTTOM, fullscreenStack1,
                    false /*includingParents*/);
        } finally {
            mDefaultTaskDisplayArea.unregisterRootTaskOrderChangedListener(listener);
        }
        assertTrue(listener.mChanged);
    }

    @Test
    public void testNavigateUpTo() {
        final ActivityStartController controller = mock(ActivityStartController.class);
        final ActivityStarter starter = new ActivityStarter(controller,
                mAtm, mAtm.mTaskSupervisor, mock(ActivityStartInterceptor.class));
        doReturn(controller).when(mAtm).getActivityStartController();
        spyOn(starter);
        doReturn(ActivityManager.START_SUCCESS).when(starter).execute();

        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm).setTask(task)
                .setUid(firstActivity.getUid() + 1).build();
        doReturn(starter).when(controller).obtainStarter(eq(firstActivity.intent), anyString());

        final IApplicationThread thread = secondActivity.app.getThread();
        secondActivity.app.setThread(null);
        // This should do nothing from a non-attached caller.
        assertFalse(task.navigateUpTo(secondActivity /* source record */,
                firstActivity.intent /* destIntent */, null /* destGrants */,
                0 /* resultCode */, null /* resultData */, null /* resultGrants */));

        secondActivity.app.setThread(thread);
        assertTrue(task.navigateUpTo(secondActivity /* source record */,
                firstActivity.intent /* destIntent */, null /* destGrants */,
                0 /* resultCode */, null /* resultData */, null /* resultGrants */));
        // The firstActivity uses default launch mode, so the activities between it and itself will
        // be finished.
        assertTrue(secondActivity.finishing);
        assertTrue(firstActivity.finishing);
        // The caller uid of the new activity should be the current real caller.
        assertEquals(starter.mRequest.callingUid, secondActivity.getUid());
    }

    @Test
    public void testShouldUpRecreateTaskLockedWithCorrectAffinityFormat() {
        final String affinity = "affinity";
        final ActivityRecord activity = new ActivityBuilder(mAtm).setAffinity(affinity)
                .setUid(Binder.getCallingUid()).setCreateTask(true).build();
        final Task task = activity.getTask();
        task.affinity = activity.taskAffinity;

        assertFalse(task.shouldUpRecreateTaskLocked(activity, affinity));
    }

    @Test
    public void testShouldUpRecreateTaskLockedWithWrongAffinityFormat() {
        final String affinity = "affinity";
        final ActivityRecord activity = new ActivityBuilder(mAtm).setAffinity(affinity)
                .setUid(Binder.getCallingUid()).setCreateTask(true).build();
        final Task task = activity.getTask();
        task.affinity = activity.taskAffinity;
        final String fakeAffinity = activity.getUid() + activity.taskAffinity;

        assertTrue(task.shouldUpRecreateTaskLocked(activity, fakeAffinity));
    }

    @Test
    public void testResetTaskWithFinishingActivities() {
        final ActivityRecord taskTop = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = taskTop.getTask();
        // Make all activities in the task are finishing to simulate Task#getTopActivity
        // returns null.
        taskTop.finishing = true;

        final ActivityRecord newR = new ActivityBuilder(mAtm).build();
        final ActivityRecord result = task.resetTaskIfNeeded(taskTop, newR);
        assertThat(result).isEqualTo(taskTop);
    }

    @Test
    public void testIterateOccludedActivity() {
        final ArrayList<ActivityRecord> occludedActivities = new ArrayList<>();
        final Consumer<ActivityRecord> handleOccludedActivity = occludedActivities::add;
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord bottomActivity = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        // Top activity occludes bottom activity.
        doReturn(true).when(task).shouldBeVisible(any());
        assertTrue(topActivity.shouldBeVisible());
        assertFalse(bottomActivity.shouldBeVisible());

        task.forAllOccludedActivities(handleOccludedActivity);
        assertThat(occludedActivities).containsExactly(bottomActivity);

        // Top activity doesn't occlude parent, so the bottom activity is not occluded.
        doReturn(false).when(topActivity).occludesParent();
        assertTrue(bottomActivity.shouldBeVisible());

        occludedActivities.clear();
        task.forAllOccludedActivities(handleOccludedActivity);
        assertThat(occludedActivities).isEmpty();

        // A finishing activity should not occlude other activities behind.
        final ActivityRecord finishingActivity = new ActivityBuilder(mAtm).setTask(task).build();
        finishingActivity.finishing = true;
        doCallRealMethod().when(finishingActivity).occludesParent();
        assertTrue(topActivity.shouldBeVisible());
        assertTrue(bottomActivity.shouldBeVisible());

        occludedActivities.clear();
        task.forAllOccludedActivities(handleOccludedActivity);
        assertThat(occludedActivities).isEmpty();
    }

    @Test
    public void testClearUnknownAppVisibilityBehindFullscreenActivity() {
        final UnknownAppVisibilityController unknownAppVisibilityController =
                mDefaultTaskDisplayArea.mDisplayContent.mUnknownAppVisibilityController;
        final KeyguardController keyguardController = mSupervisor.getKeyguardController();
        doReturn(true).when(keyguardController).isKeyguardLocked();

        // Start 2 activities that their processes have not yet started.
        final ActivityRecord[] activities = new ActivityRecord[2];
        mSupervisor.beginDeferResume();
        final Task task = new TaskBuilder(mSupervisor).build();
        for (int i = 0; i < activities.length; i++) {
            final ActivityRecord r = new ActivityBuilder(mAtm).setTask(task).build();
            activities[i] = r;
            doReturn(null).when(mAtm).getProcessController(
                    eq(r.processName), eq(r.info.applicationInfo.uid));
            r.setState(Task.ActivityState.INITIALIZING, "test");
            // Ensure precondition that the activity is opaque.
            assertTrue(r.occludesParent());
            mSupervisor.startSpecificActivity(r, false /* andResume */,
                    false /* checkConfig */);
        }
        mSupervisor.endDeferResume();

        setBooted(mAtm);
        // 2 activities are started while keyguard is locked, so they are waiting to be resolved.
        assertFalse(unknownAppVisibilityController.allResolved());

        // Assume the top activity is going to resume and
        // {@link RootWindowContainer#cancelInitializingActivities} should clear the unknown
        // visibility records that are occluded.
        task.resumeTopActivityUncheckedLocked(null /* prev */, null /* options */);
        // Assume the top activity relayouted, just remove it directly.
        unknownAppVisibilityController.appRemovedOrHidden(activities[1]);
        // All unresolved records should be removed.
        assertTrue(unknownAppVisibilityController.allResolved());
    }

    @Test
    public void testNonTopVisibleActivityNotResume() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityRecord nonTopVisibleActivity =
                new ActivityBuilder(mAtm).setTask(task).build();
        new ActivityBuilder(mAtm).setTask(task).build();
        doReturn(false).when(nonTopVisibleActivity).attachedToProcess();
        doReturn(true).when(nonTopVisibleActivity).shouldBeVisibleUnchecked();
        doNothing().when(mSupervisor).startSpecificActivity(any(), anyBoolean(),
                anyBoolean());

        task.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */);
        verify(mSupervisor).startSpecificActivity(any(), eq(false) /* andResume */,
                anyBoolean());
    }

    private boolean isAssistantOnTop() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_assistantOnTopOfDream);
    }

    private void verifyShouldSleepActivities(boolean focusedStack,
            boolean keyguardGoingAway, boolean displaySleeping, boolean isDefaultDisplay,
            boolean expected) {
        final Task task = new TaskBuilder(mSupervisor).build();
        final DisplayContent display = mock(DisplayContent.class);
        final KeyguardController keyguardController = mSupervisor.getKeyguardController();
        display.isDefaultDisplay = isDefaultDisplay;

        task.mDisplayContent = display;
        doReturn(keyguardGoingAway).when(keyguardController).isKeyguardGoingAway();
        doReturn(displaySleeping).when(display).isSleeping();
        doReturn(focusedStack).when(task).isFocusedRootTaskOnDisplay();

        assertEquals(expected, task.shouldSleepActivities());
    }

    private static class RootTaskOrderChangedListener
            implements OnRootTaskOrderChangedListener {
        public boolean mChanged = false;

        @Override
        public void onRootTaskOrderChanged(Task rootTask) {
            mChanged = true;
        }
    }
}
