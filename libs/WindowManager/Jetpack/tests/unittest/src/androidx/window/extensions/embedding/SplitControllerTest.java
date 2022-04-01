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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.extensions.embedding.SplitController.TaskContainer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitControllerTest {
    private static final int TASK_ID = 10;

    private SplitController mSplitController;

    @Before
    public void setUp() {
        mSplitController = new SplitController();
        spyOn(mSplitController);
    }

    @Test
    public void testGetTopActiveContainer() {
        TaskContainer taskContainer = new TaskContainer();
        // tf3 is finished so is not active.
        TaskFragmentContainer tf3 = mock(TaskFragmentContainer.class);
        doReturn(true).when(tf3).isFinished();
        // tf2 has running activity so is active.
        TaskFragmentContainer tf2 = mock(TaskFragmentContainer.class);
        doReturn(1).when(tf2).getRunningActivityCount();
        // tf1 has no running activity so is not active.
        TaskFragmentContainer tf1 = new TaskFragmentContainer(null, TASK_ID);

        taskContainer.mContainers.add(tf3);
        taskContainer.mContainers.add(tf2);
        taskContainer.mContainers.add(tf1);
        mSplitController.mTaskContainers.put(TASK_ID, taskContainer);

        assertWithMessage("Must return tf2 because tf3 is not active.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf2);

        taskContainer.mContainers.remove(tf1);

        assertWithMessage("Must return tf2 because tf2 has running activity.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf2);

        taskContainer.mContainers.remove(tf2);

        assertWithMessage("Must return null because tf1 has no running activity.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isNull();
    }
}
