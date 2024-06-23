/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;

import android.content.Intent;
import android.platform.test.annotations.Presubmit;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link WindowContainerTransaction}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowContainerTransactionTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowContainerTransactionTests extends WindowTestsBase {
    @Test
    public void testRemoveTask() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);

        WindowContainerTransaction wct = new WindowContainerTransaction();
        WindowContainerToken token = task.getTaskInfo().token;
        wct.removeTask(token);
        applyTransaction(wct);

        // There is still an activity to be destroyed, so the task is not removed immediately.
        assertNotNull(task.getParent());
        assertTrue(rootTask.hasChild());
        assertTrue(task.hasChild());
        assertTrue(activity.finishing);

        activity.destroyed("testRemoveContainer");
        // Assert that the container was removed after the activity is destroyed.
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(activity.getParent());
        verify(mAtm.getLockTaskController(), atLeast(1)).clearLockedTask(task);
        verify(mAtm.getLockTaskController(), atLeast(1)).clearLockedTask(rootTask);
    }

    @Test
    public void testDesktopMode_tasksAreBroughtToFront() {
        final TestDesktopOrganizer desktopOrganizer = new TestDesktopOrganizer(mAtm);
        TaskDisplayArea tda = desktopOrganizer.mDefaultTDA;
        List<ActivityRecord> activityRecords = new ArrayList<>();
        int numberOfTasks = 4;
        desktopOrganizer.createFreeformTasksWithActivities(desktopOrganizer,
                activityRecords, numberOfTasks);

        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        // Bring home to front of the tasks
        desktopOrganizer.bringHomeToFront();

        // Bring tasks in front of the home
        WindowContainerTransaction wct = new WindowContainerTransaction();
        desktopOrganizer.bringDesktopTasksToFront(wct);
        applyTransaction(wct);

        // Verify tasks are resumed and in correct z-order
        verify(mRootWindowContainer, times(2)).ensureActivitiesVisible();
        for (int i = 0; i < numberOfTasks - 1; i++) {
            assertTrue(tda.mChildren
                    .indexOf(desktopOrganizer.mTasks.get(i).getRootTask())
                    < tda.mChildren.indexOf(desktopOrganizer.mTasks.get(i + 1).getRootTask()));
        }
    }

    @Test
    public void testDesktopMode_moveTaskToDesktop() {
        final TestDesktopOrganizer desktopOrganizer = new TestDesktopOrganizer(mAtm);
        TaskDisplayArea tda = desktopOrganizer.mDefaultTDA;
        List<ActivityRecord> activityRecords = new ArrayList<>();
        int numberOfTasks = 4;
        desktopOrganizer.createFreeformTasksWithActivities(desktopOrganizer,
                activityRecords, numberOfTasks);

        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        // Bring home to front of the tasks
        desktopOrganizer.bringHomeToFront();

        // Bring tasks in front of the home and newly moved task to on top of them
        WindowContainerTransaction wct = new WindowContainerTransaction();
        desktopOrganizer.bringDesktopTasksToFront(wct);
        desktopOrganizer.addMoveToDesktopChanges(wct, task, true);
        wct.setBounds(task.getTaskInfo().token, desktopOrganizer.getDefaultDesktopTaskBounds());
        applyTransaction(wct);

        // Verify tasks are resumed
        verify(mRootWindowContainer, times(2)).ensureActivitiesVisible();

        // Tasks are in correct z-order
        for (int i = 0; i < numberOfTasks - 1; i++) {
            assertTrue(tda.mChildren
                    .indexOf(desktopOrganizer.mTasks.get(i).getRootTask())
                    < tda.mChildren.indexOf(desktopOrganizer.mTasks.get(i + 1).getRootTask()));
        }
        // New task is on top of other tasks
        assertTrue(tda.mChildren
                .indexOf(desktopOrganizer.mTasks.get(3).getRootTask())
                < tda.mChildren.indexOf(task));

        // New task is in freeform and has specified bounds
        assertEquals(WINDOWING_MODE_FREEFORM, task.getWindowingMode());
        assertEquals(desktopOrganizer.getDefaultDesktopTaskBounds(), task.getBounds());
    }


    @Test
    public void testDesktopMode_moveTaskToFullscreen() {
        final TestDesktopOrganizer desktopOrganizer = new TestDesktopOrganizer(mAtm);
        List<ActivityRecord> activityRecords = new ArrayList<>();
        int numberOfTasks = 4;
        desktopOrganizer.createFreeformTasksWithActivities(desktopOrganizer,
                activityRecords, numberOfTasks);

        Task taskToMove = desktopOrganizer.mTasks.get(numberOfTasks - 1);

        // Bring tasks in front of the home and newly moved task to on top of them
        WindowContainerTransaction wct = new WindowContainerTransaction();
        desktopOrganizer.addMoveToFullscreen(wct, taskToMove, false);
        applyTransaction(wct);

        // New task is in freeform
        assertEquals(WINDOWING_MODE_FULLSCREEN, taskToMove.getWindowingMode());
    }

    @Test
    public void testDesktopMode_moveTaskToFront() {
        final TestDesktopOrganizer desktopOrganizer = new TestDesktopOrganizer(mAtm);
        TaskDisplayArea tda = desktopOrganizer.mDefaultTDA;
        List<ActivityRecord> activityRecords = new ArrayList<>();
        int numberOfTasks = 5;
        desktopOrganizer.createFreeformTasksWithActivities(desktopOrganizer,
                activityRecords, numberOfTasks);

        // Bring task 2 on top of other tasks
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reorder(desktopOrganizer.mTasks.get(2).getTaskInfo().token, true /* onTop */);
        applyTransaction(wct);

        // Tasks are in correct z-order
        assertTrue(tda.mChildren.indexOf(desktopOrganizer.mTasks.get(0).getRootTask())
                < tda.mChildren.indexOf(desktopOrganizer.mTasks.get(1).getRootTask()));
        assertTrue(tda.mChildren.indexOf(desktopOrganizer.mTasks.get(1).getRootTask())
                < tda.mChildren.indexOf(desktopOrganizer.mTasks.get(3).getRootTask()));
        assertTrue(tda.mChildren.indexOf(desktopOrganizer.mTasks.get(3).getRootTask())
                < tda.mChildren.indexOf(desktopOrganizer.mTasks.get(4).getRootTask()));
        assertTrue(tda.mChildren.indexOf(desktopOrganizer.mTasks.get(4).getRootTask())
                < tda.mChildren.indexOf(desktopOrganizer.mTasks.get(2).getRootTask()));
    }

    private Task createTask(int taskId) {
        return new Task.Builder(mAtm)
                .setTaskId(taskId)
                .setIntent(new Intent())
                .setRealActivity(ActivityBuilder.getDefaultComponent())
                .setEffectiveUid(10050)
                .buildInner();
    }

    private void applyTransaction(@NonNull WindowContainerTransaction t) {
        if (!t.isEmpty()) {
            mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        }
    }
}

