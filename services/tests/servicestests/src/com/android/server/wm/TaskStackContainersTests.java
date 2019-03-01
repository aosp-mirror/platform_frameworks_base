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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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

    private TaskStack mPinnedStack;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mPinnedStack = createStackControllerOnStackOnDisplay(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, mDisplayContent).mContainer;
        // Stack should contain visible app window to be considered visible.
        final Task pinnedTask = createTaskInStack(mPinnedStack, 0 /* userId */);
        assertFalse(mPinnedStack.isVisible());
        final WindowTestUtils.TestAppWindowToken pinnedApp =
                WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        pinnedTask.addChild(pinnedApp, 0 /* addPos */);
        assertTrue(mPinnedStack.isVisible());
    }

    @After
    public void tearDown() throws Exception {
        mPinnedStack.removeImmediately();
    }

    @Test
    public void testStackPositionChildAt() throws Exception {
        // Test that always-on-top stack can't be moved to position other than top.
        final TaskStack stack1 = createTaskStackOnDisplay(mDisplayContent);
        final TaskStack stack2 = createTaskStackOnDisplay(mDisplayContent);

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stack1Pos = taskStackContainer.mChildren.indexOf(stack1);
        final int stack2Pos = taskStackContainer.mChildren.indexOf(stack2);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(mPinnedStack);
        assertGreaterThan(pinnedStackPos, stack2Pos);
        assertGreaterThan(stack2Pos, stack1Pos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_BOTTOM, mPinnedStack, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);

        taskStackContainer.positionChildAt(1, mPinnedStack, false);
        assertEquals(taskStackContainer.mChildren.get(stack1Pos), stack1);
        assertEquals(taskStackContainer.mChildren.get(stack2Pos), stack2);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);
    }

    @Test
    public void testStackPositionBelowPinnedStack() throws Exception {
        // Test that no stack can be above pinned stack.
        final TaskStack stack1 = createTaskStackOnDisplay(mDisplayContent);

        final WindowContainer taskStackContainer = stack1.getParent();

        final int stackPos = taskStackContainer.mChildren.indexOf(stack1);
        final int pinnedStackPos = taskStackContainer.mChildren.indexOf(mPinnedStack);
        assertGreaterThan(pinnedStackPos, stackPos);

        taskStackContainer.positionChildAt(WindowContainer.POSITION_TOP, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);

        taskStackContainer.positionChildAt(taskStackContainer.mChildren.size() - 1, stack1, false);
        assertEquals(taskStackContainer.mChildren.get(stackPos), stack1);
        assertEquals(taskStackContainer.mChildren.get(pinnedStackPos), mPinnedStack);
    }
}
