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

import org.junit.Test;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@link TaskStack} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.TaskStackTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskStackTests extends WindowTestsBase {

    @Test
    public void testStackPositionChildAt() throws Exception {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task1 = createTaskInStack(stack, 0 /* userId */);
        final Task task2 = createTaskInStack(stack, 1 /* userId */);

        // Current user task should be moved to top.
        stack.positionChildAt(WindowContainer.POSITION_TOP, task1, false /* includingParents */);
        assertEquals(stack.mChildren.get(0), task2);
        assertEquals(stack.mChildren.get(1), task1);

        // Non-current user won't be moved to top.
        stack.positionChildAt(WindowContainer.POSITION_TOP, task2, false /* includingParents */);
        assertEquals(stack.mChildren.get(0), task2);
        assertEquals(stack.mChildren.get(1), task1);
    }

    @Test
    public void testClosingAppDifferentStackOrientation() throws Exception {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task1 = createTaskInStack(stack, 0 /* userId */);
        WindowTestUtils.TestAppWindowToken appWindowToken1 =
                WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        task1.addChild(appWindowToken1, 0);
        appWindowToken1.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final Task task2 = createTaskInStack(stack, 1 /* userId */);
        WindowTestUtils.TestAppWindowToken appWindowToken2 =
                WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        task2.addChild(appWindowToken2, 0);
        appWindowToken2.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, stack.getOrientation());
        sWm.mClosingApps.add(appWindowToken2);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, stack.getOrientation());
    }

    @Test
    public void testMoveTaskToBackDifferentStackOrientation() throws Exception {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task1 = createTaskInStack(stack, 0 /* userId */);
        WindowTestUtils.TestAppWindowToken appWindowToken1 =
                WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        task1.addChild(appWindowToken1, 0);
        appWindowToken1.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final Task task2 = createTaskInStack(stack, 1 /* userId */);
        WindowTestUtils.TestAppWindowToken appWindowToken2 =
                WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        task2.addChild(appWindowToken2, 0);
        appWindowToken2.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, stack.getOrientation());
        task2.setSendingToBottom(true);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, stack.getOrientation());
    }

    @Test
    public void testStackRemoveImmediately() throws Exception {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        assertEquals(stack, task.mStack);

        // Remove stack and check if its child is also removed.
        stack.removeImmediately();
        assertNull(stack.getDisplayContent());
        assertNull(task.mStack);
    }
}
