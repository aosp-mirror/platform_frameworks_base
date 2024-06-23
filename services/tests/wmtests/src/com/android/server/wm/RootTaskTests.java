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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.DESTROYED;
import static com.android.server.wm.ActivityRecord.State.DESTROYING;
import static com.android.server.wm.ActivityRecord.State.FINISHING;
import static com.android.server.wm.ActivityRecord.State.INITIALIZING;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;
import static com.android.server.wm.Task.REPARENT_KEEP_ROOT_TASK_AT_FRONT;
import static com.android.server.wm.Task.REPARENT_MOVE_ROOT_TASK_TO_FRONT;
import static com.android.server.wm.TaskDisplayArea.getRootTaskAbove;
import static com.android.server.wm.TaskFragment.TASK_FRAGMENT_VISIBILITY_INVISIBLE;
import static com.android.server.wm.TaskFragment.TASK_FRAGMENT_VISIBILITY_VISIBLE;
import static com.android.server.wm.TaskFragment.TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the root {@link Task} behavior.
 *
 * Build/Install/Run:
 *  atest WmTests:RootTaskTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RootTaskTests extends WindowTestsBase {

    private TaskDisplayArea mDefaultTaskDisplayArea;

    @Before
    public void setUp() throws Exception {
        mDefaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
    }

    @Test
    public void testRootTaskPositionChildAt() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task1 = createTaskInRootTask(rootTask, 0 /* userId */);
        final Task task2 = createTaskInRootTask(rootTask, 1 /* userId */);

        // Current user root task should be moved to top.
        rootTask.positionChildAt(WindowContainer.POSITION_TOP, task1, false /* includingParents */);
        assertEquals(rootTask.mChildren.get(0), task2);
        assertEquals(rootTask.mChildren.get(1), task1);

        // Non-current user won't be moved to top.
        rootTask.positionChildAt(WindowContainer.POSITION_TOP, task2, false /* includingParents */);
        assertEquals(rootTask.mChildren.get(0), task2);
        assertEquals(rootTask.mChildren.get(1), task1);

        // Non-leaf task should be moved to top regardless of the user id.
        createTaskInRootTask(task2, 0 /* userId */);
        createTaskInRootTask(task2, 1 /* userId */);
        rootTask.positionChildAt(WindowContainer.POSITION_TOP, task2, false /* includingParents */);
        assertEquals(rootTask.mChildren.get(0), task1);
        assertEquals(rootTask.mChildren.get(1), task2);
    }

    @Test
    public void testClosingAppDifferentTaskOrientation() {
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        activity1.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent);
        activity2.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        final WindowContainer parent = activity1.getTask().getParent();
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, parent.getOrientation());
        activity2.setVisibleRequested(false);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, parent.getOrientation());
    }

    @Test
    public void testMoveTaskToBackDifferentTaskOrientation() {
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        activity1.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent);
        activity2.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        final WindowContainer parent = activity1.getTask().getParent();
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, parent.getOrientation());
    }

    @Test
    public void testRootTaskRemoveImmediately() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        assertEquals(rootTask, task.getRootTask());

        // Remove root task and check if its child is also removed.
        rootTask.removeImmediately();
        assertNull(rootTask.getDisplayContent());
        assertNull(task.getParent());
    }

    @Test
    public void testRemoveContainer() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);

        assertNotNull(rootTask);
        assertNotNull(task);
        rootTask.removeIfPossible();
        // Assert that the container was removed.
        assertNull(rootTask.getParent());
        assertEquals(0, rootTask.getChildCount());
        assertNull(rootTask.getDisplayContent());
        assertNull(task.getDisplayContent());
        assertNull(task.getParent());
    }

    @Test
    public void testRemoveContainer_deferRemoval() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);

        // Root task removal is deferred if one of its child is animating.
        doReturn(rootTask).when(task).getAnimatingContainer(
                eq(TRANSITION | CHILDREN), anyInt());

        rootTask.removeIfPossible();
        // For the case of deferred removal the task controller will still be connected to its
        // container until the root task window container is removed.
        assertNotNull(rootTask.getParent());
        assertNotEquals(0, rootTask.getChildCount());
        assertNotNull(task);

        rootTask.removeImmediately();
        // After removing, the task will be isolated.
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
    }

    @Test
    public void testReparent() {
        // Create first root task on primary display.
        final Task rootTask1 = createTask(mDisplayContent);
        final Task task1 = createTaskInRootTask(rootTask1, 0 /* userId */);

        // Create second display and put second root task on it.
        final DisplayContent dc = createNewDisplay();
        final Task rootTask2 = createTask(dc);

        // Reparent
        clearInvocations(task1); // reset the number of onDisplayChanged for task.
        rootTask1.reparent(dc.getDefaultTaskDisplayArea(), true /* onTop */);
        assertEquals(dc, rootTask1.getDisplayContent());
        final int stack1PositionInParent = rootTask1.getParent().mChildren.indexOf(rootTask1);
        final int stack2PositionInParent = rootTask1.getParent().mChildren.indexOf(rootTask2);
        assertEquals(stack1PositionInParent, stack2PositionInParent + 1);
        verify(task1, times(1)).onDisplayChanged(any());
    }

    @Test
    public void testActivityAndTaskGetsProperType() {
        final Task task1 = new TaskBuilder(mSupervisor).build();
        ActivityRecord activity1 = createNonAttachedActivityRecord(mDisplayContent);

        // First activity should become standard
        task1.addChild(activity1, 0);
        assertEquals(WindowConfiguration.ACTIVITY_TYPE_STANDARD, activity1.getActivityType());
        assertEquals(WindowConfiguration.ACTIVITY_TYPE_STANDARD, task1.getActivityType());

        // Second activity should also become standard
        ActivityRecord activity2 = createNonAttachedActivityRecord(mDisplayContent);
        task1.addChild(activity2, WindowContainer.POSITION_TOP);
        assertEquals(WindowConfiguration.ACTIVITY_TYPE_STANDARD, activity2.getActivityType());
        assertEquals(WindowConfiguration.ACTIVITY_TYPE_STANDARD, task1.getActivityType());
    }

    @Test
    public void testResumedActivity() {
        final ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = r.getTask();
        assertNull(task.getTopResumedActivity());
        r.setState(RESUMED, "testResumedActivity");
        assertEquals(r, task.getTopResumedActivity());
        r.setState(PAUSING, "testResumedActivity");
        assertNull(task.getTopResumedActivity());
    }

    @Test
    public void testResumedActivityFromTaskReparenting() {
        final Task rootTask = new TaskBuilder(mSupervisor).setOnTop(true).build();
        final ActivityRecord r = new ActivityBuilder(mAtm)
                .setCreateTask(true).setParentTask(rootTask).build();
        final Task task = r.getTask();
        // Ensure moving task between two root tasks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromTaskReparenting");
        assertEquals(r, rootTask.getTopResumedActivity());

        final Task destRootTask = new TaskBuilder(mSupervisor).setOnTop(true).build();
        task.reparent(destRootTask, true /* toTop */, REPARENT_KEEP_ROOT_TASK_AT_FRONT,
                false /* animate */, true /* deferResume*/,
                "testResumedActivityFromTaskReparenting");

        assertNull(rootTask.getTopResumedActivity());
        assertEquals(r, destRootTask.getTopResumedActivity());
    }

    @Test
    public void testResumedActivityFromActivityReparenting() {
        final Task rootTask = new TaskBuilder(mSupervisor).setOnTop(true).build();
        final ActivityRecord r = new ActivityBuilder(mAtm)
                .setCreateTask(true).setParentTask(rootTask).build();
        final Task task = r.getTask();
        // Ensure moving task between two root tasks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromActivityReparenting");
        assertEquals(r, rootTask.getTopResumedActivity());

        final Task destRootTask = new TaskBuilder(mSupervisor).setOnTop(true).build();
        task.reparent(destRootTask, true /*toTop*/, REPARENT_MOVE_ROOT_TASK_TO_FRONT,
                false /* animate */, false /* deferResume*/,
                "testResumedActivityFromActivityReparenting");

        assertNull(rootTask.getTopResumedActivity());
        assertEquals(r, destRootTask.getTopResumedActivity());
    }

    @Test
    public void testSplitScreenMoveToBack() {
        TestSplitOrganizer organizer = new TestSplitOrganizer(mAtm);
        final Task primaryTask = organizer.createTaskToPrimary(true /* onTop */);
        final Task secondaryTask = organizer.createTaskToSecondary(true /* onTop */);
        final Task homeRoot = mDefaultTaskDisplayArea.getRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        mDefaultTaskDisplayArea.positionChildAt(POSITION_TOP, organizer.mPrimary,
                false /* includingParents */);

        // Move primary to back.
        primaryTask.moveToBack("test", null /* task */);

        // Assert that the primaryTask is now below home in its parent but primary is left alone.
        assertEquals(0, organizer.mPrimary.getChildCount());
        // Assert that root task is at the bottom.
        assertEquals(0, getTaskIndexOf(mDefaultTaskDisplayArea, primaryTask));
        assertEquals(1, organizer.mPrimary.compareTo(organizer.mSecondary));
        assertEquals(1, homeRoot.compareTo(primaryTask));
        assertEquals(homeRoot.getParent(), primaryTask.getParent());

        // Make sure windowing modes are correct
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, organizer.mPrimary.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, secondaryTask.getWindowingMode());
        // Ensure no longer in splitscreen.
        assertEquals(WINDOWING_MODE_FULLSCREEN, primaryTask.getWindowingMode());
        // Ensure that the override mode is restored to undefined
        assertEquals(WINDOWING_MODE_UNDEFINED, primaryTask.getRequestedOverrideWindowingMode());

        // Move secondary to back via parent (should be equivalent)
        organizer.mSecondary.moveToBack("test", secondaryTask);

        // Assert that it is now in back and left in secondary split
        assertEquals(0, organizer.mSecondary.getChildCount());
        assertEquals(1, homeRoot.compareTo(primaryTask));
        assertEquals(1, primaryTask.compareTo(secondaryTask));
        assertEquals(homeRoot.getParent(), secondaryTask.getParent());
    }

    @Test
    public void testRemoveOrganizedTask_UpdateRootTaskReference() {
        final Task rootHomeTask = mDefaultTaskDisplayArea.getRootHomeTask();
        final ActivityRecord homeActivity = new ActivityBuilder(mAtm)
                .setTask(rootHomeTask)
                .build();
        final Task secondaryRootTask = mAtm.mTaskOrganizerController.createRootTask(
                rootHomeTask.getDisplayContent(), WINDOWING_MODE_MULTI_WINDOW, null);

        rootHomeTask.reparent(secondaryRootTask, POSITION_TOP);
        assertEquals(secondaryRootTask, rootHomeTask.getParent());

        // This should call to {@link TaskDisplayArea#removeRootTaskReferenceIfNeeded}.
        homeActivity.removeImmediately();
        assertNull(mDefaultTaskDisplayArea.getRootHomeTask());
    }

    @Test
    public void testRootTaskInheritsDisplayWindowingMode() {
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
    public void testRootTaskOverridesDisplayWindowingMode() {
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
        result.init(r.getActivityType(), r.taskAffinity, r.intent, r.info);
        result.process(task);

        assertEquals(r, task.getTopNonFinishingActivity(false /* includeOverlays */));
        assertEquals(taskOverlay, task.getTopNonFinishingActivity(true /* includeOverlays */));
        assertNotNull(result.mIdealRecord);
    }

    @Test
    public void testFindTaskAlias() {
        final String targetActivity = "target.activity";
        final String aliasActivity = "alias.activity";
        final ComponentName target = new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME,
                targetActivity);
        final ComponentName alias = new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME,
                aliasActivity);
        final Task parentTask = new TaskBuilder(mSupervisor).build();
        final Task task = new TaskBuilder(mSupervisor).setParentTask(parentTask).build();
        task.origActivity = alias;
        task.realActivity = target;
        new ActivityBuilder(mAtm).setComponent(target).setTask(task).setTargetActivity(
                targetActivity).build();

        // Using target activity to find task.
        final ActivityRecord r1 = new ActivityBuilder(mAtm).setComponent(
                target).setTargetActivity(targetActivity).build();
        RootWindowContainer.FindTaskResult result = new RootWindowContainer.FindTaskResult();
        result.init(r1.getActivityType(), r1.taskAffinity, r1.intent, r1.info);
        result.process(parentTask);
        assertThat(result.mIdealRecord).isNotNull();

        // Using alias activity to find task.
        final ActivityRecord r2 = new ActivityBuilder(mAtm).setComponent(
                alias).setTargetActivity(targetActivity).build();
        result = new RootWindowContainer.FindTaskResult();
        result.init(r2.getActivityType(), r2.taskAffinity, r2.intent, r2.info);
        result.process(parentTask);
        assertThat(result.mIdealRecord).isNotNull();
    }

    @Test
    public void testMoveRootTaskToBackIncludingParent() {
        final TaskDisplayArea taskDisplayArea = addNewDisplayContentAt(DisplayContent.POSITION_TOP)
                .getDefaultTaskDisplayArea();
        final Task rootTask1 = createTaskForShouldBeVisibleTest(taskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */,
                true /* twoLevelTask */);
        final Task rootTask2 = createTaskForShouldBeVisibleTest(taskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */,
                true /* twoLevelTask */);

        // Do not move display to back because there is still another root task.
        rootTask2.moveToBack("testMoveRootTaskToBackIncludingParent", rootTask2.getTopMostTask());
        verify(rootTask2).positionChildAtBottom(any(), eq(false) /* includingParents */);

        // Also move display to back because there is only one root task left.
        taskDisplayArea.removeRootTask(rootTask1);
        rootTask2.moveToBack("testMoveRootTaskToBackIncludingParent", rootTask2.getTopMostTask());
        verify(rootTask2).positionChildAtBottom(any(), eq(true) /* includingParents */);
    }

    @Test
    public void testShouldBeVisible_Fullscreen() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task pinnedRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // Add an activity to the pinned root task so it isn't considered empty for visibility
        // check.
        final ActivityRecord pinnedActivity = new ActivityBuilder(mAtm)
                .setTask(pinnedRootTask)
                .build();

        assertTrue(homeRootTask.shouldBeVisible(null /* starting */));
        assertTrue(pinnedRootTask.shouldBeVisible(null /* starting */));

        final Task fullscreenRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // Home root task shouldn't be visible behind an opaque fullscreen root task, but pinned
        // root task should be visible since it is always on-top.
        doReturn(false).when(fullscreenRootTask).isTranslucent(any());
        assertFalse(homeRootTask.shouldBeVisible(null /* starting */));
        assertTrue(pinnedRootTask.shouldBeVisible(null /* starting */));
        assertTrue(fullscreenRootTask.shouldBeVisible(null /* starting */));

        // Home root task should be visible behind a translucent fullscreen root task.
        doReturn(true).when(fullscreenRootTask).isTranslucent(any());
        assertTrue(homeRootTask.shouldBeVisible(null /* starting */));
        assertTrue(pinnedRootTask.shouldBeVisible(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_SplitScreen() {
        // Fullscreen root task for this test.
        final Task fullScreenRootTask = createTaskForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        final TestSplitOrganizer organizer = new TestSplitOrganizer(mAtm);
        final Task splitScreenPrimary = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task splitScreenSecondary = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        organizer.putTaskToPrimary(splitScreenPrimary, true /* onTop */);
        organizer.putTaskToSecondary(splitScreenSecondary, true /* onTop */);
        splitScreenPrimary.moveToFront("testShouldBeVisible_SplitScreen");
        splitScreenSecondary.moveToFront("testShouldBeVisible_SplitScreen");

        // Fullscreen root task shouldn't be visible if both halves of split-screen are opaque.
        doReturn(false).when(organizer.mPrimary).isTranslucent(any());
        doReturn(false).when(organizer.mSecondary).isTranslucent(any());
        doReturn(false).when(splitScreenPrimary).isTranslucent(any());
        doReturn(false).when(splitScreenSecondary).isTranslucent(any());
        assertFalse(fullScreenRootTask.shouldBeVisible(null /* starting */));
        assertTrue(organizer.mPrimary.shouldBeVisible(null /* starting */));
        assertTrue(organizer.mSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));

        // Fullscreen root task shouldn't be visible if one of the halves of split-screen
        // is translucent.
        doReturn(true).when(splitScreenPrimary).isTranslucent(any());
        assertFalse(fullScreenRootTask.shouldBeVisible(null /* starting */));
        assertTrue(organizer.mPrimary.shouldBeVisible(null /* starting */));
        assertTrue(organizer.mSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));

        final Task splitScreenSecondary2 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        organizer.putTaskToSecondary(splitScreenSecondary2, true /* onTop */);
        // First split-screen secondary shouldn't be visible behind another opaque split-split
        // secondary.
        doReturn(false).when(splitScreenSecondary2).isTranslucent(any());
        assertTrue(organizer.mSecondary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                organizer.mSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // First split-screen secondary should be visible behind another translucent split-screen
        // secondary.
        doReturn(true).when(splitScreenSecondary2).isTranslucent(any());
        assertTrue(organizer.mSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                organizer.mSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        final Task assistantRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT, true /* onTop */);

        // Split-screen root tasks shouldn't be visible behind an opaque fullscreen root task.
        doReturn(false).when(assistantRootTask).isTranslucent(any());
        assertTrue(assistantRootTask.shouldBeVisible(null /* starting */));
        assertFalse(organizer.mPrimary.shouldBeVisible(null /* starting */));
        assertFalse(organizer.mSecondary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                assistantRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // Split-screen root tasks should be visible behind a translucent fullscreen root task.
        doReturn(true).when(assistantRootTask).isTranslucent(any());
        assertTrue(assistantRootTask.shouldBeVisible(null /* starting */));
        assertTrue(organizer.mPrimary.shouldBeVisible(null /* starting */));
        assertTrue(organizer.mSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                assistantRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                organizer.mPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                organizer.mSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary2.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_MultiLevel() {
        TestSplitOrganizer organizer = new TestSplitOrganizer(mAtm);
        final Task splitPrimary = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_UNDEFINED, true /* onTop */);
        final Task splitSecondary = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_UNDEFINED, true /* onTop */);

        doReturn(false).when(splitPrimary).isTranslucent(any());
        doReturn(false).when(splitSecondary).isTranslucent(any());

        // Re-parent tasks to split.
        organizer.putTaskToPrimary(splitPrimary, true /* onTop */);
        organizer.putTaskToSecondary(splitSecondary, true /* onTop */);
        // Reparented tasks should be visible.
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                splitPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                splitSecondary.getVisibility(null /* starting */));

        // Add fullscreen translucent task that partially occludes split tasks
        final Task translucentRootTask = createStandardRootTaskForVisibilityTest(
                WINDOWING_MODE_FULLSCREEN, true /* translucent */);
        // Fullscreen translucent task should be visible
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                translucentRootTask.getVisibility(null /* starting */));
        // Split tasks should be visible behind translucent
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitSecondary.getVisibility(null /* starting */));

        // Hide split-secondary
        organizer.mSecondary.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, true /* set */);
        // Home split secondary and home task should be invisible.
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                splitSecondary.getVisibility(null /* starting */));

        // Put another task on top of primary split.
        final Task topSplitPrimary = new TaskBuilder(mSupervisor).setParentTask(organizer.mPrimary)
                .setCreateActivity(true).build();
        doReturn(false).when(topSplitPrimary).isTranslucent(any());
        // Convert the fullscreen translucent task to opaque.
        doReturn(false).when(translucentRootTask).isTranslucent(any());
        translucentRootTask.moveToFront("test");
        // The tasks of primary split are occluded by the fullscreen opaque task.
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                organizer.mPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                topSplitPrimary.getVisibility(null /* starting */));
        // Make primary split root transient-hide.
        spyOn(splitPrimary.mTransitionController);
        doReturn(true).when(splitPrimary.mTransitionController).isTransientVisible(
                organizer.mPrimary);
        // The split root and its top become visible.
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                organizer.mPrimary.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                topSplitPrimary.getVisibility(null /* starting */));
        // The bottom of primary split becomes invisible because it is occluded by topSplitPrimary.
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                splitPrimary.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucent() {
        final Task bottomRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                translucentRootTask.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucentAndOpaque() {
        final Task bottomRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task opaqueRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);

        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                bottomRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                translucentRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                opaqueRootTask.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindOpaqueAndTranslucent() {
        final Task bottomRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task opaqueRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                bottomRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                opaqueRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                translucentRootTask.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenTranslucentBehindTranslucent() {
        final Task bottomTranslucentRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task translucentRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomTranslucentRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                translucentRootTask.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenTranslucentBehindOpaque() {
        final Task bottomTranslucentRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task opaqueRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);

        assertEquals(TASK_FRAGMENT_VISIBILITY_INVISIBLE,
                bottomTranslucentRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                opaqueRootTask.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucentAndPip() {
        final Task bottomRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final Task translucentRootTask =
                createStandardRootTaskForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final Task pinnedRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomRootTask.getVisibility(null /* starting */));
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                translucentRootTask.getVisibility(null /* starting */));
        // Add an activity to the pinned root task so it isn't considered empty for visibility
        // check.
        final ActivityRecord pinnedActivity = new ActivityBuilder(mAtm)
                .setTask(pinnedRootTask)
                .build();
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE,
                pinnedRootTask.getVisibility(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_Finishing() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        ActivityRecord topRunningHomeActivity = homeRootTask.topRunningActivity();
        if (topRunningHomeActivity == null) {
            topRunningHomeActivity = new ActivityBuilder(mAtm)
                    .setTask(homeRootTask)
                    .build();
        }

        final Task translucentRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        doReturn(true).when(translucentRootTask).isTranslucent(any());

        assertTrue(homeRootTask.shouldBeVisible(null /* starting */));
        assertTrue(translucentRootTask.shouldBeVisible(null /* starting */));

        topRunningHomeActivity.finishing = true;
        final ActivityRecord topRunningTranslucentActivity =
                translucentRootTask.topRunningActivity();
        topRunningTranslucentActivity.finishing = true;

        // Home root task should be visible even there are no running activities.
        assertTrue(homeRootTask.shouldBeVisible(null /* starting */));
        // Home should be visible if we are starting an activity within it.
        assertTrue(homeRootTask.shouldBeVisible(topRunningHomeActivity /* starting */));
        // The translucent root task shouldn't be visible since its activity marked as finishing.
        assertFalse(translucentRootTask.shouldBeVisible(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_FullscreenBehindTranslucentInHomeRootTask() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setParentTask(homeRootTask)
                .setCreateTask(true)
                .build();
        final Task task = firstActivity.getTask();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(task)
                .build();

        doReturn(false).when(secondActivity).occludesParent();
        homeRootTask.ensureActivitiesVisible(null /* starting */);

        assertTrue(firstActivity.shouldBeVisible());
    }

    @Test
    public void testMoveHomeRootTaskBehindBottomMostVisible_NoMoveHomeBehindFullscreen() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeRootTask).isTranslucent(any());
        doReturn(false).when(fullscreenRootTask).isTranslucent(any());

        // Ensure that we don't move the home root task if it is already behind the top fullscreen
        // root task.
        int homeRootTaskIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask);
        assertEquals(fullscreenRootTask, getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeRootTask);
        assertEquals(homeRootTaskIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask));
    }

    @Test
    public void testMoveHomeRootTaskBehindBottomMostVisible_NoMoveHomeBehindTranslucent() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeRootTask).isTranslucent(any());
        doReturn(true).when(fullscreenRootTask).isTranslucent(any());

        // Ensure that we don't move the home root task if it is already behind the top fullscreen
        // root task.
        int homeRootTaskIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask);
        assertEquals(fullscreenRootTask, getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeRootTask);
        assertEquals(homeRootTaskIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask));
    }

    @Test
    public void testMoveHomeRootTaskBehindBottomMostVisible_NoMoveHomeOnTop() {
        final Task fullscreenRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        doReturn(false).when(homeRootTask).isTranslucent(any());
        doReturn(false).when(fullscreenRootTask).isTranslucent(any());

        // Ensure we don't move the home root task if it is already on top
        int homeRootTaskIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask);
        assertNull(getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeRootTask);
        assertEquals(homeRootTaskIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask));
    }

    @Test
    public void testMoveHomeRootTaskBehindBottomMostVisible_MoveHomeBehindFullscreen() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenRootTask1 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task fullscreenRootTask2 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task pinnedRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeRootTask).isTranslucent(any());
        doReturn(false).when(fullscreenRootTask1).isTranslucent(any());
        doReturn(false).when(fullscreenRootTask2).isTranslucent(any());

        // Ensure that we move the home root task behind the bottom most fullscreen root task,
        // ignoring the pinned root task.
        assertEquals(fullscreenRootTask1, getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeRootTask);
        assertEquals(fullscreenRootTask2, getRootTaskAbove(homeRootTask));
    }

    @Test
    public void
            testMoveHomeRootTaskBehindBottomMostVisible_MoveHomeBehindFullscreenAndTranslucent() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task fullscreenRootTask1 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task fullscreenRootTask2 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeRootTask).isTranslucent(any());
        doReturn(false).when(fullscreenRootTask1).isTranslucent(any());
        doReturn(true).when(fullscreenRootTask2).isTranslucent(any());

        // Ensure that we move the home root task behind the bottom most non-translucent fullscreen
        // root task.
        assertEquals(fullscreenRootTask1, getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindBottomMostVisibleRootTask(homeRootTask);
        assertEquals(fullscreenRootTask1, getRootTaskAbove(homeRootTask));
    }

    @Test
    public void testMoveHomeRootTaskBehindRootTask_BehindHomeRootTask() {
        final Task fullscreenRootTask1 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task fullscreenRootTask2 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        doReturn(false).when(homeRootTask).isTranslucent(any());
        doReturn(false).when(fullscreenRootTask1).isTranslucent(any());
        doReturn(false).when(fullscreenRootTask2).isTranslucent(any());

        // Ensure we don't move the home root task behind itself
        int homeRootTaskIndex = getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask);
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeRootTask, homeRootTask);
        assertEquals(homeRootTaskIndex, getTaskIndexOf(mDefaultTaskDisplayArea, homeRootTask));
    }

    @Test
    public void testMoveHomeRootTaskBehindRootTask() {
        final Task fullscreenRootTask1 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task fullscreenRootTask2 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task fullscreenRootTask3 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task fullscreenRootTask4 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeRootTask, fullscreenRootTask1);
        assertEquals(fullscreenRootTask1, getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeRootTask, fullscreenRootTask2);
        assertEquals(fullscreenRootTask2, getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeRootTask, fullscreenRootTask4);
        assertEquals(fullscreenRootTask4, getRootTaskAbove(homeRootTask));
        mDefaultTaskDisplayArea.moveRootTaskBehindRootTask(homeRootTask, fullscreenRootTask2);
        assertEquals(fullscreenRootTask2, getRootTaskAbove(homeRootTask));
    }

    @Test
    public void testSetAlwaysOnTop() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final Task pinnedRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(pinnedRootTask, getRootTaskAbove(homeRootTask));

        final Task alwaysOnTopRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        alwaysOnTopRootTask.setAlwaysOnTop(true);
        assertTrue(alwaysOnTopRootTask.isAlwaysOnTop());
        // Ensure (non-pinned) always on top root task is put below pinned root task.
        assertEquals(pinnedRootTask, getRootTaskAbove(alwaysOnTopRootTask));

        final Task nonAlwaysOnTopRootTask = createTaskForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        // Ensure non always on top root task is put below always on top root tasks.
        assertEquals(alwaysOnTopRootTask, getRootTaskAbove(nonAlwaysOnTopRootTask));

        final Task alwaysOnTopRootTask2 = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        alwaysOnTopRootTask2.setAlwaysOnTop(true);
        assertTrue(alwaysOnTopRootTask2.isAlwaysOnTop());
        // Ensure newly created always on top root task is placed above other all always on top
        // root tasks.
        assertEquals(pinnedRootTask, getRootTaskAbove(alwaysOnTopRootTask2));

        alwaysOnTopRootTask2.setAlwaysOnTop(false);
        // Ensure, when always on top is turned off for a root task, the root task is put just below
        // all other always on top root tasks.
        assertEquals(alwaysOnTopRootTask, getRootTaskAbove(alwaysOnTopRootTask2));
        alwaysOnTopRootTask2.setAlwaysOnTop(true);

        // Ensure always on top state changes properly when windowing mode changes.
        alwaysOnTopRootTask2.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertFalse(alwaysOnTopRootTask2.isAlwaysOnTop());
        assertEquals(alwaysOnTopRootTask, getRootTaskAbove(alwaysOnTopRootTask2));
        alwaysOnTopRootTask2.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTrue(alwaysOnTopRootTask2.isAlwaysOnTop());
        assertEquals(pinnedRootTask, getRootTaskAbove(alwaysOnTopRootTask2));
    }

    private Task createStandardRootTaskForVisibilityTest(int windowingMode,
            boolean translucent) {
        final Task rootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                windowingMode, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        doReturn(translucent).when(rootTask).isTranslucent(any());
        return rootTask;
    }

    private Task createTaskForShouldBeVisibleTest(
            TaskDisplayArea taskDisplayArea, int windowingMode, int activityType, boolean onTop) {
        return createTaskForShouldBeVisibleTest(taskDisplayArea,
                windowingMode, activityType, onTop, false /* twoLevelTask */);
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    private Task createTaskForShouldBeVisibleTest(TaskDisplayArea taskDisplayArea,
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
                    .setActivityType(activityType)
                    .setOnTop(onTop)
                    .setCreateActivity(true)
                    .setCreateParentTask(true)
                    .build().getRootTask();
            task.setWindowingMode(windowingMode);
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
        // Simulate the process is dead
        activity.setVisibleRequested(false);
        activity.setState(DESTROYED, "Test");

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

        task.startPausing(false /* uiSleeping */, topActivity,
                "test");
        verify(task).completePause(anyBoolean(), eq(topActivity));
    }

    @Test
    public void testWontFinishHomeRootTaskImmediately() {
        final Task homeRootTask = createTaskForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        ActivityRecord activity = homeRootTask.topRunningActivity();
        if (activity == null) {
            activity = new ActivityBuilder(mAtm)
                    .setParentTask(homeRootTask)
                    .setCreateTask(true)
                    .build();
        }

        // Home root task should not be destroyed immediately.
        final ActivityRecord activity1 = finishTopActivity(homeRootTask);
        assertEquals(FINISHING, activity1.getState());
    }

    @Test
    public void testFinishCurrentActivity() {
        // Create 2 activities on a new display.
        final DisplayContent display = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        final Task rootTask1 = createTaskForShouldBeVisibleTest(display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task rootTask2 = createTaskForShouldBeVisibleTest(display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // There is still an activity1 in rootTask1 so the activity2 should be added to finishing
        // list that will be destroyed until idle.
        rootTask2.getTopNonFinishingActivity().setVisibleRequested(true);
        final ActivityRecord activity2 = finishTopActivity(rootTask2);
        assertEquals(STOPPING, activity2.getState());
        assertThat(mSupervisor.mStoppingActivities).contains(activity2);

        // The display becomes empty. Since there is no next activity to be idle, the activity
        // should be destroyed immediately with updating configuration to restore original state.
        final ActivityRecord activity1 = finishTopActivity(rootTask1);
        assertEquals(DESTROYING, activity1.getState());
        verify(mRootWindowContainer).ensureVisibilityAndConfig(eq(null) /* starting */,
                eq(display.mDisplayId), anyBoolean());
    }

    private ActivityRecord finishTopActivity(Task task) {
        final ActivityRecord activity = task.topRunningActivity();
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
        verifyShouldSleepActivities(true /* focusedRootTask */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* isDefaultDisplay */, false /* expected */);
        verifyShouldSleepActivities(true /* focusedRootTask */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, false /* isDefaultDisplay */, true /* expected */);

        // When not the focused root task, defer to display sleeping state.
        verifyShouldSleepActivities(false /* focusedRootTask */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* isDefaultDisplay */, true /* expected */);

        // If keyguard is going away, defer to the display sleeping state.
        verifyShouldSleepActivities(true /* focusedRootTask */, false /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* isDefaultDisplay */, true /* expected */);
        verifyShouldSleepActivities(true /* focusedRootTask */, false /*keyguardGoingAway*/,
                false /* displaySleeping */, true /* isDefaultDisplay */, false /* expected */);
    }

    @Test
    public void testRootTaskOrderChangedOnRemoveRootTask() {
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
    public void testRootTaskOrderChangedOnAddPositionRootTask() {
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
    public void testRootTaskOrderChangedOnPositionRootTask() {
        RootTaskOrderChangedListener listener = new RootTaskOrderChangedListener();
        try {
            final Task fullscreenRootTask1 = createTaskForShouldBeVisibleTest(
                    mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                    true /* onTop */);
            mDefaultTaskDisplayArea.registerRootTaskOrderChangedListener(listener);
            mDefaultTaskDisplayArea.positionChildAt(POSITION_BOTTOM, fullscreenRootTask1,
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
                firstActivity.intent /* destIntent */, null /* resolvedType */,
                null /* destGrants */, 0 /* resultCode */, null /* resultData */,
                null /* resultGrants */));

        secondActivity.app.setThread(thread);
        assertTrue(task.navigateUpTo(secondActivity /* source record */,
                firstActivity.intent /* destIntent */, null /* resolvedType */,
                null /* destGrants */, 0 /* resultCode */, null /* resultData */,
                null /* resultGrants */));
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
    public void testClearUnknownAppVisibilityBehindFullscreenActivity() {
        final UnknownAppVisibilityController unknownAppVisibilityController =
                mDefaultTaskDisplayArea.mDisplayContent.mUnknownAppVisibilityController;
        final KeyguardController keyguardController = mSupervisor.getKeyguardController();
        doReturn(true).when(keyguardController).isKeyguardLocked(eq(DEFAULT_DISPLAY));

        // Start 2 activities that their processes have not yet started.
        final ActivityRecord[] activities = new ActivityRecord[2];
        mSupervisor.beginDeferResume();
        final Task task = new TaskBuilder(mSupervisor).build();
        for (int i = 0; i < activities.length; i++) {
            final ActivityRecord r = new ActivityBuilder(mAtm).setTask(task).build();
            activities[i] = r;
            doReturn(null).when(mAtm).getProcessController(
                    eq(r.processName), eq(r.info.applicationInfo.uid));
            r.setState(INITIALIZING, "test");
            // Ensure precondition that the activity is opaque.
            assertTrue(r.occludesParent());
            mSupervisor.startSpecificActivity(r, false /* andResume */,
                    false /* checkConfig */);
        }
        mSupervisor.endDeferResume();

        // 2 activities are started while keyguard is locked, so they are waiting to be resolved.
        assertFalse(unknownAppVisibilityController.allResolved());

        // Any common path that updates activity visibility should clear the unknown visibility
        // records that are no longer visible according to hierarchy.
        task.ensureActivitiesVisible(null /* starting */);
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
        // The scenario we are testing is when the app isn't visible yet.
        nonTopVisibleActivity.setVisible(false);
        nonTopVisibleActivity.setVisibleRequested(false);
        doReturn(false).when(nonTopVisibleActivity).attachedToProcess();
        doReturn(true).when(nonTopVisibleActivity).shouldBeVisibleUnchecked();
        doNothing().when(mSupervisor).startSpecificActivity(any(), anyBoolean(),
                anyBoolean());

        task.ensureActivitiesVisible(null /* starting */);
        verify(mSupervisor).startSpecificActivity(any(), eq(false) /* andResume */,
                anyBoolean());
    }

    private boolean isAssistantOnTop() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_assistantOnTopOfDream);
    }

    private void verifyShouldSleepActivities(boolean focusedRootTask,
            boolean keyguardGoingAway, boolean displaySleeping, boolean isDefaultDisplay,
            boolean expected) {
        final Task task = new TaskBuilder(mSupervisor).build();
        final DisplayContent display = mock(DisplayContent.class);
        final KeyguardController keyguardController = mSupervisor.getKeyguardController();
        display.isDefaultDisplay = isDefaultDisplay;

        task.mDisplayContent = display;
        doReturn(keyguardGoingAway).when(display).isKeyguardGoingAway();
        doReturn(displaySleeping).when(display).isSleeping();
        doReturn(focusedRootTask).when(task).isFocusedRootTaskOnDisplay();

        assertEquals(expected, task.shouldSleepActivities());
    }

    private static class RootTaskOrderChangedListener
            implements TaskDisplayArea.OnRootTaskOrderChangedListener {
        public boolean mChanged = false;

        @Override
        public void onRootTaskOrderChanged(Task rootTask) {
            mChanged = true;
        }
    }
}
