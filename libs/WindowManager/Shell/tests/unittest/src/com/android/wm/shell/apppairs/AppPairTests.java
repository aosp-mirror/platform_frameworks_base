/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.apppairs;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.hardware.display.DisplayManager;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link AppPair}
 * Build/Install/Run:
 *  atest WMShellUnitTests:AppPairTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppPairTests extends ShellTestCase {

    private AppPairsController mController;
    @Mock private SyncTransactionQueue mSyncQueue;
    @Mock private ShellTaskOrganizer mTaskOrganizer;
    @Mock private DisplayController mDisplayController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDisplayController.getDisplayContext(anyInt())).thenReturn(mContext);
        when(mDisplayController.getDisplay(anyInt())).thenReturn(
                mContext.getSystemService(DisplayManager.class).getDisplay(DEFAULT_DISPLAY));
        mController = new TestAppPairsController(
                mTaskOrganizer,
                mSyncQueue,
                mDisplayController);
        spyOn(mController);
    }

    @After
    public void tearDown() {}

    @Test
    @UiThreadTest
    public void testContains() {
        final ActivityManager.RunningTaskInfo task1 = new TestRunningTaskInfoBuilder().build();
        final ActivityManager.RunningTaskInfo task2 = new TestRunningTaskInfoBuilder().build();

        final AppPair pair = mController.pairInner(task1, task2);
        assertThat(pair.contains(task1.taskId)).isTrue();
        assertThat(pair.contains(task2.taskId)).isTrue();

        pair.unpair();
        assertThat(pair.contains(task1.taskId)).isFalse();
        assertThat(pair.contains(task2.taskId)).isFalse();
    }

    @Test
    @UiThreadTest
    public void testVanishUnpairs() {
        final ActivityManager.RunningTaskInfo task1 = new TestRunningTaskInfoBuilder().build();
        final ActivityManager.RunningTaskInfo task2 = new TestRunningTaskInfoBuilder().build();

        final AppPair pair = mController.pairInner(task1, task2);
        assertThat(pair.contains(task1.taskId)).isTrue();
        assertThat(pair.contains(task2.taskId)).isTrue();

        pair.onTaskVanished(task1);
        assertThat(pair.contains(task1.taskId)).isFalse();
        assertThat(pair.contains(task2.taskId)).isFalse();
    }

    @Test
    @UiThreadTest
    public void testOnTaskInfoChanged_notSupportsMultiWindow() {
        final ActivityManager.RunningTaskInfo task1 = new TestRunningTaskInfoBuilder().build();
        final ActivityManager.RunningTaskInfo task2 = new TestRunningTaskInfoBuilder().build();

        final AppPair pair = mController.pairInner(task1, task2);
        assertThat(pair.contains(task1.taskId)).isTrue();
        assertThat(pair.contains(task2.taskId)).isTrue();

        task1.supportsMultiWindow = false;
        pair.onTaskInfoChanged(task1);
        verify(mController).unpair(pair.getRootTaskId());
    }
}
