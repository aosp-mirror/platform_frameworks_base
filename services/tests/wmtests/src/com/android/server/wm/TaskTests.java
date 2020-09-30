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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;

import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link Task}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskTests extends WindowTestsBase {

    @Test
    public void testRemoveContainer() {
        final ActivityStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stackController1, 0 /* userId */);
        final ActivityRecord activity =
                WindowTestUtils.createActivityRecordInTask(mDisplayContent, task);

        task.removeIfPossible();
        // Assert that the container was removed.
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(activity.getParent());
    }

    @Test
    public void testRemoveContainer_deferRemoval() {
        final ActivityStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stackController1, 0 /* userId */);
        final ActivityRecord activity =
                WindowTestUtils.createActivityRecordInTask(mDisplayContent, task);

        doReturn(true).when(task).shouldDeferRemoval();

        task.removeIfPossible();
        // For the case of deferred removal the task will still be connected to the its app token
        // until the task window container is removed.
        assertNotNull(task.getParent());
        assertNotEquals(0, task.getChildCount());
        assertNotNull(activity.getParent());

        task.removeImmediately();
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(activity.getParent());
    }

    @Test
    public void testReparent() {
        final ActivityStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stackController1, 0 /* userId */);
        final ActivityStack stackController2 = createTaskStackOnDisplay(mDisplayContent);
        final Task task2 = createTaskInStack(stackController2, 0 /* userId */);

        boolean gotException = false;
        try {
            task.reparent(stackController1, 0, false/* moveParents */, "testReparent");
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to the same parent", gotException);

        gotException = false;
        try {
            task.reparent(null, 0, false/* moveParents */, "testReparent");
        } catch (Exception e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to a stack that doesn't exist", gotException);

        task.reparent(stackController2, 0, false/* moveParents */, "testReparent");
        assertEquals(stackController2, task.getParent());
        assertEquals(0, task.getParent().mChildren.indexOf(task));
        assertEquals(1, task2.getParent().mChildren.indexOf(task2));
    }

    @Test
    public void testReparent_BetweenDisplays() {
        // Create first stack on primary display.
        final ActivityStack stack1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack1, 0 /* userId */);
        assertEquals(mDisplayContent, stack1.getDisplayContent());

        // Create second display and put second stack on it.
        final DisplayContent dc = createNewDisplay();
        final ActivityStack stack2 = createTaskStackOnDisplay(dc);
        final Task task2 = createTaskInStack(stack2, 0 /* userId */);
        // Reparent and check state
        clearInvocations(task);  // reset the number of onDisplayChanged for task.
        task.reparent(stack2, 0, false /* moveParents */, "testReparent_BetweenDisplays");
        assertEquals(stack2, task.getParent());
        assertEquals(0, task.getParent().mChildren.indexOf(task));
        assertEquals(1, task2.getParent().mChildren.indexOf(task2));
        verify(task, times(1)).onDisplayChanged(any());
    }

    @Test
    public void testBounds() {
        final ActivityStack stack1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack1, 0 /* userId */);

        // Check that setting bounds also updates surface position
        task.setWindowingMode(WINDOWING_MODE_FREEFORM);
        Rect bounds = new Rect(10, 10, 100, 200);
        task.setBounds(bounds);
        assertEquals(new Point(bounds.left, bounds.top), task.getLastSurfacePosition());
    }

    @Test
    public void testIsInStack() {
        final Task task1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task2 = createTaskStackOnDisplay(mDisplayContent);
        final ActivityRecord activity1 =
                WindowTestUtils.createActivityRecordInTask(mDisplayContent, task1);
        final ActivityRecord activity2 =
                WindowTestUtils.createActivityRecordInTask(mDisplayContent, task2);
        assertEquals(activity1, task1.isInTask(activity1));
        assertNull(task1.isInTask(activity2));
    }

    @Test
    public void testRemoveChildForOverlayTask() {
        final Task task = createTaskStackOnDisplay(mDisplayContent);
        final int taskId = task.mTaskId;
        final ActivityRecord activity1 =
                WindowTestUtils.createActivityRecordInTask(mDisplayContent, task);
        final ActivityRecord activity2 =
                WindowTestUtils.createActivityRecordInTask(mDisplayContent, task);
        final ActivityRecord activity3 =
                WindowTestUtils.createActivityRecordInTask(mDisplayContent, task);
        activity1.setTaskOverlay(true);
        activity2.setTaskOverlay(true);
        activity3.setTaskOverlay(true);

        assertEquals(3, task.getChildCount());
        assertTrue(task.onlyHasTaskOverlayActivities(true));

        task.removeChild(activity1);

        verify(task.mStackSupervisor).removeTask(any(), anyBoolean(), anyBoolean(), anyString());
        assertEquals(2, task.getChildCount());
        task.forAllActivities((r) -> {
            assertTrue(r.finishing);
        });
    }

    @Test
    public void testSwitchUser() {
        final Task rootTask = createTaskStackOnDisplay(mDisplayContent);
        final Task childTask = createTaskInStack((ActivityStack) rootTask, 0 /* userId */);
        final Task leafTask1 = createTaskInStack((ActivityStack) childTask, 10 /* userId */);
        final Task leafTask2 = createTaskInStack((ActivityStack) childTask, 0 /* userId */);
        assertEquals(1, rootTask.getChildCount());
        assertEquals(leafTask2, childTask.getTopChild());

        doReturn(true).when(leafTask1).showToCurrentUser();
        rootTask.switchUser(10);
        assertEquals(1, rootTask.getChildCount());
        assertEquals(leafTask1, childTask.getTopChild());
    }
}
