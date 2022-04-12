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
}
