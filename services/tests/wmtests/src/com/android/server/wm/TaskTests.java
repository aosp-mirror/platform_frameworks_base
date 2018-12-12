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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Test class for {@link Task}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:TaskTests
 */
@SmallTest
@Presubmit
public class TaskTests extends WindowTestsBase {

    @Test
    public void testRemoveContainer() {
        final TaskStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final WindowTestUtils.TestTask task = WindowTestUtils.createTestTask(stackController1);
        final WindowTestUtils.TestAppWindowToken appToken =
                WindowTestUtils.createAppWindowTokenInTask(mDisplayContent, task);

        task.removeIfPossible();
        // Assert that the container was removed.
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(appToken.getParent());
    }

    @Test
    public void testRemoveContainer_deferRemoval() {
        final TaskStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final WindowTestUtils.TestTask task = WindowTestUtils.createTestTask(stackController1);
        final WindowTestUtils.TestAppWindowToken appToken =
                WindowTestUtils.createAppWindowTokenInTask(mDisplayContent, task);

        task.mShouldDeferRemoval = true;

        task.removeIfPossible();
        // For the case of deferred removal the task will still be connected to the its app token
        // until the task window container is removed.
        assertNotNull(task.getParent());
        assertNotEquals(0, task.getChildCount());
        assertNotNull(appToken.getParent());

        task.removeImmediately();
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(appToken.getParent());
    }

    @Test
    public void testReparent() {
        final TaskStack stackController1 = createTaskStackOnDisplay(mDisplayContent);
        final WindowTestUtils.TestTask task = WindowTestUtils.createTestTask(stackController1);
        final TaskStack stackController2 = createTaskStackOnDisplay(mDisplayContent);
        final WindowTestUtils.TestTask task2 = WindowTestUtils.createTestTask(stackController2);

        boolean gotException = false;
        try {
            task.reparent(stackController1, 0, false/* moveParents */);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to the same parent", gotException);

        gotException = false;
        try {
            task.reparent(null, 0, false/* moveParents */);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to a stack that doesn't exist",
                gotException);

        task.reparent(stackController2, 0, false/* moveParents */);
        assertEquals(stackController2, task.getParent());
        assertEquals(0, task.positionInParent());
        assertEquals(1, task2.positionInParent());
    }

    @Test
    public void testReparent_BetweenDisplays() {
        // Create first stack on primary display.
        final TaskStack stack1 = createTaskStackOnDisplay(mDisplayContent);
        final WindowTestUtils.TestTask task = WindowTestUtils.createTestTask(stack1);
        task.mOnDisplayChangedCalled = false;
        assertEquals(mDisplayContent, stack1.getDisplayContent());

        // Create second display and put second stack on it.
        final DisplayContent dc = createNewDisplay();
        final TaskStack stack2 = createTaskStackOnDisplay(dc);
        final WindowTestUtils.TestTask task2 = WindowTestUtils.createTestTask(stack2);
        // Reparent and check state
        task.reparent(stack2, 0, false /* moveParents */);
        assertEquals(stack2, task.getParent());
        assertEquals(0, task.positionInParent());
        assertEquals(1, task2.positionInParent());
        assertTrue(task.mOnDisplayChangedCalled);
    }
}
