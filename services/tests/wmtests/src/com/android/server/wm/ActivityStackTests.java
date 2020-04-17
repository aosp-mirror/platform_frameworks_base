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
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.wm.ActivityStack.ActivityState.FINISHING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_INVISIBLE;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
import static com.android.server.wm.Task.REPARENT_MOVE_STACK_TO_FRONT;
import static com.android.server.wm.TaskDisplayArea.getStackAbove;
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
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ActivityStack} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStackTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityStackTests extends ActivityTestsBase {
    private TaskDisplayArea mDefaultTaskDisplayArea;
    private ActivityStack mStack;
    private Task mTask;

    @Before
    public void setUp() throws Exception {
        mDefaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        mStack = mDefaultTaskDisplayArea.createStack(WINDOWING_MODE_UNDEFINED,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        spyOn(mStack);
        mTask = new TaskBuilder(mSupervisor).setStack(mStack).build();
    }

    @Test
    public void testResumedActivity() {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        assertNull(mStack.getResumedActivity());
        r.setState(RESUMED, "testResumedActivity");
        assertEquals(r, mStack.getResumedActivity());
        r.setState(PAUSING, "testResumedActivity");
        assertNull(mStack.getResumedActivity());
    }

    @Test
    public void testResumedActivityFromTaskReparenting() {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        // Ensure moving task between two stacks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromTaskReparenting");
        assertEquals(r, mStack.getResumedActivity());

        final ActivityStack destStack = mDefaultTaskDisplayArea.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        mTask.reparent(destStack, true /* toTop */, Task.REPARENT_KEEP_STACK_AT_FRONT,
                false /* animate */, true /* deferResume*/,
                "testResumedActivityFromTaskReparenting");

        assertNull(mStack.getResumedActivity());
        assertEquals(r, destStack.getResumedActivity());
    }

    @Test
    public void testResumedActivityFromActivityReparenting() {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        // Ensure moving task between two stacks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromActivityReparenting");
        assertEquals(r, mStack.getResumedActivity());

        final ActivityStack destStack = mDefaultTaskDisplayArea.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTask.reparent(destStack, true /*toTop*/, REPARENT_MOVE_STACK_TO_FRONT, false, false,
                "testResumedActivityFromActivityReparenting");

        assertNull(mStack.getResumedActivity());
        assertEquals(r, destStack.getResumedActivity());
    }

    @Test
    public void testPrimarySplitScreenMoveToBack() {
        TestSplitOrganizer organizer = new TestSplitOrganizer(mService);
        // We're testing an edge case here where we have primary + fullscreen rather than secondary.
        organizer.setMoveToSecondaryOnEnter(false);

        // Create primary splitscreen stack.
        final ActivityStack primarySplitScreen = mDefaultTaskDisplayArea.createStack(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Assert windowing mode.
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, primarySplitScreen.getWindowingMode());

        // Move primary to back.
        primarySplitScreen.moveToBack("testPrimarySplitScreenToFullscreenWhenMovedToBack",
                null /* task */);

        // Assert that stack is at the bottom.
        assertEquals(0, mDefaultTaskDisplayArea.getIndexOf(primarySplitScreen));

        // Ensure no longer in splitscreen.
        assertEquals(WINDOWING_MODE_FULLSCREEN, primarySplitScreen.getWindowingMode());

        // Ensure that the override mode is restored to undefined
        assertEquals(WINDOWING_MODE_UNDEFINED,
                primarySplitScreen.getRequestedOverrideWindowingMode());
    }

    @Test
    public void testMoveToPrimarySplitScreenThenMoveToBack() {
        TestSplitOrganizer organizer = new TestSplitOrganizer(mService);
        // This time, start with a fullscreen activitystack
        final ActivityStack primarySplitScreen = mDefaultTaskDisplayArea.createStack(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        primarySplitScreen.reparent((ActivityStack) organizer.mPrimary, POSITION_TOP,
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
        TestSplitOrganizer organizer = new TestSplitOrganizer(mService);
        // Set up split-screen with primary on top and secondary containing the home task below
        // another stack.
        final ActivityStack primaryTask = mDefaultTaskDisplayArea.createStack(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityStack homeRoot = mDefaultTaskDisplayArea.getStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        final ActivityStack secondaryTask = mDefaultTaskDisplayArea.createStack(
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mDefaultTaskDisplayArea.positionStackAtTop((ActivityStack) organizer.mPrimary,
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
        ((ActivityStack) organizer.mSecondary).moveToBack("test", secondaryTask);

        // Assert that it is now in back but still in secondary split
        assertEquals(1, homeRoot.compareTo(primaryTask));
        assertEquals(secondaryTask, organizer.mSecondary.getChildAt(0));
        assertEquals(1, primaryTask.compareTo(secondaryTask));
        assertEquals(homeRoot.getParent(), secondaryTask.getParent());
    }

    @Test
    public void testStackInheritsDisplayWindowingMode() {
        final ActivityStack primarySplitScreen = mDefaultTaskDisplayArea.createStack(
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
        final ActivityStack primarySplitScreen = mDefaultTaskDisplayArea.createStack(
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
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        r.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        mStack.moveToFront("testStopActivityWithDestroy");
        r.stopIfPossible();
        // Mostly testing to make sure there is a crash in the call part, so if we get here we are
        // good-to-go!
    }

    @Test
    public void testFindTaskWithOverlay() {
        final ActivityRecord r = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setStack(mStack)
                .setUid(0)
                .build();
        final Task task = r.getTask();
        // Overlay must be for a different user to prevent recognizing a matching top activity
        final ActivityRecord taskOverlay = new ActivityBuilder(mService).setTask(task)
                .setUid(UserHandle.PER_USER_RANGE * 2).build();
        taskOverlay.setTaskOverlay(true);

        final RootWindowContainer.FindTaskResult result =
                new RootWindowContainer.FindTaskResult();
        result.process(r, mStack);

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
        final Task task = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();
        task.origActivity = alias;
        task.realActivity = target;
        new ActivityBuilder(mService).setComponent(target).setTask(task).setTargetActivity(
                targetActivity).build();

        // Using target activity to find task.
        final ActivityRecord r1 = new ActivityBuilder(mService).setComponent(
                target).setTargetActivity(targetActivity).build();
        RootWindowContainer.FindTaskResult result = new RootWindowContainer.FindTaskResult();
        result.process(r1, mStack);
        assertThat(result.mRecord).isNotNull();

        // Using alias activity to find task.
        final ActivityRecord r2 = new ActivityBuilder(mService).setComponent(
                alias).setTargetActivity(targetActivity).build();
        result = new RootWindowContainer.FindTaskResult();
        result.process(r2, mStack);
        assertThat(result.mRecord).isNotNull();
    }

    @Test
    public void testMoveStackToBackIncludingParent() {
        final TaskDisplayArea taskDisplayArea = addNewDisplayContentAt(DisplayContent.POSITION_TOP)
                .getDefaultTaskDisplayArea();
        final ActivityStack stack1 = createStackForShouldBeVisibleTest(taskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityStack stack2 = createStackForShouldBeVisibleTest(taskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Do not move display to back because there is still another stack.
        stack2.moveToBack("testMoveStackToBackIncludingParent", stack2.getTopMostTask());
        verify(stack2).positionChildAtBottom(any(), eq(false) /* includingParents */);

        // Also move display to back because there is only one stack left.
        taskDisplayArea.removeStack(stack1);
        stack2.moveToBack("testMoveStackToBackIncludingParent", stack2.getTopMostTask());
        verify(stack2).positionChildAtBottom(any(), eq(true) /* includingParents */);
    }

    @Test
    public void testShouldBeVisible_Fullscreen() {
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // Add an activity to the pinned stack so it isn't considered empty for visibility check.
        final ActivityRecord pinnedActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setStack(pinnedStack)
                .build();

        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));

        final ActivityStack fullscreenStack = createStackForShouldBeVisibleTest(
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
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        // Home stack should always be fullscreen for this test.
        doReturn(false).when(homeStack).supportsSplitScreenWindowingMode();
        final ActivityStack splitScreenPrimary =
                createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityStack splitScreenSecondary =
                createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Home stack shouldn't be visible if both halves of split-screen are opaque.
        doReturn(false).when(splitScreenPrimary).isTranslucent(any());
        doReturn(false).when(splitScreenSecondary).isTranslucent(any());
        assertFalse(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE, homeStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));

        // Home stack should be visible if one of the halves of split-screen is translucent.
        doReturn(true).when(splitScreenPrimary).isTranslucent(any());
        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                homeStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));

        final ActivityStack splitScreenSecondary2 =
                createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // First split-screen secondary shouldn't be visible behind another opaque split-split
        // secondary.
        doReturn(false).when(splitScreenSecondary2).isTranslucent(any());
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // First split-screen secondary should be visible behind another translucent split-screen
        // secondary.
        doReturn(true).when(splitScreenSecondary2).isTranslucent(any());
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        final ActivityStack assistantStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT,
                true /* onTop */);

        // Split-screen stacks shouldn't be visible behind an opaque fullscreen stack.
        doReturn(false).when(assistantStack).isTranslucent(any());
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                assistantStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // Split-screen stacks should be visible behind a translucent fullscreen stack.
        doReturn(true).when(assistantStack).isTranslucent(any());
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                assistantStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                splitScreenSecondary2.getVisibility(null /* starting */));

        // Assistant stack shouldn't be visible behind translucent split-screen stack
        doReturn(false).when(assistantStack).isTranslucent(any());
        doReturn(true).when(splitScreenPrimary).isTranslucent(any());
        doReturn(true).when(splitScreenSecondary2).isTranslucent(any());
        splitScreenSecondary2.moveToFront("testShouldBeVisible_SplitScreen");
        splitScreenPrimary.moveToFront("testShouldBeVisible_SplitScreen");
        assertFalse(assistantStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE,
                assistantStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenPrimary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE,
                splitScreenSecondary.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                splitScreenSecondary2.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucent() {
        final ActivityStack bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final ActivityStack translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucentAndOpaque() {
        final ActivityStack bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final ActivityStack translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final ActivityStack opaqueStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);

        assertEquals(STACK_VISIBILITY_INVISIBLE, bottomStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_INVISIBLE,
                translucentStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE, opaqueStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindOpaqueAndTranslucent() {
        final ActivityStack bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final ActivityStack opaqueStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final ActivityStack translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(STACK_VISIBILITY_INVISIBLE, bottomStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                opaqueStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenTranslucentBehindTranslucent() {
        final ActivityStack bottomTranslucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final ActivityStack translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);

        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomTranslucentStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenTranslucentBehindOpaque() {
        final ActivityStack bottomTranslucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final ActivityStack opaqueStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);

        assertEquals(STACK_VISIBILITY_INVISIBLE,
                bottomTranslucentStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE, opaqueStack.getVisibility(null /* starting */));
    }

    @Test
    public void testGetVisibility_FullscreenBehindTranslucentAndPip() {
        final ActivityStack bottomStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        false /* translucent */);
        final ActivityStack translucentStack =
                createStandardStackForVisibilityTest(WINDOWING_MODE_FULLSCREEN,
                        true /* translucent */);
        final ActivityStack pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        assertEquals(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                bottomStack.getVisibility(null /* starting */));
        assertEquals(STACK_VISIBILITY_VISIBLE,
                translucentStack.getVisibility(null /* starting */));
        // Add an activity to the pinned stack so it isn't considered empty for visibility check.
        final ActivityRecord pinnedActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setStack(pinnedStack)
                .build();
        assertEquals(STACK_VISIBILITY_VISIBLE, pinnedStack.getVisibility(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_Finishing() {
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        ActivityRecord topRunningHomeActivity = homeStack.topRunningActivity();
        if (topRunningHomeActivity == null) {
            topRunningHomeActivity = new ActivityBuilder(mService)
                    .setStack(homeStack)
                    .setCreateTask(true)
                    .build();
        }

        final ActivityStack translucentStack = createStackForShouldBeVisibleTest(
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
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        final ActivityRecord firstActivity = new ActivityBuilder(mService)
                    .setStack(homeStack)
                    .setCreateTask(true)
                    .build();
        final Task task = firstActivity.getTask();
        final ActivityRecord secondActivity = new ActivityBuilder(mService)
                .setTask(task)
                .build();

        doReturn(false).when(secondActivity).occludesParent();
        homeStack.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */);

        assertTrue(firstActivity.shouldBeVisible());
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeBehindFullscreen() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack fullscreenStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack).isTranslucent(any());

        // Ensure that we don't move the home stack if it is already behind the top fullscreen stack
        int homeStackIndex = mDefaultTaskDisplayArea.getIndexOf(homeStack);
        assertEquals(fullscreenStack, getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindBottomMostVisibleStack(homeStack);
        assertEquals(homeStackIndex, mDefaultTaskDisplayArea.getIndexOf(homeStack));
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeBehindTranslucent() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack fullscreenStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(true).when(fullscreenStack).isTranslucent(any());

        // Ensure that we don't move the home stack if it is already behind the top fullscreen stack
        int homeStackIndex = mDefaultTaskDisplayArea.getIndexOf(homeStack);
        assertEquals(fullscreenStack, getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindBottomMostVisibleStack(homeStack);
        assertEquals(homeStackIndex, mDefaultTaskDisplayArea.getIndexOf(homeStack));
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeOnTop() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        final ActivityStack fullscreenStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack).isTranslucent(any());

        // Ensure we don't move the home stack if it is already on top
        int homeStackIndex = mDefaultTaskDisplayArea.getIndexOf(homeStack);
        assertNull(getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindBottomMostVisibleStack(homeStack);
        assertEquals(homeStackIndex, mDefaultTaskDisplayArea.getIndexOf(homeStack));
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_MoveHomeBehindFullscreen() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack1).isTranslucent(any());
        doReturn(false).when(fullscreenStack2).isTranslucent(any());

        // Ensure that we move the home stack behind the bottom most fullscreen stack, ignoring the
        // pinned stack
        assertEquals(fullscreenStack1, getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindBottomMostVisibleStack(homeStack);
        assertEquals(fullscreenStack2, getStackAbove(homeStack));
    }

    @Test
    public void
            testMoveHomeStackBehindBottomMostVisibleStack_MoveHomeBehindFullscreenAndTranslucent() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack1).isTranslucent(any());
        doReturn(true).when(fullscreenStack2).isTranslucent(any());

        // Ensure that we move the home stack behind the bottom most non-translucent fullscreen
        // stack
        assertEquals(fullscreenStack1, getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindBottomMostVisibleStack(homeStack);
        assertEquals(fullscreenStack1, getStackAbove(homeStack));
    }

    @Test
    public void testMoveHomeStackBehindStack_BehindHomeStack() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        final ActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        doReturn(false).when(homeStack).isTranslucent(any());
        doReturn(false).when(fullscreenStack1).isTranslucent(any());
        doReturn(false).when(fullscreenStack2).isTranslucent(any());

        // Ensure we don't move the home stack behind itself
        int homeStackIndex = mDefaultTaskDisplayArea.getIndexOf(homeStack);
        mDefaultTaskDisplayArea.moveStackBehindStack(homeStack, homeStack);
        assertEquals(homeStackIndex, mDefaultTaskDisplayArea.getIndexOf(homeStack));
    }

    @Test
    public void testMoveHomeStackBehindStack() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        final ActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack fullscreenStack3 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack fullscreenStack4 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        mDefaultTaskDisplayArea.moveStackBehindStack(homeStack, fullscreenStack1);
        assertEquals(fullscreenStack1, getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindStack(homeStack, fullscreenStack2);
        assertEquals(fullscreenStack2, getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindStack(homeStack, fullscreenStack4);
        assertEquals(fullscreenStack4, getStackAbove(homeStack));
        mDefaultTaskDisplayArea.moveStackBehindStack(homeStack, fullscreenStack2);
        assertEquals(fullscreenStack2, getStackAbove(homeStack));
    }

    @Test
    public void testSetAlwaysOnTop() {
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack pinnedStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(pinnedStack, getStackAbove(homeStack));

        final ActivityStack alwaysOnTopStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        alwaysOnTopStack.setAlwaysOnTop(true);
        assertTrue(alwaysOnTopStack.isAlwaysOnTop());
        // Ensure (non-pinned) always on top stack is put below pinned stack.
        assertEquals(pinnedStack, getStackAbove(alwaysOnTopStack));

        final ActivityStack nonAlwaysOnTopStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        // Ensure non always on top stack is put below always on top stacks.
        assertEquals(alwaysOnTopStack, getStackAbove(nonAlwaysOnTopStack));

        final ActivityStack alwaysOnTopStack2 = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        alwaysOnTopStack2.setAlwaysOnTop(true);
        assertTrue(alwaysOnTopStack2.isAlwaysOnTop());
        // Ensure newly created always on top stack is placed above other all always on top stacks.
        assertEquals(pinnedStack, getStackAbove(alwaysOnTopStack2));

        alwaysOnTopStack2.setAlwaysOnTop(false);
        // Ensure, when always on top is turned off for a stack, the stack is put just below all
        // other always on top stacks.
        assertEquals(alwaysOnTopStack, getStackAbove(alwaysOnTopStack2));
        alwaysOnTopStack2.setAlwaysOnTop(true);

        // Ensure always on top state changes properly when windowing mode changes.
        alwaysOnTopStack2.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertFalse(alwaysOnTopStack2.isAlwaysOnTop());
        assertEquals(alwaysOnTopStack, getStackAbove(alwaysOnTopStack2));
        alwaysOnTopStack2.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTrue(alwaysOnTopStack2.isAlwaysOnTop());
        assertEquals(pinnedStack, getStackAbove(alwaysOnTopStack2));
    }

    @Test
    public void testSplitScreenMoveToFront() {
        final ActivityStack splitScreenPrimary = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityStack splitScreenSecondary = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityStack assistantStack = createStackForShouldBeVisibleTest(
                mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT,
                true /* onTop */);

        doReturn(false).when(splitScreenPrimary).isTranslucent(any());
        doReturn(false).when(splitScreenSecondary).isTranslucent(any());
        doReturn(false).when(assistantStack).isTranslucent(any());

        assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));

        splitScreenSecondary.moveToFront("testSplitScreenMoveToFront");

        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertFalse(assistantStack.shouldBeVisible(null /* starting */));
    }

    private ActivityStack createStandardStackForVisibilityTest(int windowingMode,
            boolean translucent) {
        final ActivityStack stack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                windowingMode, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        doReturn(translucent).when(stack).isTranslucent(any());
        return stack;
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    private ActivityStack createStackForShouldBeVisibleTest(
            TaskDisplayArea taskDisplayArea, int windowingMode, int activityType, boolean onTop) {
        final ActivityStack stack;
        if (activityType == ACTIVITY_TYPE_HOME) {
            // Home stack and activity are created in ActivityTestsBase#setupActivityManagerService
            stack = mDefaultTaskDisplayArea.getStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
            if (onTop) {
                mDefaultTaskDisplayArea.positionStackAtTop(stack,
                        false /* includingParents */);
            } else {
                mDefaultTaskDisplayArea.positionStackAtBottom(stack);
            }
        } else {
            stack = new StackBuilder(mRootWindowContainer)
                    .setTaskDisplayArea(taskDisplayArea)
                    .setWindowingMode(windowingMode)
                    .setActivityType(activityType)
                    .setOnTop(onTop)
                    .setCreateActivity(true)
                    .build();
        }
        return stack;
    }

    @Test
    public void testFinishDisabledPackageActivities_FinishAliveActivities() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setTask(mTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mService).setTask(mTask).build();
        firstActivity.setState(STOPPED, "testFinishDisabledPackageActivities");
        secondActivity.setState(RESUMED, "testFinishDisabledPackageActivities");
        mStack.mResumedActivity = secondActivity;

        // Note the activities have non-null ActivityRecord.app, so it won't remove directly.
        mRootWindowContainer.mFinishDisabledPackageActivitiesHelper.process(
                firstActivity.packageName, null /* filterByClasses */, true /* doit */,
                true /* evenPersistent */, UserHandle.USER_ALL);

        // If the activity is disabled with {@link android.content.pm.PackageManager#DONT_KILL_APP}
        // the activity should still follow the normal flow to finish and destroy.
        assertThat(firstActivity.getState()).isEqualTo(DESTROYING);
        assertThat(secondActivity.getState()).isEqualTo(PAUSING);
        assertTrue(secondActivity.finishing);
    }

    @Test
    public void testFinishDisabledPackageActivities_RemoveNonAliveActivities() {
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(mTask).build();

        // The overlay activity is not in the disabled package but it is in the same task.
        final ActivityRecord overlayActivity = new ActivityBuilder(mService).setTask(mTask)
                .setComponent(new ComponentName("package.overlay", ".OverlayActivity")).build();
        // If the task only remains overlay activity, the task should also be removed.
        // See {@link ActivityStack#removeFromHistory}.
        overlayActivity.setTaskOverlay(true);

        // The activity without an app means it will be removed immediately.
        // See {@link ActivityStack#destroyActivityLocked}.
        activity.app = null;
        overlayActivity.app = null;

        assertEquals(2, mTask.getChildCount());

        mRootWindowContainer.mFinishDisabledPackageActivitiesHelper.process(
                activity.packageName, null  /* filterByClasses */, true /* doit */,
                true /* evenPersistent */, UserHandle.USER_ALL);

        // Although the overlay activity is in another package, the non-overlay activities are
        // removed from the task. Since the overlay activity should be removed as well, the task
        // should be empty.
        assertFalse(mTask.hasChild());
        assertFalse(mStack.hasChild());
    }

    @Test
    public void testHandleAppDied() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setTask(mTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mService).setTask(mTask).build();

        // Making the first activity a task overlay means it will be removed from the task's
        // activities as well once second activity is removed as handleAppDied processes the
        // activity list in reverse.
        firstActivity.setTaskOverlay(true);
        firstActivity.app = null;

        // second activity will be immediately removed as it has no state.
        secondActivity.setSavedState(null /* savedState */);

        assertEquals(2, mTask.getChildCount());

        mRootWindowContainer.handleAppDied(secondActivity.app);

        assertFalse(mTask.hasChild());
        assertFalse(mStack.hasChild());
    }

    @Test
    public void testHandleAppDied_RelaunchesAfterCrashDuringWindowingModeResize() {
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(mTask).build();

        activity.mRelaunchReason = RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
        activity.launchCount = 1;
        activity.setSavedState(null /* savedState */);

        mRootWindowContainer.handleAppDied(activity.app);

        assertEquals(1, mTask.getChildCount());
        assertEquals(1, mStack.getChildCount());
    }

    @Test
    public void testHandleAppDied_NotRelaunchAfterThreeCrashesDuringWindowingModeResize() {
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(mTask).build();

        activity.mRelaunchReason = RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
        activity.launchCount = 3;
        activity.setSavedState(null /* savedState */);

        mRootWindowContainer.handleAppDied(activity.app);

        assertFalse(mTask.hasChild());
        assertFalse(mStack.hasChild());
    }

    @Test
    public void testHandleAppDied_RelaunchesAfterCrashDuringFreeResize() {
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(mTask).build();

        activity.mRelaunchReason = RELAUNCH_REASON_FREE_RESIZE;
        activity.launchCount = 1;
        activity.setSavedState(null /* savedState */);

        mRootWindowContainer.handleAppDied(activity.app);

        assertEquals(1, mTask.getChildCount());
        assertEquals(1, mStack.getChildCount());
    }

    @Test
    public void testHandleAppDied_NotRelaunchAfterThreeCrashesDuringFreeResize() {
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(mTask).build();

        activity.mRelaunchReason = RELAUNCH_REASON_FREE_RESIZE;
        activity.launchCount = 3;
        activity.setSavedState(null /* savedState */);

        mRootWindowContainer.handleAppDied(activity.app);

        assertFalse(mTask.hasChild());
        assertFalse(mStack.hasChild());
    }

    @Test
    public void testCompletePauseOnResumeWhilePausingActivity() {
        final ActivityRecord bottomActivity = new ActivityBuilder(mService).setTask(mTask).build();
        doReturn(true).when(bottomActivity).attachedToProcess();
        mStack.mPausingActivity = null;
        mStack.mResumedActivity = bottomActivity;
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.info.flags |= FLAG_RESUME_WHILE_PAUSING;

        mStack.startPausingLocked(false /* userLeaving */, false /* uiSleeping */, topActivity);
        verify(mStack).completePauseLocked(anyBoolean(), eq(topActivity));
    }

    @Test
    public void testWontFinishHomeStackImmediately() {
        final ActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultTaskDisplayArea,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        ActivityRecord activity = homeStack.topRunningActivity();
        if (activity == null) {
            activity = new ActivityBuilder(mService)
                    .setStack(homeStack)
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
        final ActivityStack stack1 = createStackForShouldBeVisibleTest(
                display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityStack stack2 = createStackForShouldBeVisibleTest(
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

    private ActivityRecord finishTopActivity(ActivityStack stack) {
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
        // of the display state
        verifyShouldSleepActivities(true /* focusedStack */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, false /* expected*/);

        // When not the focused stack, defer to display sleeping state.
        verifyShouldSleepActivities(false /* focusedStack */, true /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* expected*/);

        // If keyguard is going away, defer to the display sleeping state.
        verifyShouldSleepActivities(true /* focusedStack */, false /*keyguardGoingAway*/,
                true /* displaySleeping */, true /* expected*/);
        verifyShouldSleepActivities(true /* focusedStack */, false /*keyguardGoingAway*/,
                false /* displaySleeping */, false /* expected*/);
    }

    @Test
    public void testStackOrderChangedOnRemoveStack() {
        StackOrderChangedListener listener = new StackOrderChangedListener();
        mDefaultTaskDisplayArea.registerStackOrderChangedListener(listener);
        try {
            mDefaultTaskDisplayArea.removeStack(mStack);
        } finally {
            mDefaultTaskDisplayArea.unregisterStackOrderChangedListener(listener);
        }
        assertTrue(listener.mChanged);
    }

    @Test
    public void testStackOrderChangedOnAddPositionStack() {
        mDefaultTaskDisplayArea.removeStack(mStack);

        StackOrderChangedListener listener = new StackOrderChangedListener();
        mDefaultTaskDisplayArea.registerStackOrderChangedListener(listener);
        try {
            mStack.mReparenting = true;
            mDefaultTaskDisplayArea.addChild(mStack, 0);
        } finally {
            mDefaultTaskDisplayArea.unregisterStackOrderChangedListener(listener);
        }
        assertTrue(listener.mChanged);
    }

    @Test
    public void testStackOrderChangedOnPositionStack() {
        StackOrderChangedListener listener = new StackOrderChangedListener();
        try {
            final ActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                    mDefaultTaskDisplayArea, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                    true /* onTop */);
            mDefaultTaskDisplayArea.registerStackOrderChangedListener(listener);
            mDefaultTaskDisplayArea.positionStackAtBottom(fullscreenStack1);
        } finally {
            mDefaultTaskDisplayArea.unregisterStackOrderChangedListener(listener);
        }
        assertTrue(listener.mChanged);
    }

    @Test
    public void testNavigateUpTo() {
        final ActivityStartController controller = mock(ActivityStartController.class);
        final ActivityStarter starter = new ActivityStarter(controller,
                mService, mService.mStackSupervisor, mock(ActivityStartInterceptor.class));
        doReturn(controller).when(mService).getActivityStartController();
        spyOn(starter);
        doReturn(ActivityManager.START_SUCCESS).when(starter).execute();

        final ActivityRecord firstActivity = new ActivityBuilder(mService).setTask(mTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mService).setTask(mTask)
                .setUid(firstActivity.getUid() + 1).build();
        doReturn(starter).when(controller).obtainStarter(eq(firstActivity.intent), anyString());

        final IApplicationThread thread = secondActivity.app.getThread();
        secondActivity.app.setThread(null);
        // This should do nothing from a non-attached caller.
        assertFalse(mStack.navigateUpTo(secondActivity /* source record */,
                firstActivity.intent /* destIntent */, 0 /* resultCode */, null /* resultData */));

        secondActivity.app.setThread(thread);
        assertTrue(mStack.navigateUpTo(secondActivity /* source record */,
                firstActivity.intent /* destIntent */, 0 /* resultCode */, null /* resultData */));
        // The firstActivity uses default launch mode, so the activities between it and itself will
        // be finished.
        assertTrue(secondActivity.finishing);
        assertTrue(firstActivity.finishing);
        // The caller uid of the new activity should be the current real caller.
        assertEquals(starter.mRequest.callingUid, secondActivity.getUid());
    }

    @Test
    public void testResetTaskWithFinishingActivities() {
        final ActivityRecord taskTop =
                new ActivityBuilder(mService).setStack(mStack).setCreateTask(true).build();
        // Make all activities in the task are finishing to simulate Task#getTopActivity
        // returns null.
        taskTop.finishing = true;

        final ActivityRecord newR = new ActivityBuilder(mService).build();
        final ActivityRecord result = mStack.resetTaskIfNeeded(taskTop, newR);
        assertThat(result).isEqualTo(taskTop);
    }

    @Test
    public void testCheckBehindFullscreenActivity() {
        final ActivityRecord bottomActivity =
                new ActivityBuilder(mService).setStack(mStack).setTask(mTask).build();
        final ActivityRecord topActivity =
                new ActivityBuilder(mService).setStack(mStack).setTask(mTask).build();
        doReturn(true).when(mStack).shouldBeVisible(any());
        assertTrue(mStack.checkBehindFullscreenActivity(bottomActivity,
                null /* handleBehindFullscreenActivity */));
        assertFalse(mStack.checkBehindFullscreenActivity(topActivity,
                null /* handleBehindFullscreenActivity */));

        doReturn(false).when(topActivity).occludesParent();
        assertFalse(mStack.checkBehindFullscreenActivity(bottomActivity,
                null /* handleBehindFullscreenActivity */));
        assertFalse(mStack.checkBehindFullscreenActivity(topActivity,
                null /* handleBehindFullscreenActivity */));

        final ActivityRecord finishingActivity =
                new ActivityBuilder(mService).setStack(mStack).setTask(mTask).build();
        finishingActivity.finishing = true;
        doCallRealMethod().when(finishingActivity).occludesParent();
        assertFalse(mStack.checkBehindFullscreenActivity(bottomActivity,
                null /* handleBehindFullscreenActivity */));
        assertFalse(mStack.checkBehindFullscreenActivity(topActivity,
                null /* handleBehindFullscreenActivity */));
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
        for (int i = 0; i < activities.length; i++) {
            final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
            activities[i] = r;
            doReturn(null).when(mService).getProcessController(
                    eq(r.processName), eq(r.info.applicationInfo.uid));
            r.setState(ActivityStack.ActivityState.INITIALIZING, "test");
            // Ensure precondition that the activity is opaque.
            assertTrue(r.occludesParent());
            mSupervisor.startSpecificActivity(r, false /* andResume */,
                    false /* checkConfig */);
        }
        mSupervisor.endDeferResume();

        doReturn(false).when(mService).isBooting();
        doReturn(true).when(mService).isBooted();
        // 2 activities are started while keyguard is locked, so they are waiting to be resolved.
        assertFalse(unknownAppVisibilityController.allResolved());

        // Assume the top activity is going to resume and
        // {@link RootWindowContainer#cancelInitializingActivities} should clear the unknown
        // visibility records that are occluded.
        mStack.resumeTopActivityUncheckedLocked(null /* prev */, null /* options */);
        // Assume the top activity relayouted, just remove it directly.
        unknownAppVisibilityController.appRemovedOrHidden(activities[1]);
        // All unresolved records should be removed.
        assertTrue(unknownAppVisibilityController.allResolved());
    }

    @Test
    public void testNonTopVisibleActivityNotResume() {
        final ActivityRecord nonTopVisibleActivity =
                new ActivityBuilder(mService).setTask(mTask).build();
        new ActivityBuilder(mService).setTask(mTask).build();
        doReturn(false).when(nonTopVisibleActivity).attachedToProcess();
        doReturn(true).when(nonTopVisibleActivity).shouldBeVisible(anyBoolean(), anyBoolean());
        doNothing().when(mSupervisor).startSpecificActivity(any(), anyBoolean(),
                anyBoolean());

        mStack.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */);
        verify(mSupervisor).startSpecificActivity(any(), eq(false) /* andResume */,
                anyBoolean());
    }

    private void verifyShouldSleepActivities(boolean focusedStack,
            boolean keyguardGoingAway, boolean displaySleeping, boolean expected) {
        final DisplayContent display = mock(DisplayContent.class);
        final KeyguardController keyguardController = mSupervisor.getKeyguardController();

        doReturn(display).when(mStack).getDisplay();
        doReturn(keyguardGoingAway).when(keyguardController).isKeyguardGoingAway();
        doReturn(displaySleeping).when(display).isSleeping();
        doReturn(focusedStack).when(mStack).isFocusedStackOnDisplay();

        assertEquals(expected, mStack.shouldSleepActivities());
    }

    private static class StackOrderChangedListener
            implements TaskDisplayArea.OnStackOrderChangedListener {
        public boolean mChanged = false;

        @Override
        public void onStackOrderChanged(ActivityStack stack) {
            mChanged = true;
        }
    }
}
