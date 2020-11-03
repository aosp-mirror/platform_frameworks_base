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

import android.app.ActivityManager.StackInfo;
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

        StackInfo stackInfo1 = createTask(1, /* isVisible= */ true);
        stackInfo1.taskIds = new int[] { 11, 22, 33 };

        StackInfo stackInfo2 = createTask(2, /* isVisible= */ true);
        stackInfo2.taskIds = new int[] { 111, 222, 333, taskId };
        stackInfo2.displayId = displayId;

        List<StackInfo> stackInfoList = Arrays.asList(stackInfo1, stackInfo2);

        when(mActivityTaskManager.getAllStackInfos()).thenReturn(stackInfoList);
        when(mSideLoadedAppDetector.isSafe(stackInfo2)).thenReturn(true);

        mSideLoadedAppListener.onTaskCreated(taskId, componentName);

        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo1);
        verify(mSideLoadedAppDetector).isSafe(stackInfo2);

        verify(mSideLoadedAppStateController, never()).onUnsafeTaskCreatedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onSafeTaskDisplayedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(any());
    }

    @Test
    public void onTaskCreated_unsafeTask_callsUnsafeTaskCreated() throws Exception {
        int taskId = 999;
        int displayId = 123;
        ComponentName componentName = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        StackInfo stackInfo1 = createTask(1, /* isVisible= */ true);
        stackInfo1.taskIds = new int[] { 11, 22, 33 };
        StackInfo stackInfo2 = createTask(2, /* isVisible= */ true);
        stackInfo2.taskIds = new int[] { 111, 222, 333, taskId };
        stackInfo2.displayId = displayId;
        List<StackInfo> stackInfoList = Arrays.asList(stackInfo1, stackInfo2);

        Display display = createDisplay(displayId);

        when(mActivityTaskManager.getAllStackInfos()).thenReturn(stackInfoList);
        when(mSideLoadedAppDetector.isSafe(stackInfo2)).thenReturn(false);
        when(mDisplayManager.getDisplay(displayId)).thenReturn(display);

        mSideLoadedAppListener.onTaskCreated(taskId, componentName);

        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo1);
        verify(mSideLoadedAppDetector).isSafe(stackInfo2);

        verify(mSideLoadedAppStateController).onUnsafeTaskCreatedOnDisplay(display);
        verify(mSideLoadedAppStateController, never()).onSafeTaskDisplayedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(any());
    }

    @Test
    public void onTaskStackChanged_safeTask_callsSafeTaskDisplayed() throws Exception {
        Display display = createDisplay(123);
        StackInfo stackInfo1 = createTask(1, /* isVisible= */ false);
        StackInfo stackInfo2 = createTask(2, /* isVisible= */ true);
        StackInfo stackInfo3 = createTask(3, /* isVisible= */ true);
        List<StackInfo> stackInfoList = Arrays.asList(stackInfo1, stackInfo2, stackInfo3);

        when(mActivityTaskManager.getAllStackInfosOnDisplay(display.getDisplayId()))
                .thenReturn(stackInfoList);
        when(mSideLoadedAppDetector.isSafe(stackInfo2)).thenReturn(true);
        when(mDisplayManager.getDisplays()).thenReturn(new Display[] { display });

        mSideLoadedAppListener.onTaskStackChanged();

        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo1);
        verify(mSideLoadedAppDetector).isSafe(stackInfo2);
        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo3);

        verify(mSideLoadedAppStateController, never()).onUnsafeTaskCreatedOnDisplay(any());
        verify(mSideLoadedAppStateController).onSafeTaskDisplayedOnDisplay(display);
        verify(mSideLoadedAppStateController, never()).onUnsafeTaskDisplayedOnDisplay(any());
    }

    @Test
    public void onTaskStackChanged_unsafeTask_callsUnsafeTaskDisplayed() throws Exception {
        Display display = createDisplay(123);
        StackInfo stackInfo1 = createTask(1, /* isVisible= */ false);
        StackInfo stackInfo2 = createTask(2, /* isVisible= */ true);
        StackInfo stackInfo3 = createTask(3, /* isVisible= */ true);
        List<StackInfo> stackInfoList = Arrays.asList(stackInfo1, stackInfo2, stackInfo3);

        when(mActivityTaskManager.getAllStackInfosOnDisplay(display.getDisplayId()))
                .thenReturn(stackInfoList);
        when(mSideLoadedAppDetector.isSafe(stackInfo2)).thenReturn(false);
        when(mDisplayManager.getDisplays()).thenReturn(new Display[] { display });

        mSideLoadedAppListener.onTaskStackChanged();

        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo1);
        verify(mSideLoadedAppDetector).isSafe(stackInfo2);
        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo3);

        verify(mSideLoadedAppStateController, never()).onUnsafeTaskCreatedOnDisplay(any());
        verify(mSideLoadedAppStateController, never()).onSafeTaskDisplayedOnDisplay(any());
        verify(mSideLoadedAppStateController).onUnsafeTaskDisplayedOnDisplay(display);
    }

    @Test
    public void onTaskStackChanged_multiDisplay_callsTasksDisplayed() throws Exception {
        Display display1 = createDisplay(1);
        StackInfo stackInfo1 = createTask(1, /* isVisible= */ false);
        StackInfo stackInfo2 = createTask(2, /* isVisible= */ true);
        StackInfo stackInfo3 = createTask(3, /* isVisible= */ true);
        List<StackInfo> display1Stack = Arrays.asList(stackInfo1, stackInfo2, stackInfo3);

        Display display2 = createDisplay(2);
        StackInfo stackInfo4 = createTask(4, /* isVisible= */ true);
        List<StackInfo> display2Stack = Collections.singletonList(stackInfo4);

        Display display3 = createDisplay(3);
        StackInfo stackInfo5 = createTask(5, /* isVisible= */ true);
        List<StackInfo> display3Stack = Collections.singletonList(stackInfo5);

        when(mActivityTaskManager.getAllStackInfosOnDisplay(display1.getDisplayId()))
                .thenReturn(display1Stack);
        when(mActivityTaskManager.getAllStackInfosOnDisplay(display2.getDisplayId()))
                .thenReturn(display2Stack);
        when(mActivityTaskManager.getAllStackInfosOnDisplay(display3.getDisplayId()))
                .thenReturn(display3Stack);

        when(mSideLoadedAppDetector.isSafe(stackInfo2)).thenReturn(true);
        when(mSideLoadedAppDetector.isSafe(stackInfo4)).thenReturn(false);
        when(mSideLoadedAppDetector.isSafe(stackInfo5)).thenReturn(true);

        when(mDisplayManager.getDisplays())
                .thenReturn(new Display[] { display1, display2, display3});

        mSideLoadedAppListener.onTaskStackChanged();

        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo1);
        verify(mSideLoadedAppDetector).isSafe(stackInfo2);
        verify(mSideLoadedAppDetector, never()).isSafe(stackInfo3);
        verify(mSideLoadedAppDetector).isSafe(stackInfo4);
        verify(mSideLoadedAppDetector).isSafe(stackInfo5);

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

    private StackInfo createTask(int id, boolean isVisible) {
        StackInfo stackInfo = new StackInfo();
        stackInfo.stackId = id;
        stackInfo.visible = isVisible;
        return stackInfo;
    }
}
