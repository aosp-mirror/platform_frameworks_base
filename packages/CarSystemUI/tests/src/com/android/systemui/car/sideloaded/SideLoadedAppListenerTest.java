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

package com.android.systemui.car.sideloaded;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SideLoadedAppListenerTest extends SysuiTestCase {

    private static final String APP_PACKAGE_NAME = "com.test";
    private static final String APP_CLASS_NAME = ".TestClass";

    private SideLoadedAppListener mSideLoadedAppListener;

    @Mock
    private SideLoadedAppDetector mSideLoadedAppDetector;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private IActivityTaskManager mActivityTaskManager;
    @Mock
    private SideLoadedAppStateController mSideLoadedAppStateController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mSideLoadedAppListener = new SideLoadedAppListener(mSideLoadedAppDetector,
                mActivityTaskManager, mDisplayManager, mSideLoadedAppStateController);
    }

    @Test
    public void onTaskCreated_safeTask_callsNoMethods() throws Exception {
        int taskId = 999;
        int displayId = 123;
        ComponentName componentName = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        RootTaskInfo taskInfo1 = createTask(1, /* isVisible= */ true);
        taskInfo1.childTaskIds = new int[] { 11, 22, 33 };

        RootTaskInfo taskInfo2 = createTask(2, /* isVisible= */ true);
        taskInfo2.childTaskIds = new int[] { 111, 222, 333, taskId };
        taskInfo2.displayId = displayId;

        List<RootTaskInfo> taskInfoList = Arrays.asList(taskInfo1, taskInfo2);

        when(mActivityTaskManager.getAllRootTaskInfos()).thenReturn(taskInfoList);
        when(mSideLoadedAppDetector.isSafe(taskInfo2)).thenReturn(true);

        mSideLoadedAppListener.onTaskCreated(taskId, componentName);

        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo1);
        verify(mSideLoadedAppDetector).isSafe(taskInfo2);

        verify(mSideLoadedAppStateController, never()).onUnsafeTaskCreatedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onSafeTaskDisplayedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(any());
    }

    @Test
    public void onTaskCreated_unsafeTask_callsUnsafeTaskCreated() throws Exception {
        int taskId = 999;
        int displayId = 123;
        ComponentName componentName = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        RootTaskInfo taskInfo1 = createTask(1, /* isVisible= */ true);
        taskInfo1.childTaskIds = new int[] { 11, 22, 33 };
        RootTaskInfo taskInfo2 = createTask(2, /* isVisible= */ true);
        taskInfo2.childTaskIds = new int[] { 111, 222, 333, taskId };
        taskInfo2.displayId = displayId;
        List<RootTaskInfo> taskInfoList = Arrays.asList(taskInfo1, taskInfo2);

        Display display = createDisplay(displayId);

        when(mActivityTaskManager.getAllRootTaskInfos()).thenReturn(taskInfoList);
        when(mSideLoadedAppDetector.isSafe(taskInfo2)).thenReturn(false);
        when(mDisplayManager.getDisplay(displayId)).thenReturn(display);

        mSideLoadedAppListener.onTaskCreated(taskId, componentName);

        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo1);
        verify(mSideLoadedAppDetector).isSafe(taskInfo2);

        verify(mSideLoadedAppStateController).onUnsafeTaskCreatedOnDisplay(display);
        verify(mSideLoadedAppStateController, never()).onSafeTaskDisplayedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(any());
    }

    @Test
    public void onTaskStackChanged_safeTask_callsSafeTaskDisplayed() throws Exception {
        Display display = createDisplay(123);
        RootTaskInfo taskInfo1 = createTask(1, /* isVisible= */ false);
        RootTaskInfo taskInfo2 = createTask(2, /* isVisible= */ true);
        RootTaskInfo taskInfo3 = createTask(3, /* isVisible= */ true);
        List<RootTaskInfo> taskInfoList = Arrays.asList(taskInfo1, taskInfo2, taskInfo3);

        when(mActivityTaskManager.getAllRootTaskInfosOnDisplay(display.getDisplayId()))
                .thenReturn(taskInfoList);
        when(mSideLoadedAppDetector.isSafe(taskInfo2)).thenReturn(true);
        when(mDisplayManager.getDisplays()).thenReturn(new Display[] { display });

        mSideLoadedAppListener.onTaskStackChanged();

        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo1);
        verify(mSideLoadedAppDetector).isSafe(taskInfo2);
        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo3);

        verify(mSideLoadedAppStateController, never()).onUnsafeTaskCreatedOnDisplay(any());
        verify(mSideLoadedAppStateController).onSafeTaskDisplayedOnDisplay(display);
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(any());
    }

    @Test
    public void onTaskStackChanged_unsafeTask_callsUnsafeTaskDisplayed() throws Exception {
        Display display = createDisplay(123);
        RootTaskInfo taskInfo1 = createTask(1, /* isVisible= */ false);
        RootTaskInfo taskInfo2 = createTask(2, /* isVisible= */ true);
        RootTaskInfo taskInfo3 = createTask(3, /* isVisible= */ true);
        List<RootTaskInfo> taskInfoList = Arrays.asList(taskInfo1, taskInfo2, taskInfo3);

        when(mActivityTaskManager.getAllRootTaskInfosOnDisplay(display.getDisplayId()))
                .thenReturn(taskInfoList);
        when(mSideLoadedAppDetector.isSafe(taskInfo2)).thenReturn(false);
        when(mDisplayManager.getDisplays()).thenReturn(new Display[] { display });

        mSideLoadedAppListener.onTaskStackChanged();

        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo1);
        verify(mSideLoadedAppDetector).isSafe(taskInfo2);
        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo3);

        verify(mSideLoadedAppStateController, never()).onUnsafeTaskCreatedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onSafeTaskDisplayedOnDisplay(any());
        verify(mSideLoadedAppStateController).onUnsafeTaskDisplayedOnDisplay(display);
    }

    @Test
    public void onTaskStackChanged_multiDisplay_callsTasksDisplayed() throws Exception {
        Display display1 = createDisplay(1);
        RootTaskInfo taskInfo1 = createTask(1, /* isVisible= */ false);
        RootTaskInfo taskInfo2 = createTask(2, /* isVisible= */ true);
        RootTaskInfo taskInfo3 = createTask(3, /* isVisible= */ true);
        List<RootTaskInfo> display1Tasks = Arrays.asList(taskInfo1, taskInfo2, taskInfo3);

        Display display2 = createDisplay(2);
        RootTaskInfo taskInfo4 = createTask(4, /* isVisible= */ true);
        List<RootTaskInfo> display2Tasks = Collections.singletonList(taskInfo4);

        Display display3 = createDisplay(3);
        RootTaskInfo taskInfo5 = createTask(5, /* isVisible= */ true);
        List<RootTaskInfo> display3Tasks = Collections.singletonList(taskInfo5);

        when(mActivityTaskManager.getAllRootTaskInfosOnDisplay(display1.getDisplayId()))
                .thenReturn(display1Tasks);
        when(mActivityTaskManager.getAllRootTaskInfosOnDisplay(display2.getDisplayId()))
                .thenReturn(display2Tasks);
        when(mActivityTaskManager.getAllRootTaskInfosOnDisplay(display3.getDisplayId()))
                .thenReturn(display3Tasks);

        when(mSideLoadedAppDetector.isSafe(taskInfo2)).thenReturn(true);
        when(mSideLoadedAppDetector.isSafe(taskInfo4)).thenReturn(false);
        when(mSideLoadedAppDetector.isSafe(taskInfo5)).thenReturn(true);

        when(mDisplayManager.getDisplays())
                .thenReturn(new Display[] { display1, display2, display3});

        mSideLoadedAppListener.onTaskStackChanged();

        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo1);
        verify(mSideLoadedAppDetector).isSafe(taskInfo2);
        verify(mSideLoadedAppDetector, never()).isSafe(taskInfo3);
        verify(mSideLoadedAppDetector).isSafe(taskInfo4);
        verify(mSideLoadedAppDetector).isSafe(taskInfo5);

        verify(mSideLoadedAppStateController, never()).onUnsafeTaskCreatedOnDisplay(any());
        verify(mSideLoadedAppStateController).onSafeTaskDisplayedOnDisplay(display1);
        verify(mSideLoadedAppStateController).onUnsafeTaskDisplayedOnDisplay(display2);
        verify(mSideLoadedAppStateController).onSafeTaskDisplayedOnDisplay(display3);
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(display1);
        verify(mSideLoadedAppStateController).onUnsafeTaskDisplayedOnDisplay(display2);
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(display3);
    }

    private Display createDisplay(int id) {
        return new Display(DisplayManagerGlobal.getInstance(),
                id,
                new DisplayInfo(),
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    private RootTaskInfo createTask(int id, boolean isVisible) {
        RootTaskInfo taskInfo = new RootTaskInfo();
        taskInfo.taskId = id;
        taskInfo.visible = isVisible;
        return taskInfo;
    }
}
