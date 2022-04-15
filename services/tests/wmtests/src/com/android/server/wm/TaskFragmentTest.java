/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.RESUMED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link TaskFragment}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskFragmentTest
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskFragmentTest extends WindowTestsBase {

    private TaskFragmentOrganizer mOrganizer;
    private TaskFragment mTaskFragment;
    private SurfaceControl mLeash;
    @Mock
    private SurfaceControl.Transaction mTransaction;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mOrganizer = new TaskFragmentOrganizer(Runnable::run);
        final ITaskFragmentOrganizer iOrganizer =
                ITaskFragmentOrganizer.Stub.asInterface(mOrganizer.getOrganizerToken().asBinder());
        mAtm.mWindowOrganizerController.mTaskFragmentOrganizerController
                .registerOrganizer(iOrganizer);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        mLeash = mTaskFragment.getSurfaceControl();
        spyOn(mTaskFragment);
        doReturn(mTransaction).when(mTaskFragment).getSyncTransaction();
        doReturn(mTransaction).when(mTaskFragment).getPendingTransaction();
    }

    @Test
    public void testOnConfigurationChanged_updateSurface() {
        final Rect bounds = new Rect(100, 100, 1100, 1100);
        mTaskFragment.setBounds(bounds);

        verify(mTransaction).setPosition(mLeash, 100, 100);
        verify(mTransaction).setWindowCrop(mLeash, 1000, 1000);
    }

    @Test
    public void testStartChangeTransition_resetSurface() {
        mockSurfaceFreezerSnapshot(mTaskFragment.mSurfaceFreezer);
        final Rect startBounds = new Rect(0, 0, 1000, 1000);
        final Rect endBounds = new Rect(500, 500, 1000, 1000);
        mTaskFragment.setBounds(startBounds);
        doReturn(true).when(mTaskFragment).isVisible();
        doReturn(true).when(mTaskFragment).isVisibleRequested();

        clearInvocations(mTransaction);
        mTaskFragment.setBounds(endBounds);

        // Surface reset when prepare transition.
        verify(mTaskFragment).initializeChangeTransition(startBounds);
        verify(mTransaction).setPosition(mLeash, 0, 0);
        verify(mTransaction).setWindowCrop(mLeash, 0, 0);

        clearInvocations(mTransaction);
        mTaskFragment.mSurfaceFreezer.unfreeze(mTransaction);

        // Update surface after animation.
        verify(mTransaction).setPosition(mLeash, 500, 500);
        verify(mTransaction).setWindowCrop(mLeash, 500, 500);
    }

    @Test
    public void testNotOkToAnimate_doNotStartChangeTransition() {
        mockSurfaceFreezerSnapshot(mTaskFragment.mSurfaceFreezer);
        final Rect startBounds = new Rect(0, 0, 1000, 1000);
        final Rect endBounds = new Rect(500, 500, 1000, 1000);
        mTaskFragment.setBounds(startBounds);
        doReturn(true).when(mTaskFragment).isVisible();
        doReturn(true).when(mTaskFragment).isVisibleRequested();

        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        displayPolicy.screenTurnedOff();

        assertFalse(mTaskFragment.okToAnimate());

        mTaskFragment.setBounds(endBounds);

        verify(mTaskFragment, never()).initializeChangeTransition(any());
    }

    /**
     * Tests that when a {@link TaskFragmentInfo} is generated from a {@link TaskFragment}, an
     * activity that has not yet been attached to a process because it is being initialized but
     * belongs to the TaskFragmentOrganizer process is still reported in the TaskFragmentInfo.
     */
    @Test
    public void testActivityStillReported_NotYetAssignedToProcess() {
        mTaskFragment.addChild(new ActivityBuilder(mAtm).setUid(DEFAULT_TASK_FRAGMENT_ORGANIZER_UID)
                .setProcessName(DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME).build());
        final ActivityRecord activity = mTaskFragment.getTopMostActivity();
        // Remove the process to simulate an activity that has not yet been attached to a process
        activity.app = null;
        final TaskFragmentInfo info = activity.getTaskFragment().getTaskFragmentInfo();
        assertEquals(1, info.getRunningActivityCount());
        assertEquals(1, info.getActivities().size());
        assertEquals(false, info.isEmpty());
        assertEquals(activity.token, info.getActivities().get(0));
    }

    @Test
    public void testActivityVisibilityBehindTranslucentTaskFragment() {
        // Having an activity covered by a translucent TaskFragment:
        // Task
        //   - TaskFragment
        //      - Activity (Translucent)
        //   - Activity
        ActivityRecord translucentActivity = new ActivityBuilder(mAtm)
                .setUid(DEFAULT_TASK_FRAGMENT_ORGANIZER_UID).build();
        mTaskFragment.addChild(translucentActivity);
        doReturn(true).when(mTaskFragment).isTranslucent(any());

        ActivityRecord activityBelow = new ActivityBuilder(mAtm).build();
        mTaskFragment.getTask().addChild(activityBelow, 0);

        // Ensure the activity below is visible
        mTaskFragment.getTask().ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */);
        assertEquals(true, activityBelow.isVisibleRequested());
    }

    @Test
    public void testMoveTaskToFront_supportsEnterPipOnTaskSwitchForAdjacentTaskFragment() {
        final Task bottomTask = createTask(mDisplayContent);
        final ActivityRecord bottomActivity = createActivityRecord(bottomTask);
        final Task topTask = createTask(mDisplayContent);
        // First create primary TF, and then secondary TF, so that the secondary will be on the top.
        final TaskFragment primaryTf = createTaskFragmentWithParentTask(
                topTask, false /* createEmbeddedTask */);
        final TaskFragment secondaryTf = createTaskFragmentWithParentTask(
                topTask, false /* createEmbeddedTask */);
        final ActivityRecord primaryActivity = primaryTf.getTopMostActivity();
        final ActivityRecord secondaryActivity = secondaryTf.getTopMostActivity();
        doReturn(true).when(primaryActivity).supportsPictureInPicture();
        doReturn(false).when(secondaryActivity).supportsPictureInPicture();

        primaryTf.setAdjacentTaskFragment(secondaryTf, false /* moveAdjacentTogether */);
        primaryActivity.setState(RESUMED, "test");
        secondaryActivity.setState(RESUMED, "test");

        assertEquals(topTask, bottomTask.getDisplayArea().getTopRootTask());

        // When moving Task to front, the resumed activity that supports PIP should support enter
        // PIP on Task switch even if it is not the topmost in the Task.
        bottomTask.moveTaskToFront(bottomTask, false /* noAnimation */, null /* options */,
                null /* timeTracker */, "test");

        assertTrue(primaryActivity.supportsEnterPipOnTaskSwitch);
        assertFalse(secondaryActivity.supportsEnterPipOnTaskSwitch);
    }

    @Test
    public void testEmbeddedTaskFragmentEnterPip_singleActivity_resetOrganizerOverrideConfig() {
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .setCreateParentTask()
                .createActivityCount(1)
                .build();
        final Task task = taskFragment.getTask();
        final ActivityRecord activity = taskFragment.getTopMostActivity();
        final Rect taskFragmentBounds = new Rect(0, 0, 300, 1000);
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskFragment.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        taskFragment.setBounds(taskFragmentBounds);

        assertEquals(taskFragmentBounds, activity.getBounds());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, activity.getWindowingMode());

        // Move activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(activity,
                null /* launchIntoPipHostActivity */, "test");

        // Ensure taskFragment requested config is reset.
        assertEquals(taskFragment, activity.getOrganizedTaskFragment());
        assertEquals(task, activity.getTask());
        assertTrue(task.inPinnedWindowingMode());
        assertTrue(taskFragment.inPinnedWindowingMode());
        final Rect taskBounds = task.getBounds();
        assertEquals(taskBounds, taskFragment.getBounds());
        assertEquals(taskBounds, activity.getBounds());
        assertEquals(Configuration.EMPTY, taskFragment.getRequestedOverrideConfiguration());
    }

    @Test
    public void testEmbeddedTaskFragmentEnterPip_multiActivities_notifyOrganizer() {
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment0 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .createActivityCount(1)
                .build();
        final TaskFragment taskFragment1 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .createActivityCount(1)
                .build();
        final ActivityRecord activity0 = taskFragment0.getTopMostActivity();
        spyOn(mAtm.mTaskFragmentOrganizerController);

        // Move activity to pinned.
        mRootWindowContainer.moveActivityToPinnedRootTask(activity0,
                null /* launchIntoPipHostActivity */, "test");

        // Ensure taskFragment requested config is reset.
        assertTrue(taskFragment0.mClearedTaskFragmentForPip);
        assertFalse(taskFragment1.mClearedTaskFragmentForPip);
        final TaskFragmentInfo info = taskFragment0.getTaskFragmentInfo();
        assertTrue(info.isTaskFragmentClearedForPip());
        assertTrue(info.isEmpty());
        verify(mAtm.mTaskFragmentOrganizerController)
                .dispatchPendingInfoChangedEvent(taskFragment0);
    }

    @Test
    public void testActivityHasOverlayOverUntrustedModeEmbedded() {
        final Task rootTask = createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD);
        final Task leafTask0 = new TaskBuilder(mSupervisor)
                .setParentTaskFragment(rootTask)
                .build();
        final TaskFragment organizedTf = new TaskFragmentBuilder(mAtm)
                .createActivityCount(2)
                .setParentTask(leafTask0)
                .setFragmentToken(new Binder())
                .setOrganizer(mOrganizer)
                .build();
        final ActivityRecord activity0 = organizedTf.getBottomMostActivity();
        final ActivityRecord activity1 = organizedTf.getTopMostActivity();
        // Bottom activity is untrusted embedding. Top activity is trusted embedded.
        // Activity0 has overlay over untrusted mode embedded.
        activity0.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID + 1;
        activity1.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID;
        doReturn(true).when(organizedTf).isAllowedToEmbedActivityInUntrustedMode(activity0);

        assertTrue(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());

        // Both activities are trusted embedded.
        // None of the two has overlay over untrusted mode embedded.
        activity0.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID;

        assertFalse(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());

        // Bottom activity is trusted embedding. Top activity is untrusted embedded.
        // None of the two has overlay over untrusted mode embedded.
        activity1.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID + 1;

        assertFalse(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());

        // There is an activity in a different leaf task on top of activity0 and activity1.
        // None of the two has overlay over untrusted mode embedded because it is not the same Task.
        final Task leafTask1 = new TaskBuilder(mSupervisor)
                .setParentTaskFragment(rootTask)
                .setOnTop(true)
                .setCreateActivity(true)
                .build();
        final ActivityRecord activity2 = leafTask1.getTopMostActivity();
        activity2.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID + 2;

        assertFalse(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());
    }
}
