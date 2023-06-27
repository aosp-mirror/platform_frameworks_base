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
package com.android.wm.shell.windowdecor;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputMonitor;
import android.view.SurfaceControl;
import android.view.SurfaceView;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.transition.Transitions;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Tests of {@link DesktopModeWindowDecorViewModel} */
@SmallTest
public class DesktopModeWindowDecorViewModelTests extends ShellTestCase {

    private static final String TAG = "DesktopModeWindowDecorViewModelTests";
    private static  final Rect STABLE_INSETS = new Rect(0, 100, 0, 0);

    @Mock private DesktopModeWindowDecoration mDesktopModeWindowDecoration;
    @Mock private DesktopModeWindowDecoration.Factory mDesktopModeWindowDecorFactory;

    @Mock private Handler mMainHandler;
    @Mock private Choreographer mMainChoreographer;
    @Mock private ShellTaskOrganizer mTaskOrganizer;
    @Mock private DisplayController mDisplayController;
    @Mock private DisplayLayout mDisplayLayout;
    @Mock private SyncTransactionQueue mSyncQueue;
    @Mock private DesktopModeController mDesktopModeController;
    @Mock private DesktopTasksController mDesktopTasksController;
    @Mock private InputMonitor mInputMonitor;
    @Mock private InputManager mInputManager;
    @Mock private Transitions mTransitions;
    @Mock private DesktopModeWindowDecorViewModel.InputMonitorFactory mMockInputMonitorFactory;
    @Mock private Supplier<SurfaceControl.Transaction> mTransactionFactory;
    @Mock private SurfaceControl.Transaction mTransaction;
    @Mock private Display mDisplay;
    private final List<InputManager> mMockInputManagers = new ArrayList<>();

    private DesktopModeWindowDecorViewModel mDesktopModeWindowDecorViewModel;

    @Before
    public void setUp() {
        mMockInputManagers.add(mInputManager);

        mDesktopModeWindowDecorViewModel =
            new DesktopModeWindowDecorViewModel(
                mContext,
                mMainHandler,
                mMainChoreographer,
                mTaskOrganizer,
                mDisplayController,
                mSyncQueue,
                mTransitions,
                Optional.of(mDesktopModeController),
                Optional.of(mDesktopTasksController),
                mDesktopModeWindowDecorFactory,
                mMockInputMonitorFactory,
                mTransactionFactory
            );

        doReturn(mDesktopModeWindowDecoration)
            .when(mDesktopModeWindowDecorFactory)
            .create(any(), any(), any(), any(), any(), any(), any(), any());
        doReturn(mTransaction).when(mTransactionFactory).get();
        doReturn(mDisplayLayout).when(mDisplayController).getDisplayLayout(anyInt());
        doReturn(STABLE_INSETS).when(mDisplayLayout).stableInsets();

        when(mMockInputMonitorFactory.create(any(), any())).thenReturn(mInputMonitor);
        // InputChannel cannot be mocked because it passes to InputEventReceiver.
        final InputChannel[] inputChannels = InputChannel.openInputChannelPair(TAG);
        inputChannels[0].dispose();
        when(mInputMonitor.getInputChannel()).thenReturn(inputChannels[1]);

        mDesktopModeWindowDecoration.mDisplay = mDisplay;
        doReturn(Display.DEFAULT_DISPLAY).when(mDisplay).getDisplayId();
    }

    @Test
    public void testDeleteCaptionOnChangeTransitionWhenNecessary() throws Exception {
        final int taskId = 1;
        final ActivityManager.RunningTaskInfo taskInfo =
                createTaskInfo(taskId, Display.DEFAULT_DISPLAY, WINDOWING_MODE_FREEFORM);
        SurfaceControl surfaceControl = mock(SurfaceControl.class);
        runOnMainThread(() -> {
            final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
            final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);

            mDesktopModeWindowDecorViewModel.onTaskOpening(
                    taskInfo, surfaceControl, startT, finishT);

            taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_UNDEFINED);
            taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);
            mDesktopModeWindowDecorViewModel.onTaskChanging(
                    taskInfo, surfaceControl, startT, finishT);
        });
        verify(mDesktopModeWindowDecorFactory)
                .create(
                        mContext,
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        surfaceControl,
                        mMainHandler,
                        mMainChoreographer,
                        mSyncQueue);
        verify(mDesktopModeWindowDecoration).close();
    }

    @Test
    public void testCreateCaptionOnChangeTransitionWhenNecessary() throws Exception {
        final int taskId = 1;
        final ActivityManager.RunningTaskInfo taskInfo =
                createTaskInfo(taskId, Display.DEFAULT_DISPLAY, WINDOWING_MODE_UNDEFINED);
        SurfaceControl surfaceControl = mock(SurfaceControl.class);
        runOnMainThread(() -> {
            final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
            final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
            taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);

            mDesktopModeWindowDecorViewModel.onTaskChanging(
                    taskInfo, surfaceControl, startT, finishT);

            taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
            taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);

            mDesktopModeWindowDecorViewModel.onTaskChanging(
                    taskInfo, surfaceControl, startT, finishT);
        });
        verify(mDesktopModeWindowDecorFactory, times(1))
                .create(
                        mContext,
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        surfaceControl,
                        mMainHandler,
                        mMainChoreographer,
                        mSyncQueue);
    }

    @Test
    public void testCreateAndDisposeEventReceiver() throws Exception {
        final int taskId = 1;
        final ActivityManager.RunningTaskInfo taskInfo =
                createTaskInfo(taskId, Display.DEFAULT_DISPLAY, WINDOWING_MODE_FREEFORM);
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);
        runOnMainThread(() -> {
            SurfaceControl surfaceControl = mock(SurfaceControl.class);
            final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
            final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);

            mDesktopModeWindowDecorViewModel.onTaskOpening(
                    taskInfo, surfaceControl, startT, finishT);

            mDesktopModeWindowDecorViewModel.destroyWindowDecoration(taskInfo);
        });
        verify(mMockInputMonitorFactory).create(any(), any());
        verify(mInputMonitor).dispose();
    }

    @Test
    public void testEventReceiversOnMultipleDisplays() throws Exception {
        runOnMainThread(() -> {
            SurfaceView surfaceView = new SurfaceView(mContext);
            final DisplayManager mDm = mContext.getSystemService(DisplayManager.class);
            final VirtualDisplay secondaryDisplay = mDm.createVirtualDisplay(
                    "testEventReceiversOnMultipleDisplays", /*width=*/ 400, /*height=*/ 400,
                    /*densityDpi=*/ 320, surfaceView.getHolder().getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            try {
                int secondaryDisplayId = secondaryDisplay.getDisplay().getDisplayId();

                final int taskId = 1;
                final ActivityManager.RunningTaskInfo taskInfo =
                        createTaskInfo(taskId, Display.DEFAULT_DISPLAY, WINDOWING_MODE_FREEFORM);
                final ActivityManager.RunningTaskInfo secondTaskInfo =
                        createTaskInfo(taskId + 1, secondaryDisplayId, WINDOWING_MODE_FREEFORM);
                final ActivityManager.RunningTaskInfo thirdTaskInfo =
                        createTaskInfo(taskId + 2, secondaryDisplayId, WINDOWING_MODE_FREEFORM);

                SurfaceControl surfaceControl = mock(SurfaceControl.class);
                final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
                final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);

                mDesktopModeWindowDecorViewModel.onTaskOpening(taskInfo, surfaceControl, startT,
                        finishT);
                mDesktopModeWindowDecorViewModel.onTaskOpening(secondTaskInfo, surfaceControl,
                        startT, finishT);
                mDesktopModeWindowDecorViewModel.onTaskOpening(thirdTaskInfo, surfaceControl,
                        startT, finishT);
                mDesktopModeWindowDecorViewModel.destroyWindowDecoration(thirdTaskInfo);
                mDesktopModeWindowDecorViewModel.destroyWindowDecoration(taskInfo);
            } finally {
                secondaryDisplay.release();
            }
        });
        verify(mMockInputMonitorFactory, times(2)).create(any(), any());
        verify(mInputMonitor, times(1)).dispose();
    }

    private void runOnMainThread(Runnable r) throws Exception {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            r.run();
            latch.countDown();
        });
        latch.await(1, TimeUnit.SECONDS);
    }

    private static ActivityManager.RunningTaskInfo createTaskInfo(int taskId,
            int displayId, @WindowConfiguration.WindowingMode int windowingMode) {
        ActivityManager.RunningTaskInfo taskInfo =
                 new TestRunningTaskInfoBuilder()
                .setDisplayId(displayId)
                .setVisible(true)
                .build();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);
        return taskInfo;
    }
}
