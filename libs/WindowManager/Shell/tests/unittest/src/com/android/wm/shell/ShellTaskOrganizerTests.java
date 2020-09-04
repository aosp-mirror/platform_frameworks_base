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

package com.android.wm.shell;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager.RunningTaskInfo;
import android.os.RemoteException;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.ITaskOrganizerController;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Tests for the shell task organizer.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ShellTaskOrganizerTests {

    @Mock
    private ITaskOrganizerController mTaskOrganizerController;

    ShellTaskOrganizer mOrganizer;

    private class TrackingTaskListener implements ShellTaskOrganizer.TaskListener {
        final ArrayList<RunningTaskInfo> appeared = new ArrayList<>();
        final ArrayList<RunningTaskInfo> vanished = new ArrayList<>();
        final ArrayList<RunningTaskInfo> infoChanged = new ArrayList<>();

        @Override
        public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
            appeared.add(taskInfo);
        }

        @Override
        public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
            infoChanged.add(taskInfo);
        }

        @Override
        public void onTaskVanished(RunningTaskInfo taskInfo) {
            vanished.add(taskInfo);
        }

        @Override
        public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            // Not currently used
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mOrganizer = new ShellTaskOrganizer(mTaskOrganizerController);
    }

    @Test
    public void registerOrganizer_sendRegisterTaskOrganizer() throws RemoteException {
        mOrganizer.registerOrganizer();

        verify(mTaskOrganizerController).registerTaskOrganizer(any(ITaskOrganizer.class));
    }

    @Test
    public void testAppearedVanished() {
        RunningTaskInfo taskInfo = createTaskInfo(WINDOWING_MODE_MULTI_WINDOW);
        TrackingTaskListener listener = new TrackingTaskListener();
        mOrganizer.addListener(listener, WINDOWING_MODE_MULTI_WINDOW);
        mOrganizer.onTaskAppeared(taskInfo, null);
        assertTrue(listener.appeared.contains(taskInfo));

        mOrganizer.onTaskVanished(taskInfo);
        assertTrue(listener.vanished.contains(taskInfo));
    }

    @Test
    public void testAddListenerExistingTasks() {
        RunningTaskInfo taskInfo = createTaskInfo(WINDOWING_MODE_MULTI_WINDOW);
        mOrganizer.onTaskAppeared(taskInfo, null);

        TrackingTaskListener listener = new TrackingTaskListener();
        mOrganizer.addListener(listener, WINDOWING_MODE_MULTI_WINDOW);
        assertTrue(listener.appeared.contains(taskInfo));
    }

    @Test
    public void testWindowingModeChange() {
        RunningTaskInfo taskInfo = createTaskInfo(WINDOWING_MODE_MULTI_WINDOW);
        TrackingTaskListener mwListener = new TrackingTaskListener();
        TrackingTaskListener pipListener = new TrackingTaskListener();
        mOrganizer.addListener(mwListener, WINDOWING_MODE_MULTI_WINDOW);
        mOrganizer.addListener(pipListener, WINDOWING_MODE_PINNED);
        mOrganizer.onTaskAppeared(taskInfo, null);
        assertTrue(mwListener.appeared.contains(taskInfo));
        assertTrue(pipListener.appeared.isEmpty());

        taskInfo = createTaskInfo(WINDOWING_MODE_PINNED);
        mOrganizer.onTaskInfoChanged(taskInfo);
        assertTrue(mwListener.vanished.contains(taskInfo));
        assertTrue(pipListener.appeared.contains(taskInfo));
    }

    private RunningTaskInfo createTaskInfo(int windowingMode) {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);
        return taskInfo;
    }
}
