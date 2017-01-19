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

package com.android.server.wm;

import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import org.junit.Test;

import android.hardware.display.DisplayManagerGlobal;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;
import android.view.DisplayInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Test class for {@link TaskWindowContainerController}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.TaskWindowContainerControllerTests
 */
@SmallTest
@Presubmit
@org.junit.runner.RunWith(AndroidJUnit4.class)
public class TaskWindowContainerControllerTests extends WindowTestsBase {

    @Test
    public void testRemoveContainer() throws Exception {
        final TestTaskWindowContainerController taskController =
                new TestTaskWindowContainerController();
        final TestAppWindowContainerController appController =
                new TestAppWindowContainerController(taskController);

        taskController.removeContainer();
        // Assert that the container was removed.
        assertNull(taskController.mContainer);
        assertNull(appController.mContainer);
    }

    @Test
    public void testRemoveContainer_DeferRemoval() throws Exception {
        final TestTaskWindowContainerController taskController =
                new TestTaskWindowContainerController();
        final TestAppWindowContainerController appController =
                new TestAppWindowContainerController(taskController);

        final TestTask task = (TestTask) taskController.mContainer;
        final AppWindowToken app = appController.mContainer;
        task.mShouldDeferRemoval = true;

        taskController.removeContainer();
        // For the case of deferred removal the task controller will no longer be connected to the
        // container, but the app controller will still be connected to the its container until
        // the task window container is removed.
        assertNull(taskController.mContainer);
        assertNull(task.getController());
        assertNotNull(appController.mContainer);
        assertNotNull(app.getController());

        task.removeImmediately();
        assertNull(appController.mContainer);
        assertNull(app.getController());
    }

    @Test
    public void testReparent() throws Exception {
        final TaskStack stack1 = createTaskStackOnDisplay(sDisplayContent);
        final TestTaskWindowContainerController taskController =
                new TestTaskWindowContainerController(stack1.mStackId);
        final TaskStack stack2 = createTaskStackOnDisplay(sDisplayContent);
        final TestTaskWindowContainerController taskController2 =
                new TestTaskWindowContainerController(stack2.mStackId);

        boolean gotException = false;
        try {
            taskController.reparent(stack1.mStackId, 0);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to the same parent", gotException);

        gotException = false;
        try {
            taskController.reparent(sNextStackId + 1, 0);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to a stackId that doesn't exist", gotException);

        taskController.reparent(stack2.mStackId, 0);
        assertEquals(stack2, taskController.mContainer.getParent());
        assertEquals(0, ((TestTask) taskController.mContainer).positionInParent());
        assertEquals(1, ((TestTask) taskController2.mContainer).positionInParent());
    }

    @Test
    public void testReparentBetweenDisplays() throws Exception {
        // Create first stack on primary display.
        final TaskStack stack1 = createTaskStackOnDisplay(sDisplayContent);
        final TestTaskWindowContainerController taskController =
                new TestTaskWindowContainerController(stack1.mStackId);
        final TestTask task1 = (TestTask) taskController.mContainer;
        task1.mOnDisplayChangedCalled = false;

        // Create second display and put second stack on it.
        final Display display = new Display(DisplayManagerGlobal.getInstance(),
                sDisplayContent.getDisplayId() + 1, new DisplayInfo(),
                DEFAULT_DISPLAY_ADJUSTMENTS);
        final DisplayContent dc = new DisplayContent(display, sWm, sLayersController,
                new WallpaperController(sWm));
        sWm.mRoot.addChild(dc, 1);
        final TaskStack stack2 = createTaskStackOnDisplay(dc);
        final TestTaskWindowContainerController taskController2 =
                new TestTaskWindowContainerController(stack2.mStackId);
        final TestTask task2 = (TestTask) taskController2.mContainer;

        // Reparent and check state
        taskController.reparent(stack2.mStackId, 0);
        assertEquals(stack2, task1.getParent());
        assertEquals(0, task1.positionInParent());
        assertEquals(1, task2.positionInParent());
        assertTrue(task1.mOnDisplayChangedCalled);
    }
}
