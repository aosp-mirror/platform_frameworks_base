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

import static androidx.window.extensions.embedding.EmbeddingTestUtils.TASK_ID;
import static androidx.window.extensions.embedding.EmbeddingTestUtils.createTestTaskContainer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

/**
 * Test class for {@link TaskContainer}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:TaskContainerTest
 */

// Suppress GuardedBy warning on unit tests
@SuppressWarnings("GuardedBy")
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TaskContainerTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private SplitController mController;

    @Test
    public void testGetWindowingModeForSplitTaskFragment() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final Rect splitBounds = new Rect(0, 0, 500, 1000);
        final Configuration configuration = new Configuration();

        assertEquals(WINDOWING_MODE_MULTI_WINDOW,
                taskContainer.getWindowingModeForTaskFragment(splitBounds));

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, TASK_ID, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */));

        assertEquals(WINDOWING_MODE_MULTI_WINDOW,
                taskContainer.getWindowingModeForTaskFragment(splitBounds));

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, TASK_ID, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */));

        assertEquals(WINDOWING_MODE_FREEFORM,
                taskContainer.getWindowingModeForTaskFragment(splitBounds));

        // Empty bounds means the split pair are stacked, so it should be UNDEFINED which will then
        // inherit the Task windowing mode
        assertEquals(WINDOWING_MODE_UNDEFINED,
                taskContainer.getWindowingModeForTaskFragment(new Rect()));
    }

    @Test
    public void testIsInPictureInPicture() {
        final TaskContainer taskContainer = createTestTaskContainer();
        final Configuration configuration = new Configuration();

        assertFalse(taskContainer.isInPictureInPicture());

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, TASK_ID, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */));

        assertFalse(taskContainer.isInPictureInPicture());

        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_PINNED);
        taskContainer.updateTaskFragmentParentInfo(new TaskFragmentParentInfo(configuration,
                DEFAULT_DISPLAY, TASK_ID, true /* visible */, false /* hasDirectActivity */,
                null /* decorSurface */));

        assertTrue(taskContainer.isInPictureInPicture());
    }

    @Test
    public void testIsEmpty() {
        final TaskContainer taskContainer = createTestTaskContainer();

        assertTrue(taskContainer.isEmpty());

        doReturn(taskContainer).when(mController).getTaskContainer(anyInt());
        final TaskFragmentContainer tf = new TaskFragmentContainer.Builder(mController,
                taskContainer.getTaskId(), null /* activityInTask */)
                .setPendingAppearedIntent(new Intent())
                .build();

        assertFalse(taskContainer.isEmpty());

        taskContainer.mFinishedContainer.add(tf.getTaskFragmentToken());
        taskContainer.clearTaskFragmentContainer();

        assertFalse(taskContainer.isEmpty());
    }

    @Test
    public void testGetTopTaskFragmentContainer() {
        final TaskContainer taskContainer = createTestTaskContainer();
        assertNull(taskContainer.getTopNonFinishingTaskFragmentContainer());

        doReturn(taskContainer).when(mController).getTaskContainer(anyInt());
        final TaskFragmentContainer tf0 = new TaskFragmentContainer.Builder(mController,
                taskContainer.getTaskId(), null /* activityInTask */)
                        .setPendingAppearedIntent(new Intent())
                        .build();
        assertEquals(tf0, taskContainer.getTopNonFinishingTaskFragmentContainer());

        final TaskFragmentContainer tf1 = new TaskFragmentContainer.Builder(mController,
                taskContainer.getTaskId(), null /* activityInTask */)
                        .setPendingAppearedIntent(new Intent())
                        .build();
        assertEquals(tf1, taskContainer.getTopNonFinishingTaskFragmentContainer());
    }

    @Test
    public void testGetTopNonFinishingActivity() {
        final TaskContainer taskContainer = createTestTaskContainer();
        assertNull(taskContainer.getTopNonFinishingActivity(true /* includeOverlay */));

        final TaskFragmentContainer tf0 = mock(TaskFragmentContainer.class);
        taskContainer.addTaskFragmentContainer(tf0);
        final Activity activity0 = mock(Activity.class);
        doReturn(activity0).when(tf0).getTopNonFinishingActivity();
        assertEquals(activity0, taskContainer.getTopNonFinishingActivity(
                true /* includeOverlay */));

        final TaskFragmentContainer tf1 = mock(TaskFragmentContainer.class);
        taskContainer.addTaskFragmentContainer(tf1);
        assertEquals(activity0, taskContainer.getTopNonFinishingActivity(
                true /* includeOverlay */));

        final Activity activity1 = mock(Activity.class);
        doReturn(activity1).when(tf1).getTopNonFinishingActivity();
        assertEquals(activity1, taskContainer.getTopNonFinishingActivity(
                true /* includeOverlay */));
    }

    @Test
    public void testGetSplitStatesIfStable() {
        final TaskContainer taskContainer = createTestTaskContainer();

        final SplitContainer splitContainer0 = mock(SplitContainer.class);
        final SplitContainer splitContainer1 = mock(SplitContainer.class);
        final SplitInfo splitInfo0 = mock(SplitInfo.class);
        final SplitInfo splitInfo1 = mock(SplitInfo.class);
        taskContainer.addSplitContainer(splitContainer0);
        taskContainer.addSplitContainer(splitContainer1);

        // When all the SplitContainers are stable, getSplitStatesIfStable() returns the list of
        // SplitInfo representing the SplitContainers.
        doReturn(splitInfo0).when(splitContainer0).toSplitInfoIfStable();
        doReturn(splitInfo1).when(splitContainer1).toSplitInfoIfStable();

        List<SplitInfo> splitInfoList = taskContainer.getSplitStatesIfStable();

        assertEquals(2, splitInfoList.size());
        assertEquals(splitInfo0, splitInfoList.get(0));
        assertEquals(splitInfo1, splitInfoList.get(1));

        // When any SplitContainer is in an intermediate state, getSplitStatesIfStable() returns
        // null.
        doReturn(null).when(splitContainer0).toSplitInfoIfStable();

        splitInfoList = taskContainer.getSplitStatesIfStable();

        assertNull(splitInfoList);
    }
}
