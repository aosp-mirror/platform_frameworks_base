/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.tasksurfacehelper;

import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class TaskSurfaceHelperControllerTest {
    private TaskSurfaceHelperController mTaskSurfaceHelperController;
    @Mock
    private ShellTaskOrganizer mMockTaskOrganizer;
    @Mock
    private ShellExecutor mMockShellExecutor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTaskSurfaceHelperController = new TaskSurfaceHelperController(
                mMockTaskOrganizer, mMockShellExecutor);
    }

    @Test
    public void testSetGameModeForTask() {
        mTaskSurfaceHelperController.setGameModeForTask(/*taskId*/1, /*gameMode*/3);
        verify(mMockTaskOrganizer).setSurfaceMetadata(1, SurfaceControl.METADATA_GAME_MODE, 3);
    }
}
