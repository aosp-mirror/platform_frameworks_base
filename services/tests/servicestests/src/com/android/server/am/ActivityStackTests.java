/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;

import static com.android.server.am.ActivityStack.ActivityState.PAUSING;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStack.REMOVE_TASK_MODE_DESTROYING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.pm.ActivityInfo;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link ActivityStack} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:com.android.server.am.ActivityStackTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStackTests extends ActivityTestsBase {
    private ActivityManagerService mService;
    private ActivityStackSupervisor mSupervisor;
    private ActivityDisplay mDefaultDisplay;
    private ActivityStack mStack;
    private TaskRecord mTask;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mSupervisor = mService.mStackSupervisor;
        mDefaultDisplay = mService.mStackSupervisor.getDefaultDisplay();
        mStack = mDefaultDisplay.createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        mTask = new TaskBuilder(mSupervisor).setStack(mStack).build();
    }

    @Test
    public void testEmptyTaskCleanupOnRemove() throws Exception {
        assertNotNull(mTask.getWindowContainerController());
        mStack.removeTask(mTask, "testEmptyTaskCleanupOnRemove", REMOVE_TASK_MODE_DESTROYING);
        assertNull(mTask.getWindowContainerController());
    }

    @Test
    public void testOccupiedTaskCleanupOnRemove() throws Exception {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        assertNotNull(mTask.getWindowContainerController());
        mStack.removeTask(mTask, "testOccupiedTaskCleanupOnRemove", REMOVE_TASK_MODE_DESTROYING);
        assertNotNull(mTask.getWindowContainerController());
    }

    @Test
    public void testResumedActivity() throws Exception {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        assertEquals(mStack.getResumedActivity(), null);
        r.setState(RESUMED, "testResumedActivity");
        assertEquals(mStack.getResumedActivity(), r);
        r.setState(PAUSING, "testResumedActivity");
        assertEquals(mStack.getResumedActivity(), null);
    }

    @Test
    public void testResumedActivityFromTaskReparenting() {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        // Ensure moving task between two stacks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromTaskReparenting");
        assertEquals(mStack.getResumedActivity(), r);

        final ActivityStack destStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        mTask.reparent(destStack, true /* toTop */, TaskRecord.REPARENT_KEEP_STACK_AT_FRONT,
                false /* animate */, true /* deferResume*/,
                "testResumedActivityFromTaskReparenting");

        assertEquals(mStack.getResumedActivity(), null);
        assertEquals(destStack.getResumedActivity(), r);
    }

    @Test
    public void testResumedActivityFromActivityReparenting() {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        // Ensure moving task between two stacks updates resumed activity
        r.setState(RESUMED, "testResumedActivityFromActivityReparenting");
        assertEquals(mStack.getResumedActivity(), r);

        final ActivityStack destStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TaskRecord destTask = new TaskBuilder(mSupervisor).setStack(destStack).build();

        mTask.removeActivity(r);
        destTask.addActivityToTop(r);

        assertEquals(mStack.getResumedActivity(), null);
        assertEquals(destStack.getResumedActivity(), r);
    }

    @Test
    public void testPrimarySplitScreenToFullscreenWhenMovedToBack() throws Exception {
        // Create primary splitscreen stack. This will create secondary stacks and places the
        // existing fullscreen stack on the bottom.
        final ActivityStack primarySplitScreen = mDefaultDisplay.createStack(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Assert windowing mode.
        assertEquals(primarySplitScreen.getWindowingMode(), WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);

        // Move primary to back.
        primarySplitScreen.moveToBack("testPrimarySplitScreenToFullscreenWhenMovedToBack",
                null /* task */);

        // Assert that stack is at the bottom.
        assertEquals(mDefaultDisplay.getIndexOf(primarySplitScreen), 0);

        // Ensure no longer in splitscreen.
        assertEquals(primarySplitScreen.getWindowingMode(), WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testStopActivityWhenActivityDestroyed() throws Exception {
        final ActivityRecord r = new ActivityBuilder(mService).setTask(mTask).build();
        r.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        mSupervisor.setFocusStackUnchecked("testStopActivityWithDestroy", mStack);
        mStack.stopActivityLocked(r);
        // Mostly testing to make sure there is a crash in the call part, so if we get here we are
        // good-to-go!
    }

    @Test
    public void testFindTaskWithOverlay() throws Exception {
        final ActivityRecord r = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setStack(mStack)
                .setUid(0)
                .build();
        final TaskRecord task = r.getTask();
        // Overlay must be for a different user to prevent recognizing a matching top activity
        final ActivityRecord taskOverlay = new ActivityBuilder(mService).setTask(task)
                .setUid(UserHandle.PER_USER_RANGE * 2).build();
        taskOverlay.mTaskOverlay = true;

        final ActivityStackSupervisor.FindTaskResult result =
                new ActivityStackSupervisor.FindTaskResult();
        mStack.findTaskLocked(r, result);

        assertEquals(task.getTopActivity(false /* includeOverlays */), r);
        assertEquals(task.getTopActivity(true /* includeOverlays */), taskOverlay);
        assertNotNull(result.r);
    }

    @Test
    public void testShouldBeVisible_Fullscreen() throws Exception {
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack pinnedStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));

        final TestActivityStack fullscreenStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // Home stack shouldn't be visible behind an opaque fullscreen stack, but pinned stack
        // should be visible since it is always on-top.
        fullscreenStack.setIsTranslucent(false);
        assertFalse(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));
        assertTrue(fullscreenStack.shouldBeVisible(null /* starting */));

        // Home stack should be visible behind a translucent fullscreen stack.
        fullscreenStack.setIsTranslucent(true);
        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_SplitScreen() throws Exception {
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        // Home stack should always be fullscreen for this test.
        homeStack.setSupportsSplitScreen(false);
        final TestActivityStack splitScreenPrimary = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TestActivityStack splitScreenSecondary = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Home stack shouldn't be visible if both halves of split-screen are opaque.
        splitScreenPrimary.setIsTranslucent(false);
        splitScreenSecondary.setIsTranslucent(false);
        assertFalse(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));

        // Home stack should be visible if one of the halves of split-screen is translucent.
        splitScreenPrimary.setIsTranslucent(true);
        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));

        final TestActivityStack splitScreenSecondary2 = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // First split-screen secondary shouldn't be visible behind another opaque split-split
        // secondary.
        splitScreenSecondary2.setIsTranslucent(false);
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));

        // First split-screen secondary should be visible behind another translucent split-screen
        // secondary.
        splitScreenSecondary2.setIsTranslucent(true);
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));

        final TestActivityStack assistantStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT, true /* onTop */);

        // Split-screen stacks shouldn't be visible behind an opaque fullscreen stack.
        assistantStack.setIsTranslucent(false);
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary2.shouldBeVisible(null /* starting */));

        // Split-screen stacks should be visible behind a translucent fullscreen stack.
        assistantStack.setIsTranslucent(true);
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));

        // Assistant stack shouldn't be visible behind translucent split-screen stack
        assistantStack.setIsTranslucent(false);
        splitScreenPrimary.setIsTranslucent(true);
        splitScreenSecondary2.setIsTranslucent(true);
        splitScreenSecondary2.moveToFront("testShouldBeVisible_SplitScreen");
        splitScreenPrimary.moveToFront("testShouldBeVisible_SplitScreen");
        assertFalse(assistantStack.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));
    }

    @Test
    public void testShouldBeVisible_Finishing() throws Exception {
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final TestActivityStack translucentStack = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        translucentStack.setIsTranslucent(true);

        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(translucentStack.shouldBeVisible(null /* starting */));

        final ActivityRecord topRunningHomeActivity = homeStack.topRunningActivityLocked();
        topRunningHomeActivity.finishing = true;
        final ActivityRecord topRunningTranslucentActivity =
                translucentStack.topRunningActivityLocked();
        topRunningTranslucentActivity.finishing = true;

        // Home shouldn't be visible since its activity is marked as finishing and it isn't the top
        // of the stack list.
        assertFalse(homeStack.shouldBeVisible(null /* starting */));
        // Home should be visible if we are starting an activity within it.
        assertTrue(homeStack.shouldBeVisible(topRunningHomeActivity /* starting */));
        // The translucent stack should be visible since it is the top of the stack list even though
        // it has its activity marked as finishing.
        assertTrue(translucentStack.shouldBeVisible(null /* starting */));
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeBehindFullscreen() {
        mDefaultDisplay.removeChild(mStack);

        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final TestActivityStack fullscreenStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        homeStack.setIsTranslucent(false);
        fullscreenStack.setIsTranslucent(false);

        // Ensure that we don't move the home stack if it is already behind the top fullscreen stack
        int homeStackIndex = mDefaultDisplay.getIndexOf(homeStack);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack);
        mDefaultDisplay.moveStackBehindBottomMostVisibleStack(homeStack);
        assertTrue(mDefaultDisplay.getIndexOf(homeStack) == homeStackIndex);
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeBehindTranslucent() {
        mDefaultDisplay.removeChild(mStack);

        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final TestActivityStack fullscreenStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        homeStack.setIsTranslucent(false);
        fullscreenStack.setIsTranslucent(true);

        // Ensure that we don't move the home stack if it is already behind the top fullscreen stack
        int homeStackIndex = mDefaultDisplay.getIndexOf(homeStack);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack);
        mDefaultDisplay.moveStackBehindBottomMostVisibleStack(homeStack);
        assertTrue(mDefaultDisplay.getIndexOf(homeStack) == homeStackIndex);
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_NoMoveHomeOnTop() {
        mDefaultDisplay.removeChild(mStack);

        final TestActivityStack fullscreenStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        homeStack.setIsTranslucent(false);
        fullscreenStack.setIsTranslucent(false);

        // Ensure we don't move the home stack if it is already on top
        int homeStackIndex = mDefaultDisplay.getIndexOf(homeStack);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == null);
        mDefaultDisplay.moveStackBehindBottomMostVisibleStack(homeStack);
        assertTrue(mDefaultDisplay.getIndexOf(homeStack) == homeStackIndex);
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_MoveHomeBehindFullscreen() {
        mDefaultDisplay.removeChild(mStack);

        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final TestActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack pinnedStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        homeStack.setIsTranslucent(false);
        fullscreenStack1.setIsTranslucent(false);
        fullscreenStack2.setIsTranslucent(false);

        // Ensure that we move the home stack behind the bottom most fullscreen stack, ignoring the
        // pinned stack
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack1);
        mDefaultDisplay.moveStackBehindBottomMostVisibleStack(homeStack);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack2);
    }

    @Test
    public void testMoveHomeStackBehindBottomMostVisibleStack_MoveHomeBehindFullscreenAndTranslucent() {
        mDefaultDisplay.removeChild(mStack);

        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final TestActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        homeStack.setIsTranslucent(false);
        fullscreenStack1.setIsTranslucent(false);
        fullscreenStack2.setIsTranslucent(true);

        // Ensure that we move the home stack behind the bottom most non-translucent fullscreen
        // stack
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack1);
        mDefaultDisplay.moveStackBehindBottomMostVisibleStack(homeStack);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack1);
    }

    @Test
    public void testMoveHomeStackBehindStack_BehindHomeStack() {
        mDefaultDisplay.removeChild(mStack);

        final TestActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        homeStack.setIsTranslucent(false);
        fullscreenStack1.setIsTranslucent(false);
        fullscreenStack2.setIsTranslucent(false);

        // Ensure we don't move the home stack behind itself
        int homeStackIndex = mDefaultDisplay.getIndexOf(homeStack);
        mDefaultDisplay.moveStackBehindStack(homeStack, homeStack);
        assertTrue(mDefaultDisplay.getIndexOf(homeStack) == homeStackIndex);
    }

    @Test
    public void testMoveHomeStackBehindStack() {
        mDefaultDisplay.removeChild(mStack);

        final TestActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack fullscreenStack2 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack fullscreenStack3 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack fullscreenStack4 = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);

        mDefaultDisplay.moveStackBehindStack(homeStack, fullscreenStack1);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack1);
        mDefaultDisplay.moveStackBehindStack(homeStack, fullscreenStack2);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack2);
        mDefaultDisplay.moveStackBehindStack(homeStack, fullscreenStack4);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack4);
        mDefaultDisplay.moveStackBehindStack(homeStack, fullscreenStack2);
        assertTrue(mDefaultDisplay.getStackAbove(homeStack) == fullscreenStack2);
    }

    @Test
    public void testSplitScreenMoveToFront() throws Exception {
        final TestActivityStack splitScreenPrimary = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack splitScreenSecondary = createStackForShouldBeVisibleTest(
                mDefaultDisplay, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        final TestActivityStack assistantStack = createStackForShouldBeVisibleTest(mDefaultDisplay,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT, true /* onTop */);

        splitScreenPrimary.setIsTranslucent(false);
        splitScreenSecondary.setIsTranslucent(false);
        assistantStack.setIsTranslucent(false);

        assertFalse(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(assistantStack.shouldBeVisible(null /* starting */));

        splitScreenSecondary.moveToFront("testSplitScreenMoveToFront");

        assertTrue(splitScreenPrimary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertFalse(assistantStack.shouldBeVisible(null /* starting */));
    }

    private <T extends ActivityStack> T createStackForShouldBeVisibleTest(
            ActivityDisplay display, int windowingMode, int activityType, boolean onTop) {
        final T stack = display.createStack(windowingMode, activityType, onTop);
        final ActivityRecord r = new ActivityBuilder(mService).setUid(0).setStack(stack)
                .setCreateTask(true).build();
        return stack;
    }

    @Test
    public void testFinishDisabledPackageActivities() throws Exception {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setTask(mTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mService).setTask(mTask).build();

        // Making the second activity a task overlay without an app means it will be removed from
        // the task's activities as well once first activity is removed.
        secondActivity.mTaskOverlay = true;
        secondActivity.app = null;

        assertEquals(mTask.mActivities.size(), 2);

        mStack.finishDisabledPackageActivitiesLocked(firstActivity.packageName, null,
                true /* doit */, true /* evenPersistent */, UserHandle.USER_ALL);

        assertTrue(mTask.mActivities.isEmpty());
        assertTrue(mStack.getAllTasks().isEmpty());
    }

    @Test
    public void testHandleAppDied() throws Exception {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setTask(mTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mService).setTask(mTask).build();

        // Making the first activity a task overlay means it will be removed from the task's
        // activities as well once second activity is removed as handleAppDied processes the
        // activity list in reverse.
        firstActivity.mTaskOverlay = true;
        firstActivity.app = null;

        // second activity will be immediately removed as it has no state.
        secondActivity.haveState = false;

        assertEquals(mTask.mActivities.size(), 2);

        mStack.handleAppDiedLocked(secondActivity.app);

        assertTrue(mTask.mActivities.isEmpty());
        assertTrue(mStack.getAllTasks().isEmpty());
    }

    @Test
    public void testShouldSleepActivities() throws Exception {
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
    public void testStackOrderChangedOnRemoveStack() throws Exception {
        StackOrderChangedListener listener = new StackOrderChangedListener();
        mDefaultDisplay.registerStackOrderChangedListener(listener);
        try {
            mDefaultDisplay.removeChild(mStack);
        } finally {
            mDefaultDisplay.unregisterStackOrderChangedListener(listener);
        }
        assertTrue(listener.changed);
    }

    @Test
    public void testStackOrderChangedOnAddPositionStack() throws Exception {
        mDefaultDisplay.removeChild(mStack);

        StackOrderChangedListener listener = new StackOrderChangedListener();
        mDefaultDisplay.registerStackOrderChangedListener(listener);
        try {
            mDefaultDisplay.addChild(mStack, 0);
        } finally {
            mDefaultDisplay.unregisterStackOrderChangedListener(listener);
        }
        assertTrue(listener.changed);
    }

    @Test
    public void testStackOrderChangedOnPositionStack() throws Exception {
        StackOrderChangedListener listener = new StackOrderChangedListener();
        try {
            final TestActivityStack fullscreenStack1 = createStackForShouldBeVisibleTest(
                    mDefaultDisplay, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                    true /* onTop */);
            mDefaultDisplay.registerStackOrderChangedListener(listener);
            mDefaultDisplay.positionChildAtBottom(fullscreenStack1);
        } finally {
            mDefaultDisplay.unregisterStackOrderChangedListener(listener);
        }
        assertTrue(listener.changed);
    }

    private void verifyShouldSleepActivities(boolean focusedStack,
            boolean keyguardGoingAway, boolean displaySleeping, boolean expected) {
        mSupervisor.mFocusedStack = focusedStack ? mStack : null;

        final ActivityDisplay display = mock(ActivityDisplay.class);
        final KeyguardController keyguardController = mSupervisor.getKeyguardController();

        doReturn(display).when(mSupervisor).getActivityDisplay(anyInt());
        doReturn(keyguardGoingAway).when(keyguardController).isKeyguardGoingAway();
        doReturn(displaySleeping).when(display).isSleeping();

        assertEquals(expected, mStack.shouldSleepActivities());
    }

    private class StackOrderChangedListener implements ActivityDisplay.OnStackOrderChangedListener {
        boolean changed = false;

        @Override
        public void onStackOrderChanged() {
            changed = true;
        }
    }
}
