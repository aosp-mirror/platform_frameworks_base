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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.extensions.embedding.EmbeddingTestUtils.createTestTaskContainer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentParentInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    @Mock
    private SplitController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetWindowingModeForSplitTaskFragment() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final Rect splitBounds = new Rect(0, 0, 500, 1000);
        final Configuration configuration = new Configuration();

        assertEquals(WINDOWING_MODE_MULTI_WINDOW,
                taskContainer.getWindowingModeForSplitTaskFragment(splitBounds));

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, true /* visible */));

        assertEquals(WINDOWING_MODE_MULTI_WINDOW,
                taskContainer.getWindowingModeForSplitTaskFragment(splitBounds));

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, true /* visible */));

        assertEquals(WINDOWING_MODE_FREEFORM,
                taskContainer.getWindowingModeForSplitTaskFragment(splitBounds));

        // Empty bounds means the split pair are stacked, so it should be UNDEFINED which will then
        // inherit the Task windowing mode
        assertEquals(WINDOWING_MODE_UNDEFINED,
                taskContainer.getWindowingModeForSplitTaskFragment(new Rect()));
    }

    @Test
    public void testIsInPictureInPicture() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final Configuration configuration = new Configuration();

        assertFalse(taskContainer.isInPictureInPicture());

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, true /* visible */));

        assertFalse(taskContainer.isInPictureInPicture());

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_PINNED);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, true /* visible */));

        assertTrue(taskContainer.isInPictureInPicture());
    }

    @Test
    public void testIsEmpty() {
        final TaskContainer taskContainer = createTestTaskContainer();

        assertTrue(taskContainer.isEmpty());

        final TaskFragmentContainer tf = new TaskFragmentContainer(null /* activity */,
                new Intent(), taskContainer, mController, null /* pairedPrimaryContainer */);

        assertFalse(taskContainer.isEmpty());

        taskContainer.mFinishedContainer.add(tf.getTaskFragmentToken());
        taskContainer.mContainers.clear();

        assertFalse(taskContainer.isEmpty());
    }

    @Test
    public void testGetTopTaskFragmentContainer() {
        final TaskContainer taskContainer = createTestTaskContainer();
        assertNull(taskContainer.getTopTaskFragmentContainer());

        final TaskFragmentContainer tf0 = new TaskFragmentContainer(null /* activity */,
                new Intent(), taskContainer, mController, null /* pairedPrimaryContainer */);
        assertEquals(tf0, taskContainer.getTopTaskFragmentContainer());

        final TaskFragmentContainer tf1 = new TaskFragmentContainer(null /* activity */,
                new Intent(), taskContainer, mController, null /* pairedPrimaryContainer */);
        assertEquals(tf1, taskContainer.getTopTaskFragmentContainer());
    }

    @Test
    public void testGetTopNonFinishingActivity() {
        final TaskContainer taskContainer = createTestTaskContainer();
        assertNull(taskContainer.getTopNonFinishingActivity());

        final TaskFragmentContainer tf0 = mock(TaskFragmentContainer.class);
        taskContainer.mContainers.add(tf0);
        final Activity activity0 = mock(Activity.class);
        doReturn(activity0).when(tf0).getTopNonFinishingActivity();
        assertEquals(activity0, taskContainer.getTopNonFinishingActivity());

        final TaskFragmentContainer tf1 = mock(TaskFragmentContainer.class);
        taskContainer.mContainers.add(tf1);
        assertEquals(activity0, taskContainer.getTopNonFinishingActivity());

        final Activity activity1 = mock(Activity.class);
        doReturn(activity1).when(tf1).getTopNonFinishingActivity();
        assertEquals(activity1, taskContainer.getTopNonFinishingActivity());
    }
}
