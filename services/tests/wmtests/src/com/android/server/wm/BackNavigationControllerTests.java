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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.window.BackNavigationInfo.typeToString;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.hardware.HardwareBuffer;
import android.platform.test.annotations.Presubmit;
import android.window.BackNavigationInfo;
import android.window.TaskSnapshot;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(WindowTestRunner.class)
public class BackNavigationControllerTests extends WindowTestsBase {

    private BackNavigationController mBackNavigationController;

    @Before
    public void setUp() throws Exception {
        mBackNavigationController = new BackNavigationController();
    }

    @Test
    public void backTypeHomeWhenBackToLauncher() {
        Task task = createTopTaskWithActivity();
        BackNavigationInfo backNavigationInfo =
                mBackNavigationController.startBackNavigation(task, new StubTransaction());
        assertThat(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));
    }

    @Test
    public void backTypeCrossTaskWhenBackToPreviousTask() {
        Task taskA = createTask(mDefaultDisplay);
        createActivityRecord(taskA);
        Task task = createTopTaskWithActivity();
        BackNavigationInfo backNavigationInfo =
                mBackNavigationController.startBackNavigation(task, new StubTransaction());
        assertThat(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_TASK));
    }

    @Test
    public void backTypeCrossActivityWhenBackToPreviousActivity() {
        Task task = createTopTaskWithActivity();
        mAtm.setFocusedTask(task.mTaskId,
                createAppWindow(task, FIRST_APPLICATION_WINDOW, "window").mActivityRecord);
        BackNavigationInfo backNavigationInfo =
                mBackNavigationController.startBackNavigation(task, new StubTransaction());
        assertThat(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_ACTIVITY));
    }

    /**
     * Checks that we are able to fill all the field of the {@link BackNavigationInfo} object.
     */
    @Test
    public void backNavInfoFullyPopulated() {
        Task task = createTopTaskWithActivity();
        createAppWindow(task, FIRST_APPLICATION_WINDOW, "window");

        // We need a mock screenshot so
        TaskSnapshotController taskSnapshotController = createMockTaskSnapshotController();

        mBackNavigationController.setTaskSnapshotController(taskSnapshotController);

        BackNavigationInfo backNavigationInfo =
                mBackNavigationController.startBackNavigation(task, new StubTransaction());
        assertThat(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getDepartingWindowContainer()).isNotNull();
        assertThat(backNavigationInfo.getScreenshotSurface()).isNotNull();
        assertThat(backNavigationInfo.getScreenshotHardwareBuffer()).isNotNull();
        assertThat(backNavigationInfo.getTaskWindowConfiguration()).isNotNull();
    }

    @NonNull
    private TaskSnapshotController createMockTaskSnapshotController() {
        TaskSnapshotController taskSnapshotController = mock(TaskSnapshotController.class);
        TaskSnapshot taskSnapshot = mock(TaskSnapshot.class);
        when(taskSnapshot.getHardwareBuffer()).thenReturn(mock(HardwareBuffer.class));
        when(taskSnapshotController.getSnapshot(anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(taskSnapshot);
        return taskSnapshotController;
    }

    @NonNull
    private Task createTopTaskWithActivity() {
        Task task = createTask(mDefaultDisplay);
        ActivityRecord record = createActivityRecord(task);
        createWindow(null, FIRST_APPLICATION_WINDOW, record, "window");
        when(record.mSurfaceControl.isValid()).thenReturn(true);
        mAtm.setFocusedTask(task.mTaskId, record);
        return task;
    }
}
