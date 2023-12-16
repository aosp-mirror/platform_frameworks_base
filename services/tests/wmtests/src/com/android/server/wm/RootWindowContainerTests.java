/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.ActivityRecord.State.FINISHING;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;

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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.PowerManager;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.MergedConfiguration;
import android.util.Pair;

import androidx.test.filters.MediumTest;

import com.android.internal.app.ResolverActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tests for {@link RootWindowContainer}.
 *
 * Build/Install/Run:
 *  atest WmTests:RootWindowContainerTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RootWindowContainerTests extends WindowTestsBase {

    @Before
    public void setUp() throws Exception {
        doNothing().when(mAtm).updateSleepIfNeededLocked();
    }

    @Test
    public void testUpdateDefaultTaskDisplayAreaWindowingModeOnSettingsRetrieved() {
        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mWm.getDefaultDisplayContentLocked().getDefaultTaskDisplayArea()
                        .getWindowingMode());

        mWm.mIsPc = true;
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;

        mWm.mRoot.onSettingsRetrieved();

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mWm.getDefaultDisplayContentLocked().getDefaultTaskDisplayArea()
                        .getWindowingMode());
    }

    /**
     * This test ensures that an existing single instance activity with alias name can be found by
     * the same activity info. So {@link ActivityStarter#getReusableTask} won't miss it that leads
     * to create an unexpected new instance.
     */
    @Test
    public void testFindActivityByTargetComponent() {
        final ComponentName aliasComponent = ComponentName.createRelative(
                DEFAULT_COMPONENT_PACKAGE_NAME, ".AliasActivity");
        final ComponentName targetComponent = ComponentName.createRelative(
                aliasComponent.getPackageName(), ".TargetActivity");
        final ActivityRecord activity = new ActivityBuilder(mWm.mAtmService)
                .setComponent(aliasComponent)
                .setTargetActivity(targetComponent.getClassName())
                .setLaunchMode(ActivityInfo.LAUNCH_SINGLE_INSTANCE)
                .setCreateTask(true)
                .build();

        assertEquals(activity, mWm.mRoot.findActivity(activity.intent, activity.info,
                false /* compareIntentFilters */));
    }

    @Test
    public void testAllPausedActivitiesComplete() {
        DisplayContent displayContent = mWm.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        ActivityRecord activity = createActivityRecord(displayContent);
        Task task = activity.getTask();
        task.setPausingActivity(activity);

        activity.setState(PAUSING, "test PAUSING");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isFalse();

        activity.setState(PAUSED, "test PAUSED");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();

        activity.setState(STOPPED, "test STOPPED");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();

        activity.setState(STOPPING, "test STOPPING");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();

        activity.setState(FINISHING, "test FINISHING");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();
    }

    @Test
    public void testTaskLayerRank() {
        final Task rootTask = new TaskBuilder(mSupervisor).build();
        final Task task1 = new TaskBuilder(mSupervisor).setParentTask(rootTask).build();
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task1).build();
        activity1.setVisibleRequested(true);
        mWm.mRoot.rankTaskLayers();

        assertEquals(1, task1.mLayerRank);
        // Only tasks that directly contain activities have a ranking.
        assertEquals(Task.LAYER_RANK_INVISIBLE, rootTask.mLayerRank);

        final Task task2 = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setTask(task2).build();
        activity2.setVisibleRequested(true);
        mWm.mRoot.rankTaskLayers();

        // Note that ensureActivitiesVisible is disabled in SystemServicesTestRule, so both the
        // activities have the visible rank.
        assertEquals(2, task1.mLayerRank);
        // The task2 is the top task, so it has a lower rank as a higher priority oom score.
        assertEquals(1, task2.mLayerRank);

        task2.moveToBack("test", null /* task */);
        // RootWindowContainer#invalidateTaskLayers should post to update.
        waitHandlerIdle(mWm.mH);

        assertEquals(1, task1.mLayerRank);
        assertEquals(2, task2.mLayerRank);

        // The rank should be updated to invisible when device went to sleep.
        activity1.setVisibleRequested(false);
        activity2.setVisibleRequested(false);
        doReturn(true).when(mAtm).isSleepingOrShuttingDownLocked();
        doReturn(true).when(mRootWindowContainer).putTasksToSleep(anyBoolean(), anyBoolean());
        mSupervisor.mGoingToSleepWakeLock = mock(PowerManager.WakeLock.class);
        doReturn(false).when(mSupervisor.mGoingToSleepWakeLock).isHeld();
        mAtm.mTaskSupervisor.checkReadyForSleepLocked(false /* allowDelay */);
        assertEquals(Task.LAYER_RANK_INVISIBLE, task1.mLayerRank);
        assertEquals(Task.LAYER_RANK_INVISIBLE, task2.mLayerRank);
    }

    @Test
    public void testForceStopPackage() {
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopMostActivity();
        final WindowProcessController wpc = activity.app;
        final ActivityRecord[] activities = {
                activity,
                new ActivityBuilder(mWm.mAtmService).setTask(task).setUseProcess(wpc).build(),
                new ActivityBuilder(mWm.mAtmService).setTask(task).setUseProcess(wpc).build()
        };
        activities[0].detachFromProcess();
        activities[1].finishing = true;
        activities[1].destroyImmediately("test");
        spyOn(wpc);
        doReturn(true).when(wpc).isRemoved();

        mWm.mAtmService.mInternal.onForceStopPackage(wpc.mInfo.packageName, true /* doit */,
                false /* evenPersistent */, wpc.mUserId);
        // The activity without process should be removed.
        assertEquals(2, task.getChildCount());

        wpc.handleAppDied();
        // The activities with process should be removed because WindowProcessController#isRemoved.
        assertFalse(task.hasChild());
        assertFalse(wpc.hasActivities());
    }

    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. We
     * should expect {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() {
        mRootWindowContainer.getDefaultDisplay().removeAllTasks();
        Task task = mRootWindowContainer.anyTaskForId(0 /*taskId*/,
                MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE, null, false /* onTop */);
        assertNull(task);
    }

    /**
     * This test ensures that an existing task in the pinned root task is moved to the fullscreen
     * activity root task when a new task is added.
     */
    @Test
    public void testReplacingTaskInPinnedRootTask() {
        Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();

        fullscreenTask.moveToFront("testReplacingTaskInPinnedRootTask");

        // Ensure full screen root task has both tasks.
        ensureTaskPlacement(fullscreenTask, firstActivity, secondActivity);

        // Move first activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(firstActivity,
                null /* launchIntoPipHostActivity */, "initialMove");

        final TaskDisplayArea taskDisplayArea = fullscreenTask.getDisplayArea();
        Task pinnedRootTask = taskDisplayArea.getRootPinnedTask();
        // Ensure a task has moved over.
        ensureTaskPlacement(pinnedRootTask, firstActivity);
        ensureTaskPlacement(fullscreenTask, secondActivity);

        // Move second activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(secondActivity,
                null /* launchIntoPipHostActivity */, "secondMove");

        // Need to get root tasks again as a new instance might have been created.
        pinnedRootTask = taskDisplayArea.getRootPinnedTask();
        fullscreenTask = taskDisplayArea.getRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD);
        // Ensure root tasks have swapped tasks.
        ensureTaskPlacement(pinnedRootTask, secondActivity);
        ensureTaskPlacement(fullscreenTask, firstActivity);
    }

    @Test
    public void testMovingBottomMostRootTaskActivityToPinnedRootTask() {
        final Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();
        final Task task = firstActivity.getTask();

        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();

        fullscreenTask.moveTaskToBack(task);

        // Ensure full screen task has both tasks.
        ensureTaskPlacement(fullscreenTask, firstActivity, secondActivity);
        assertEquals(task.getTopMostActivity(), secondActivity);
        firstActivity.setState(STOPPED, "testMovingBottomMostRootTaskActivityToPinnedRootTask");


        // Move first activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(secondActivity,
                null /* launchIntoPipHostActivity */, "initialMove");

        assertTrue(firstActivity.mRequestForceTransition);
    }

    @Test
    public void testMultipleActivitiesTaskEnterPip() {
        // Enable shell transition because the order of setting windowing mode is different.
        registerTestTransitionPlayer();
        final ActivityRecord transientActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm)
                .setTask(activity1.getTask()).build();
        activity2.setState(RESUMED, "test");

        // Assume the top activity switches to a transient-launch, e.g. recents.
        transientActivity.setState(RESUMED, "test");
        transientActivity.getTask().moveToFront("test");

        mRootWindowContainer.moveActivityToPinnedRootTask(activity2,
                null /* launchIntoPipHostActivity */, "test");
        assertEquals("Created PiP task must not change focus", transientActivity.getTask(),
                mRootWindowContainer.getTopDisplayFocusedRootTask());
        final Task newPipTask = activity2.getTask();
        assertEquals(newPipTask, mDisplayContent.getDefaultTaskDisplayArea().getRootPinnedTask());
        assertNotEquals(newPipTask, activity1.getTask());
        assertFalse("Created PiP task must not be in recents", newPipTask.inRecents);
    }

    /**
     * When there is only one activity in the Task, and the activity is requesting to enter PIP, the
     * whole Task should enter PIP.
     */
    @Test
    public void testSingleActivityTaskEnterPip() {
        final Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask)
                .build();
        final Task task = activity.getTask();

        // Move activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(activity,
                null /* launchIntoPipHostActivity */, "test");

        // Ensure a task has moved over.
        ensureTaskPlacement(task, activity);
        assertTrue(task.inPinnedWindowingMode());
        assertFalse("Entering PiP activity must not affect SysUiFlags",
                activity.canAffectSystemUiFlags());

        // The activity with fixed orientation should not apply letterbox when entering PiP.
        final int requestedOrientation = task.getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        doReturn(requestedOrientation).when(activity).getRequestedConfigurationOrientation();
        doReturn(false).when(activity).handlesOrientationChangeFromDescendant(anyInt());
        final Rect bounds = new Rect(task.getBounds());
        bounds.scale(0.5f);
        task.setBounds(bounds);
        assertFalse(activity.isLetterboxedForFixedOrientationAndAspectRatio());
    }

    /**
     * When there is only one activity in the Task, and the activity is requesting to enter PIP, the
     * whole Task should enter PIP even if the activity is in a TaskFragment.
     */
    @Test
    public void testSingleActivityInTaskFragmentEnterPip() {
        final Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(fullscreenTask)
                .createActivityCount(1)
                .build();
        final ActivityRecord activity = taskFragment.getTopMostActivity();
        final Task task = activity.getTask();

        // Move activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(activity,
                null /* launchIntoPipHostActivity */, "test");

        // Ensure a task has moved over.
        ensureTaskPlacement(task, activity);
        assertTrue(task.inPinnedWindowingMode());
    }

    /**
     * When there is one TaskFragment with two activities in the Task, the activity requests to
     * enter PIP, that activity will be move to PIP root task.
     */
    @Test
    public void testMultipleActivitiesInTaskFragmentEnterPip() {
        final Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(fullscreenTask)
                .createActivityCount(2)
                .build();
        final ActivityRecord firstActivity = taskFragment.getTopMostActivity();
        final ActivityRecord secondActivity = taskFragment.getBottomMostActivity();

        // Move first activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(firstActivity,
                null /* launchIntoPipHostActivity */, "test");

        final TaskDisplayArea taskDisplayArea = fullscreenTask.getDisplayArea();
        final Task pinnedRootTask = taskDisplayArea.getRootPinnedTask();

        // Ensure a task has moved over.
        ensureTaskPlacement(pinnedRootTask, firstActivity);
        ensureTaskPlacement(fullscreenTask, secondActivity);
        assertTrue(pinnedRootTask.inPinnedWindowingMode());
        assertEquals(WINDOWING_MODE_FULLSCREEN, fullscreenTask.getWindowingMode());
    }

    @Test
    public void testMovingEmbeddedActivityToPip() {
        final Rect taskBounds = new Rect(0, 0, 800, 1000);
        final Rect taskFragmentBounds = new Rect(0, 0, 400, 1000);
        final Task task = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        task.setBounds(taskBounds);
        assertEquals(taskBounds, task.getBounds());
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(2)
                .setBounds(taskFragmentBounds)
                .build();
        assertEquals(taskFragmentBounds, taskFragment.getBounds());
        final ActivityRecord topActivity = taskFragment.getTopMostActivity();

        // Move the top activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(topActivity,
                null /* launchIntoPipHostActivity */, "test");

        final Task pinnedRootTask = task.getDisplayArea().getRootPinnedTask();

        // Ensure the initial bounds of the PiP Task is the same as the TaskFragment.
        ensureTaskPlacement(pinnedRootTask, topActivity);
        assertEquals(taskFragmentBounds, pinnedRootTask.getBounds());
    }

    private static void ensureTaskPlacement(Task task, ActivityRecord... activities) {
        final ArrayList<ActivityRecord> taskActivities = new ArrayList<>();

        task.forAllActivities((Consumer<ActivityRecord>) taskActivities::add, false);

        assertEquals("Expecting " + Arrays.deepToString(activities) + " got " + taskActivities,
                taskActivities.size(), activities != null ? activities.length : 0);

        if (activities == null) {
            return;
        }

        for (ActivityRecord activity : activities) {
            assertTrue(taskActivities.contains(activity));
        }
    }

    @Test
    public void testApplySleepTokens() {
        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(display)
                .setOnTop(false)
                .build();

        // Make sure we wake and resume in the case the display is turning on and the keyguard is
        // not showing.
        verifySleepTokenBehavior(display, keyguard, task, true /*displaySleeping*/,
                false /* displayShouldSleep */, true /* isFocusedTask */,
                false /* keyguardShowing */, true /* expectWakeFromSleep */,
                true /* expectResumeTopActivity */);

        // Make sure we wake and don't resume when the display is turning on and the keyguard is
        // showing.
        verifySleepTokenBehavior(display, keyguard, task, true /*displaySleeping*/,
                false /* displayShouldSleep */, true /* isFocusedTask */,
                true /* keyguardShowing */, true /* expectWakeFromSleep */,
                false /* expectResumeTopActivity */);

        // Make sure we wake and don't resume when the display is turning on and the keyguard is
        // not showing as unfocused.
        verifySleepTokenBehavior(display, keyguard, task, true /*displaySleeping*/,
                false /* displayShouldSleep */, false /* isFocusedTask */,
                false /* keyguardShowing */, true /* expectWakeFromSleep */,
                false /* expectResumeTopActivity */);

        // Should not do anything if the display state hasn't changed.
        verifySleepTokenBehavior(display, keyguard, task, false /*displaySleeping*/,
                false /* displayShouldSleep */, true /* isFocusedTask */,
                false /* keyguardShowing */, false /* expectWakeFromSleep */,
                false /* expectResumeTopActivity */);
    }

    private void verifySleepTokenBehavior(DisplayContent display, KeyguardController keyguard,
            Task task, boolean displaySleeping, boolean displayShouldSleep,
            boolean isFocusedTask, boolean keyguardShowing, boolean expectWakeFromSleep,
            boolean expectResumeTopActivity) {
        reset(task);

        doReturn(displayShouldSleep).when(display).shouldSleep();
        doReturn(displaySleeping).when(display).isSleeping();
        doReturn(keyguardShowing).when(keyguard).isKeyguardOrAodShowing(anyInt());

        doReturn(isFocusedTask).when(task).isFocusedRootTaskOnDisplay();
        doReturn(isFocusedTask ? task : null).when(display).getFocusedRootTask();
        TaskDisplayArea defaultTaskDisplayArea = display.getDefaultTaskDisplayArea();
        doReturn(isFocusedTask ? task : null).when(defaultTaskDisplayArea).getFocusedRootTask();
        mRootWindowContainer.applySleepTokens(true);
        verify(task, times(expectWakeFromSleep ? 1 : 0)).awakeFromSleeping();
        verify(task, times(expectResumeTopActivity ? 1 : 0)).resumeTopActivityUncheckedLocked(
                null /* target */, null /* targetOptions */);
    }

    @Test
    public void testAwakeFromSleepingWithAppConfiguration() {
        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.moveFocusableActivityToTop("test");
        assertTrue(activity.getRootTask().isFocusedRootTaskOnDisplay());
        ActivityRecordTests.setRotatedScreenOrientationSilently(activity);

        final Configuration rotatedConfig = new Configuration();
        display.computeScreenConfiguration(rotatedConfig, display.getDisplayRotation()
                .rotationForOrientation(activity.getOrientation(), display.getRotation()));
        assertNotEquals(activity.getConfiguration().orientation, rotatedConfig.orientation);
        // Assume the activity was shown in different orientation. For example, the top activity is
        // landscape and the portrait lockscreen is shown.
        activity.setLastReportedConfiguration(
                new MergedConfiguration(mAtm.getGlobalConfiguration(), rotatedConfig));
        activity.setState(STOPPED, "sleep");

        display.setIsSleeping(true);
        doReturn(false).when(display).shouldSleep();
        // Allow to resume when awaking.
        setBooted(mAtm);
        mRootWindowContainer.applySleepTokens(true);

        // The display orientation should be changed by the activity so there is no relaunch.
        verify(activity, never()).relaunchActivityLocked(anyBoolean());
        assertEquals(rotatedConfig.orientation, display.getConfiguration().orientation);
    }

    /**
     * Verifies that removal of activity with task and root task is done correctly.
     */
    @Test
    public void testRemovingRootTaskOnAppCrash() {
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        final int originalRootTaskCount = defaultTaskDisplayArea.getRootTaskCount();
        final Task rootTask = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(rootTask).build();

        assertEquals(originalRootTaskCount + 1, defaultTaskDisplayArea.getRootTaskCount());

        // Let's pretend that the app has crashed.
        firstActivity.app.setThread(null);
        final Task finishedTask = mRootWindowContainer.finishTopCrashedActivities(
                firstActivity.app, "test");

        // Verify that the root task was removed.
        assertEquals(originalRootTaskCount, defaultTaskDisplayArea.getRootTaskCount());
        assertEquals(rootTask, finishedTask);
    }

    /**
     * Verifies that removal of activities with task and root task is done correctly when there are
     * several task display areas.
     */
    @Test
    public void testRemovingRootTaskOnAppCrash_multipleDisplayAreas() {
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        final int originalRootTaskCount = defaultTaskDisplayArea.getRootTaskCount();
        final Task rootTask = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(rootTask).build();
        assertEquals(originalRootTaskCount + 1, defaultTaskDisplayArea.getRootTaskCount());

        final DisplayContent dc = defaultTaskDisplayArea.getDisplayContent();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                dc, mRootWindowContainer.mWmService, "TestTaskDisplayArea", FEATURE_VENDOR_FIRST);
        final Task secondRootTask = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        new ActivityBuilder(mAtm).setTask(secondRootTask).setUseProcess(firstActivity.app).build();
        assertEquals(1, secondTaskDisplayArea.getRootTaskCount());

        // Let's pretend that the app has crashed.
        firstActivity.app.setThread(null);
        mRootWindowContainer.finishTopCrashedActivities(firstActivity.app, "test");

        // Verify that the root tasks were removed.
        assertEquals(originalRootTaskCount, defaultTaskDisplayArea.getRootTaskCount());
        assertEquals(0, secondTaskDisplayArea.getRootTaskCount());
    }

    @Test
    public void testFocusability() {
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        final Task task = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        // Created tasks are focusable by default.
        assertTrue(task.isTopActivityFocusable());
        assertTrue(activity.isFocusable());

        // If the task is made unfocusable, its activities should inherit that.
        task.setFocusable(false);
        assertFalse(task.isTopActivityFocusable());
        assertFalse(activity.isFocusable());

        final Task pinnedTask = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord pinnedActivity = new ActivityBuilder(mAtm)
                .setTask(pinnedTask).build();

        // We should not be focusable when in pinned mode
        assertFalse(pinnedTask.isTopActivityFocusable());
        assertFalse(pinnedActivity.isFocusable());

        // Add flag forcing focusability.
        pinnedActivity.info.flags |= FLAG_ALWAYS_FOCUSABLE;

        // Task with FLAG_ALWAYS_FOCUSABLE should be focusable.
        assertTrue(pinnedTask.isTopActivityFocusable());
        assertTrue(pinnedActivity.isFocusable());
    }

    /**
     * Verify that home root task would be moved to front when the top activity is Recents.
     */
    @Test
    public void testFindTaskToMoveToFrontWhenRecentsOnTop() {
        // Create root task/task on default display.
        final Task targetRootTask = new TaskBuilder(mSupervisor)
                .setCreateActivity(true)
                .setOnTop(false)
                .build();
        final Task targetTask = targetRootTask.getBottomMostTask();

        // Create Recents on top of the display.
        final Task rootTask = new TaskBuilder(mSupervisor)
                .setCreateActivity(true)
                .setActivityType(ACTIVITY_TYPE_RECENTS)
                .build();

        final String reason = "findTaskToMoveToFront";
        mSupervisor.findTaskToMoveToFront(targetTask, 0, ActivityOptions.makeBasic(), reason,
                false);

        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        verify(taskDisplayArea).moveHomeRootTaskToFront(contains(reason));
    }

    /**
     * Verify that home root task won't be moved to front if the top activity on other display is
     * Recents.
     */
    @Test
    public void testFindTaskToMoveToFrontWhenRecentsOnOtherDisplay() {
        // Create tasks on default display.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task targetRootTask = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task targetTask = new TaskBuilder(mSupervisor).setParentTask(targetRootTask)
                .build();

        // Create Recents on secondary display.
        final TestDisplayContent secondDisplay = addNewDisplayContentAt(
                DisplayContent.POSITION_TOP);
        final Task rootTask = secondDisplay.getDefaultTaskDisplayArea()
                .createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mAtm).setTask(rootTask).build();

        final String reason = "findTaskToMoveToFront";
        mSupervisor.findTaskToMoveToFront(targetTask, 0, ActivityOptions.makeBasic(), reason,
                false);

        verify(taskDisplayArea, never()).moveHomeRootTaskToFront(contains(reason));
    }

    /**
     * Verify if a root task is not at the topmost position, it should be able to resume its
     * activity if the root task is the top focused.
     */
    @Test
    public void testResumeActivityWhenNonTopmostRootTaskIsTopFocused() {
        // Create a root task at bottom.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask = spy(taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(rootTask).build();
        taskDisplayArea.positionChildAt(POSITION_BOTTOM, rootTask, false /*includingParents*/);

        // Assume the task is not at the topmost position (e.g. behind always-on-top root tasks)
        // but it is the current top focused task.
        assertFalse(rootTask.isTopRootTaskInDisplayArea());
        doReturn(rootTask).when(mRootWindowContainer).getTopDisplayFocusedRootTask();

        // Use the task as target to resume.
        mRootWindowContainer.resumeFocusedTasksTopActivities(rootTask, activity,
                null /* targetOptions */);

        // Verify the target task should resume its activity.
        verify(rootTask, times(1)).resumeTopActivityUncheckedLocked(
                eq(activity), eq(null /* targetOptions */), eq(false));
    }

    /**
     * Verify that home activity will be started on a display even if another display has a
     * focusable activity.
     */
    @Test
    public void testResumeFocusedRootTasksStartsHomeActivity_NoActivities() {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        taskDisplayArea.getRootHomeTask().removeIfPossible();
        taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);

        doReturn(true).when(mRootWindowContainer).resumeHomeActivity(any(), any(), any());

        setBooted(mAtm);

        // Trigger resume on all displays
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify that home activity was started on the default display
        verify(mRootWindowContainer).resumeHomeActivity(any(), any(), eq(taskDisplayArea));
    }

    /**
     * Verify that home activity will be started on a display even if another display has a
     * focusable activity.
     */
    @Test
    public void testResumeFocusedRootTasksStartsHomeActivity_ActivityOnSecondaryScreen() {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        taskDisplayArea.getRootHomeTask().removeIfPossible();
        taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);

        // Create an activity on secondary display.
        final TestDisplayContent secondDisplay = addNewDisplayContentAt(
                DisplayContent.POSITION_TOP);
        final Task rootTask = secondDisplay.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mAtm).setTask(rootTask).build();

        doReturn(true).when(mRootWindowContainer).resumeHomeActivity(any(), any(), any());

        setBooted(mAtm);

        // Trigger resume on all displays
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify that home activity was started on the default display
        verify(mRootWindowContainer).resumeHomeActivity(any(), any(), eq(taskDisplayArea));
    }

    /**
     * Verify that a lingering transition is being executed in case the activity to be resumed is
     * already resumed
     */
    @Test
    public void testResumeActivityLingeringTransition() {
        // Create a root task at top.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask = spy(taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(rootTask).setOnTop(true).build();
        activity.setState(RESUMED, "test");

        // Assume the task is at the topmost position
        assertTrue(rootTask.isTopRootTaskInDisplayArea());

        // Use the task as target to resume.
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify the lingering app transition is being executed because it's already resumed
        verify(rootTask, times(1)).executeAppTransition(any());
    }

    @Test
    public void testResumeActivityLingeringTransition_notExecuted() {
        // Create a root task at bottom.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask = spy(taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(rootTask).setOnTop(true).build();
        activity.setState(RESUMED, "test");
        taskDisplayArea.positionChildAt(POSITION_BOTTOM, rootTask, false /*includingParents*/);

        // Assume the task is at the topmost position
        assertFalse(rootTask.isTopRootTaskInDisplayArea());
        doReturn(taskDisplayArea.getHomeActivity()).when(taskDisplayArea).topRunningActivity();

        // Use the task as target to resume.
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify the lingering app transition is being executed because it's already resumed
        verify(rootTask, never()).executeAppTransition(any());
    }

    /**
     * Tests that home activities can be started on the displays that supports system decorations.
     */
    @Test
    public void testStartHomeOnAllDisplays() {
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        mockResolveSecondaryHomeActivity();

        // Create secondary displays.
        final TestDisplayContent secondDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setSystemDecorations(true).build();

        doReturn(true).when(mRootWindowContainer)
                .ensureVisibilityAndConfig(any(), anyInt(), anyBoolean());
        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        mRootWindowContainer.startHomeOnAllDisplays(0, "testStartHome");

        assertTrue(mRootWindowContainer.getDefaultDisplay().getTopRootTask().isActivityTypeHome());
        assertNotNull(secondDisplay.getTopRootTask());
        assertTrue(secondDisplay.getTopRootTask().isActivityTypeHome());
    }

    /**
     * Tests that home activities won't be started before booting when display added.
     */
    @Test
    public void testNotStartHomeBeforeBoot() {
        final int displayId = 1;
        doReturn(false).when(mAtm).isBooting();
        doReturn(false).when(mAtm).isBooted();
        mRootWindowContainer.onDisplayAdded(displayId);
        verify(mRootWindowContainer, never()).startHomeOnDisplay(anyInt(), any(), anyInt());
    }

    /**
     * Tests whether home can be started if being instrumented.
     */
    @Test
    public void testCanStartHomeWhenInstrumented() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        final WindowProcessController app = mock(WindowProcessController.class);
        doReturn(app).when(mAtm).getProcessController(any(), anyInt());

        // Can not start home if we don't want to start home while home is being instrumented.
        doReturn(true).when(app).isInstrumenting();
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        assertFalse(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                false /* allowInstrumenting*/));

        // Can start home for other cases.
        assertTrue(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                true /* allowInstrumenting*/));

        doReturn(false).when(app).isInstrumenting();
        assertTrue(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                false /* allowInstrumenting*/));
        assertTrue(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                true /* allowInstrumenting*/));
    }

    /**
     * Tests that secondary home activity should not be resolved if device is still locked.
     */
    @Test
    public void testStartSecondaryHomeOnDisplayWithUserKeyLocked() {
        // Create secondary displays.
        final TestDisplayContent secondDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setSystemDecorations(true).build();

        // Use invalid user id to let StorageManager.isCeStorageUnlocked() return false.
        final int currentUser = mRootWindowContainer.mCurrentUser;
        mRootWindowContainer.mCurrentUser = -1;

        mRootWindowContainer.startHomeOnDisplay(0 /* userId */, "testStartSecondaryHome",
                secondDisplay.mDisplayId, true /* allowInstrumenting */, true /* fromHomeKey */);

        try {
            verify(mRootWindowContainer, never()).resolveSecondaryHomeActivity(anyInt(), any());
        } finally {
            mRootWindowContainer.mCurrentUser = currentUser;
        }
    }

    /**
     * Tests that secondary home activity should not be resolved if display does not support system
     * decorations.
     */
    @Test
    public void testStartSecondaryHomeOnDisplayWithoutSysDecorations() {
        // Create secondary displays.
        final TestDisplayContent secondDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setSystemDecorations(false).build();

        mRootWindowContainer.startHomeOnDisplay(0 /* userId */, "testStartSecondaryHome",
                secondDisplay.mDisplayId, true /* allowInstrumenting */, true /* fromHomeKey */);

        verify(mRootWindowContainer, never()).resolveSecondaryHomeActivity(anyInt(), any());
    }

    /**
     * Tests that when starting {@link ResolverActivity} for home, it should use the standard
     * activity type (in a new root task) so the order of back stack won't be broken.
     */
    @Test
    public void testStartResolverActivityForHome() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = "android";
        info.name = ResolverActivity.class.getName();
        doReturn(info).when(mRootWindowContainer).resolveHomeActivity(anyInt(), any());

        mRootWindowContainer.startHomeOnDisplay(0 /* userId */, "test", DEFAULT_DISPLAY);
        final ActivityRecord resolverActivity = mRootWindowContainer.topRunningActivity();

        assertEquals(info, resolverActivity.info);
        assertEquals(ACTIVITY_TYPE_STANDARD, resolverActivity.getRootTask().getActivityType());
    }

    /**
     * Tests that secondary home should be selected if primary home not set.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenPrimaryHomeNotSet() {
        // Setup: primary home not set.
        final Intent primaryHomeIntent = mAtm.getHomeIntent();
        final ActivityInfo aInfoPrimary = new ActivityInfo();
        aInfoPrimary.name = ResolverActivity.class.getName();
        doReturn(aInfoPrimary).when(mRootWindowContainer).resolveHomeActivity(anyInt(),
                refEq(primaryHomeIntent));
        // Setup: set secondary home.
        mockResolveHomeActivity(false /* primaryHome */, false /* forceSystemProvided */);

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false /* primaryHome*/);
        assertEquals(aInfoSecondary.name, resolvedInfo.first.name);
        assertEquals(aInfoSecondary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }

    /**
     * Tests that the default secondary home activity is always picked when it is in forced by
     * config_useSystemProvidedLauncherForSecondary.
     */
    @Test
    public void testResolveSecondaryHomeActivityForced() {
        // SetUp: set primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        // SetUp: set secondary home and force it.
        mockResolveHomeActivity(false /* primaryHome */, true /* forceSystemProvided */);
        final Intent secondaryHomeIntent =
                mAtm.getSecondaryHomeIntent(null /* preferredPackage */);
        final List<ResolveInfo> resolutions = new ArrayList<>();
        final ResolveInfo resolveInfo = new ResolveInfo();
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false /* primaryHome*/);
        resolveInfo.activityInfo = aInfoSecondary;
        resolutions.add(resolveInfo);
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(),
                refEq(secondaryHomeIntent));
        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        assertEquals(aInfoSecondary.name, resolvedInfo.first.name);
        assertEquals(aInfoSecondary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }

    /**
     * Tests that secondary home should be selected if primary home not support secondary displays
     * or there is no matched activity in the same package as selected primary home.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenPrimaryHomeNotSupportMultiDisplay() {
        // Setup: there is no matched activity in the same package as selected primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        final List<ResolveInfo> resolutions = new ArrayList<>();
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(), any());
        // Setup: set secondary home.
        mockResolveHomeActivity(false /* primaryHome */, false /* forceSystemProvided */);

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false /* primaryHome*/);
        assertEquals(aInfoSecondary.name, resolvedInfo.first.name);
        assertEquals(aInfoSecondary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }
    /**
     * Tests that primary home activity should be selected if it already support secondary displays.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenPrimaryHomeSupportMultiDisplay() {
        // SetUp: set primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        // SetUp: put primary home info on 2nd item
        final List<ResolveInfo> resolutions = new ArrayList<>();
        final ResolveInfo infoFake1 = new ResolveInfo();
        infoFake1.activityInfo = new ActivityInfo();
        infoFake1.activityInfo.name = "fakeActivity1";
        infoFake1.activityInfo.applicationInfo = new ApplicationInfo();
        infoFake1.activityInfo.applicationInfo.packageName = "fakePackage1";
        final ResolveInfo infoFake2 = new ResolveInfo();
        final ActivityInfo aInfoPrimary = getFakeHomeActivityInfo(true /* primaryHome */);
        infoFake2.activityInfo = aInfoPrimary;
        resolutions.add(infoFake1);
        resolutions.add(infoFake2);
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(), any());

        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        assertEquals(aInfoPrimary.name, resolvedInfo.first.name);
        assertEquals(aInfoPrimary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }

    /**
     * Tests that the first one that matches should be selected if there are multiple activities.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenOtherActivitySupportMultiDisplay() {
        // SetUp: set primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        // Setup: prepare two eligible activity info.
        final List<ResolveInfo> resolutions = new ArrayList<>();
        final ResolveInfo infoFake1 = new ResolveInfo();
        infoFake1.activityInfo = new ActivityInfo();
        infoFake1.activityInfo.name = "fakeActivity1";
        infoFake1.activityInfo.applicationInfo = new ApplicationInfo();
        infoFake1.activityInfo.applicationInfo.packageName = "fakePackage1";
        final ResolveInfo infoFake2 = new ResolveInfo();
        infoFake2.activityInfo = new ActivityInfo();
        infoFake2.activityInfo.name = "fakeActivity2";
        infoFake2.activityInfo.applicationInfo = new ApplicationInfo();
        infoFake2.activityInfo.applicationInfo.packageName = "fakePackage2";
        resolutions.add(infoFake1);
        resolutions.add(infoFake2);
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(), any());

        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        // Use the first one of matched activities in the same package as selected primary home.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));

        assertEquals(infoFake1.activityInfo.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
        assertEquals(infoFake1.activityInfo.name, resolvedInfo.first.name);
    }

    @Test
    public void testGetLaunchRootTaskOnSecondaryTaskDisplayArea() {
        // Adding another TaskDisplayArea to the default display.
        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final TaskDisplayArea taskDisplayArea = new TaskDisplayArea(display,
                mWm, "TDA", FEATURE_VENDOR_FIRST);
        display.addChild(taskDisplayArea, POSITION_BOTTOM);

        // Making sure getting the root task from the preferred TDA and the preferred windowing mode
        LaunchParamsController.LaunchParams launchParams =
                new LaunchParamsController.LaunchParams();
        launchParams.mPreferredTaskDisplayArea = taskDisplayArea;
        launchParams.mWindowingMode = WINDOWING_MODE_FREEFORM;
        Task root = mRootWindowContainer.getOrCreateRootTask(null /* r */, null /* options */,
                null /* candidateTask */, null /* sourceTask */, true /* onTop */, launchParams,
                0 /* launchParams */);
        assertEquals(taskDisplayArea, root.getTaskDisplayArea());
        assertEquals(WINDOWING_MODE_FREEFORM, root.getWindowingMode());

        // Making sure still getting the root task from the preferred TDA when passing in a
        // launching activity.
        ActivityRecord r = new ActivityBuilder(mAtm).build();
        root = mRootWindowContainer.getOrCreateRootTask(r, null /* options */,
                null /* candidateTask */, null /* sourceTask */, true /* onTop */, launchParams,
                0 /* launchParams */);
        assertEquals(taskDisplayArea, root.getTaskDisplayArea());
        assertEquals(WINDOWING_MODE_FREEFORM, root.getWindowingMode());
    }

    @Test
    public void testGetOrCreateRootTaskOnDisplayWithCandidateRootTask() {
        // Create a root task with an activity on secondary display.
        final TestDisplayContent secondaryDisplay = new TestDisplayContent.Builder(mAtm, 300,
                600).build();
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(secondaryDisplay).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        // Make sure the root task is valid and can be reused on default display.
        final Task rootTask = mRootWindowContainer.getDefaultTaskDisplayArea().getOrCreateRootTask(
                activity, null /* options */, task, null /* sourceTask */, null /* launchParams */,
                0 /* launchFlags */, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(task, rootTask);
    }

    @Test
    public void testSwitchUser_missingHomeRootTask() {
        final Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        doReturn(fullscreenTask).when(mRootWindowContainer).getTopDisplayFocusedRootTask();

        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task rootHomeTask = taskDisplayArea.getRootHomeTask();
        if (rootHomeTask != null) {
            rootHomeTask.removeImmediately();
        }
        assertNull(taskDisplayArea.getRootHomeTask());

        int currentUser = mRootWindowContainer.mCurrentUser;
        int otherUser = currentUser + 1;

        mRootWindowContainer.switchUser(otherUser, null);

        assertNotNull(taskDisplayArea.getRootHomeTask());
        assertEquals(taskDisplayArea.getTopRootTask(), taskDisplayArea.getRootHomeTask());
    }

    @Test
    public void testLockAllProfileTasks() {
        final int profileUid = UserHandle.PER_USER_RANGE + UserHandle.MIN_SECONDARY_USER_ID;
        final int profileUserId = UserHandle.getUserId(profileUid);
        // Create an activity belonging to the profile user.
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true)
                .setUid(profileUid).build();
        final Task task = activity.getTask();

        // Create another activity belonging to current user on top.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        topActivity.intent.setAction(Intent.ACTION_MAIN);

        // Make sure the listeners will be notified for putting the task to locked state
        TaskChangeNotificationController controller = mAtm.getTaskChangeNotificationController();
        spyOn(controller);
        mWm.mRoot.lockAllProfileTasks(profileUserId);
        verify(controller).notifyTaskProfileLocked(any(), eq(profileUserId));

        // Create the work lock activity on top of the task
        final ActivityRecord workLockActivity = new ActivityBuilder(mAtm).setTask(task).build();
        workLockActivity.intent.setAction(ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER);
        doReturn(workLockActivity.mActivityComponent).when(mAtm).getSysUiServiceComponentLocked();

        // Make sure the listener won't be notified again.
        clearInvocations(controller);
        mWm.mRoot.lockAllProfileTasks(profileUserId);
        verify(controller, never()).notifyTaskProfileLocked(any(), anyInt());
    }

    /**
     * Mock {@link RootWindowContainer#resolveHomeActivity} for returning consistent activity
     * info for test cases.
     *
     * @param primaryHome Indicate to use primary home intent as parameter, otherwise, use
     *                    secondary home intent.
     * @param forceSystemProvided Indicate to force using system provided home activity.
     */
    private void mockResolveHomeActivity(boolean primaryHome, boolean forceSystemProvided) {
        ActivityInfo targetActivityInfo = getFakeHomeActivityInfo(primaryHome);
        Intent targetIntent;
        if (primaryHome) {
            targetIntent = mAtm.getHomeIntent();
        } else {
            Resources resources = mContext.getResources();
            spyOn(resources);
            doReturn(targetActivityInfo.applicationInfo.packageName).when(resources).getString(
                    com.android.internal.R.string.config_secondaryHomePackage);
            doReturn(forceSystemProvided).when(resources).getBoolean(
                    com.android.internal.R.bool.config_useSystemProvidedLauncherForSecondary);
            targetIntent = mAtm.getSecondaryHomeIntent(null /* preferredPackage */);
        }
        doReturn(targetActivityInfo).when(mRootWindowContainer).resolveHomeActivity(anyInt(),
                refEq(targetIntent));
    }

    /**
     * Mock {@link RootWindowContainer#resolveSecondaryHomeActivity} for returning consistent
     * activity info for test cases.
     */
    private void mockResolveSecondaryHomeActivity() {
        final Intent secondaryHomeIntent = mAtm
                .getSecondaryHomeIntent(null /* preferredPackage */);
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false);
        doReturn(Pair.create(aInfoSecondary, secondaryHomeIntent)).when(mRootWindowContainer)
                .resolveSecondaryHomeActivity(anyInt(), any());
    }

    private ActivityInfo getFakeHomeActivityInfo(boolean primaryHome) {
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.name = primaryHome ? "fakeHomeActivity" : "fakeSecondaryHomeActivity";
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName =
                primaryHome ? "fakeHomePackage" : "fakeSecondaryHomePackage";
        return  aInfo;
    }
}

