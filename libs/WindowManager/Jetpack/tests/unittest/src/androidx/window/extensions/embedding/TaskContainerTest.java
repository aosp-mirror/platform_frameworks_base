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

package androidx.window.extensions.embedding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link TaskContainer}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:TaskContainerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TaskContainerTest {
    private static final int TASK_ID = 10;
    private static final Rect TASK_BOUNDS = new Rect(0, 0, 600, 1200);

    @Test
    public void testIsTaskBoundsInitialized() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);

        assertFalse(taskContainer.isTaskBoundsInitialized());

        taskContainer.setTaskBounds(TASK_BOUNDS);

        assertTrue(taskContainer.isTaskBoundsInitialized());
    }

    @Test
    public void testSetTaskBounds() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);

        assertFalse(taskContainer.setTaskBounds(new Rect()));

        assertTrue(taskContainer.setTaskBounds(TASK_BOUNDS));

        assertFalse(taskContainer.setTaskBounds(TASK_BOUNDS));
    }

    @Test
    public void testIsEmpty() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);

        assertTrue(taskContainer.isEmpty());

        final TaskFragmentContainer tf = new TaskFragmentContainer(null, TASK_ID);
        taskContainer.mContainers.add(tf);

        assertFalse(taskContainer.isEmpty());

        taskContainer.mFinishedContainer.add(tf.getTaskFragmentToken());
        taskContainer.mContainers.clear();

        assertFalse(taskContainer.isEmpty());
    }
}
