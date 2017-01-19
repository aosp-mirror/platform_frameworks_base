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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.hardware.display.DisplayManagerGlobal;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;
import android.view.DisplayInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@link DisplayContent.TaskStackContainers} container in {@link DisplayContent}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.TaskStackContainersTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskStackContainersTests extends WindowTestsBase {

    @Test
    public void testStackPositionChildAt() throws Exception {
        // Test that always-on-top stack can't be moved to position other than top.
        final TaskStack stack1 = createTaskStackOnDisplay(sDisplayContent);
        final TaskStack stack2 = createTaskStackOnDisplay(sDisplayContent);
        final TaskStack pinnedStack = addPinnedStack();

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stack1Pos = taskStackContainer.mChildren.indexOf(stack1);
        final int stack2Pos = taskStackContainer.mChildren.indexOf(stack2);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(pinnedStack);
        assertGreaterThan(pinnedStackPos, stack2Pos);
        assertGreaterThan(stack2Pos, stack1Pos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_BOTTOM, pinnedStack, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), pinnedStack);

        taskStackContainer.positionChildAt(1, pinnedStack, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), pinnedStack);
    }
    @Test
    public void testStackPositionBelowPinnedStack() throws Exception {
        // Test that no stack can be above pinned stack.
        final TaskStack pinnedStack = addPinnedStack();
        final TaskStack stack1 = createTaskStackOnDisplay(sDisplayContent);

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stackPos = taskStackContainer.mChildren.indexOf(stack1);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(pinnedStack);
        assertGreaterThan(pinnedStackPos, stackPos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_TOP, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), pinnedStack);

        taskStackContainer.positionChildAt(taskStackContainer.mChildren.size() - 1, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), pinnedStack);
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

        // Reparent and check state.DisplayContent.java:2572
        sWm.moveStackToDisplay(stack1.mStackId, dc.getDisplayId());
        assertEquals(dc, stack1.getDisplayContent());
        final int stack1PositionInParent = stack1.getParent().mChildren.indexOf(stack1);
        final int stack2PositionInParent = stack1.getParent().mChildren.indexOf(stack2);
        assertEquals(stack1PositionInParent, stack2PositionInParent + 1);
        assertTrue(task1.mOnDisplayChangedCalled);
    }

    private TaskStack addPinnedStack() {
        TaskStack pinnedStack = sWm.mStackIdToStack.get(PINNED_STACK_ID);
        if (pinnedStack == null) {
            sDisplayContent.addStackToDisplay(PINNED_STACK_ID, true);
            pinnedStack = sWm.mStackIdToStack.get(PINNED_STACK_ID);
        }

        if (!pinnedStack.isVisible()) {
            // Stack should contain visible app window to be considered visible.
            final Task pinnedTask = createTaskInStack(pinnedStack, 0 /* userId */);
            assertFalse(pinnedStack.isVisible());
            final TestAppWindowToken pinnedApp = new TestAppWindowToken(sDisplayContent);
            pinnedTask.addChild(pinnedApp, 0 /* addPos */);
            assertTrue(pinnedStack.isVisible());
        }

        return pinnedStack;
    }
}
