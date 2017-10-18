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
import static com.android.server.am.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
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
 *  bit FrameworksServicesTests:com.android.server.am.ActivityStackTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStackTests extends ActivityTestsBase {
    private static final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");
    private static final ComponentName testOverlayComponent =
            ComponentName.unflattenFromString("com.foo/.OverlayActivity");

    private ActivityManagerService mService;
    private ActivityStackSupervisor mSupervisor;
    private ActivityStack mStack;
    private TaskRecord mTask;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mSupervisor = mService.mStackSupervisor;
        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTask = createTask(mSupervisor, testActivityComponent, mStack);
    }

    @Test
    public void testEmptyTaskCleanupOnRemove() throws Exception {
        assertNotNull(mTask.getWindowContainerController());
        mStack.removeTask(mTask, "testEmptyTaskCleanupOnRemove", REMOVE_TASK_MODE_DESTROYING);
        assertNull(mTask.getWindowContainerController());
    }

    @Test
    public void testOccupiedTaskCleanupOnRemove() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask);
        assertNotNull(mTask.getWindowContainerController());
        mStack.removeTask(mTask, "testOccupiedTaskCleanupOnRemove", REMOVE_TASK_MODE_DESTROYING);
        assertNotNull(mTask.getWindowContainerController());
    }

    @Test
    public void testNoPauseDuringResumeTopActivity() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask);

        // Simulate the a resumed activity set during
        // {@link ActivityStack#resumeTopActivityUncheckedLocked}.
        mSupervisor.inResumeTopActivity = true;
        mStack.mResumedActivity = r;

        final boolean waiting = mStack.goToSleepIfPossible(false);

        // Ensure we report not being ready for sleep.
        assertFalse(waiting);

        // Make sure the resumed activity is untouched.
        assertEquals(mStack.mResumedActivity, r);
    }

    @Test
    public void testStopActivityWhenActivityDestroyed() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask);
        r.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        mSupervisor.setFocusStackUnchecked("testStopActivityWithDestroy", mStack);
        mStack.stopActivityLocked(r);
        // Mostly testing to make sure there is a crash in the call part, so if we get here we are
        // good-to-go!
    }

    @Test
    public void testFindTaskWithOverlay() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask, 0);
        // Overlay must be for a different user to prevent recognizing a matching top activity
        final ActivityRecord taskOverlay = createActivity(mService, testOverlayComponent, mTask,
                UserHandle.PER_USER_RANGE * 2);
        taskOverlay.mTaskOverlay = true;

        final ActivityStackSupervisor.FindTaskResult result =
                new ActivityStackSupervisor.FindTaskResult();
        mStack.findTaskLocked(r, result);

        assertEquals(mTask.getTopActivity(false /* includeOverlays */), r);
        assertEquals(mTask.getTopActivity(true /* includeOverlays */), taskOverlay);
        assertNotNull(result.r);
    }

    @Test
    public void testShouldBeVisible_Fullscreen() throws Exception {
        final ActivityDisplay display = mService.mStackSupervisor.getDefaultDisplay();
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(display,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final ActivityStack pinnedStack = createStackForShouldBeVisibleTest(display,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        assertTrue(homeStack.shouldBeVisible(null /* starting */));
        assertTrue(pinnedStack.shouldBeVisible(null /* starting */));

        final TestActivityStack fullscreenStack = createStackForShouldBeVisibleTest(display,
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
        final ActivityDisplay display = mService.mStackSupervisor.getDefaultDisplay();
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(display,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final TestActivityStack splitScreenPrimary = createStackForShouldBeVisibleTest(display,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TestActivityStack splitScreenSecondary = createStackForShouldBeVisibleTest(display,
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

        final TestActivityStack splitScreenSecondary2 = createStackForShouldBeVisibleTest(display,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        // First split-screen secondary shouldn't be visible behind another opaque split-split
        // secondary.
        splitScreenSecondary2.setIsTranslucent(false);
        assertFalse(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));

        // First split-screen secondary should be visible behind another translucent split-split
        // secondary.
        splitScreenSecondary2.setIsTranslucent(true);
        assertTrue(splitScreenSecondary.shouldBeVisible(null /* starting */));
        assertTrue(splitScreenSecondary2.shouldBeVisible(null /* starting */));

        final TestActivityStack assistantStack = createStackForShouldBeVisibleTest(display,
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
    }

    @Test
    public void testShouldBeVisible_Finishing() throws Exception {
        final ActivityDisplay display = mService.mStackSupervisor.getDefaultDisplay();
        final TestActivityStack homeStack = createStackForShouldBeVisibleTest(display,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        final TestActivityStack translucentStack = createStackForShouldBeVisibleTest(display,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
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

    private <T extends ActivityStack> T createStackForShouldBeVisibleTest(
            ActivityDisplay display, int windowingMode, int activityType, boolean onTop) {
        final T stack = display.createStack(windowingMode, activityType, onTop);
        // Create a task and activity in the stack so that it has a top running activity.
        final TaskRecord task = createTask(mSupervisor, testActivityComponent, stack);
        final ActivityRecord r = createActivity(mService, testActivityComponent, task, 0);
        return stack;
    }
}
