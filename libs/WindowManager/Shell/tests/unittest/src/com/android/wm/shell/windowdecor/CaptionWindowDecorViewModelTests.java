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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputMonitor;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.rule.GrantPermissionRule;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Tests of {@link CaptionWindowDecorViewModel} */
@SmallTest
public class CaptionWindowDecorViewModelTests extends ShellTestCase {
    @Mock private CaptionWindowDecoration mCaptionWindowDecoration;

    @Mock private CaptionWindowDecoration.Factory mCaptionWindowDecorFactory;

    @Mock private Handler mMainHandler;

    @Mock private Choreographer mMainChoreographer;

    @Mock private ShellTaskOrganizer mTaskOrganizer;

    @Mock private DisplayController mDisplayController;

    @Mock private SyncTransactionQueue mSyncQueue;

    @Mock private DesktopModeController mDesktopModeController;

    @Mock private InputMonitor mInputMonitor;

    @Mock private InputChannel mInputChannel;

    @Mock private CaptionWindowDecorViewModel.EventReceiverFactory mEventReceiverFactory;

    @Mock private CaptionWindowDecorViewModel.EventReceiver mEventReceiver;

    @Mock private InputManager mInputManager;

    private final List<InputManager> mMockInputManagers = new ArrayList<>();

    private CaptionWindowDecorViewModel mCaptionWindowDecorViewModel;

    @Before
    public void setUp() {
        mMockInputManagers.add(mInputManager);

        mCaptionWindowDecorViewModel =
            new CaptionWindowDecorViewModel(
                mContext,
                mMainHandler,
                mMainChoreographer,
                mTaskOrganizer,
                mDisplayController,
                mSyncQueue,
                Optional.of(mDesktopModeController),
                mCaptionWindowDecorFactory,
                new MockObjectSupplier<>(mMockInputManagers, () -> mock(InputManager.class)));
        mCaptionWindowDecorViewModel.setEventReceiverFactory(mEventReceiverFactory);

        doReturn(mCaptionWindowDecoration)
            .when(mCaptionWindowDecorFactory)
            .create(any(), any(), any(), any(), any(), any(), any(), any());

        when(mInputManager.monitorGestureInput(any(), anyInt())).thenReturn(mInputMonitor);
        when(mEventReceiverFactory.create(any(), any(), any())).thenReturn(mEventReceiver);
        when(mInputMonitor.getInputChannel()).thenReturn(mInputChannel);
    }

    @Test
    public void testDeleteCaptionOnChangeTransitionWhenNecessary() throws Exception {
        Looper.prepare();
        final int taskId = 1;
        final ActivityManager.RunningTaskInfo taskInfo =
                createTaskInfo(taskId, WINDOWING_MODE_FREEFORM);
        SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        GrantPermissionRule.grant(android.Manifest.permission.MONITOR_INPUT);

        mCaptionWindowDecorViewModel.onTaskOpening(taskInfo, surfaceControl, startT, finishT);
        verify(mCaptionWindowDecorFactory)
                .create(
                    mContext,
                    mDisplayController,
                    mTaskOrganizer,
                    taskInfo,
                    surfaceControl,
                    mMainHandler,
                    mMainChoreographer,
                    mSyncQueue);

        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);
        mCaptionWindowDecorViewModel.onTaskChanging(taskInfo, surfaceControl, startT, finishT);
        verify(mCaptionWindowDecoration).close();
    }

    @Test
    public void testCreateCaptionOnChangeTransitionWhenNecessary() throws Exception {
        final int taskId = 1;
        final ActivityManager.RunningTaskInfo taskInfo =
                createTaskInfo(taskId, WINDOWING_MODE_UNDEFINED);
        SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);

        mCaptionWindowDecorViewModel.onTaskChanging(taskInfo, surfaceControl, startT, finishT);

        verify(mCaptionWindowDecorFactory, never())
                .create(
                    mContext,
                    mDisplayController,
                    mTaskOrganizer,
                    taskInfo,
                    surfaceControl,
                    mMainHandler,
                    mMainChoreographer,
                    mSyncQueue);

        taskInfo.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);

        mCaptionWindowDecorViewModel.onTaskChanging(taskInfo, surfaceControl, startT, finishT);

        verify(mCaptionWindowDecorFactory)
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

    private static ActivityManager.RunningTaskInfo createTaskInfo(int taskId, int windowingMode) {
        ActivityManager.RunningTaskInfo taskInfo =
                 new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setVisible(true)
                .build();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);
        return taskInfo;
    }

    private static class MockObjectSupplier<T> implements Supplier<T> {
        private final List<T> mObjects;
        private final Supplier<T> mDefaultSupplier;
        private int mNumOfCalls = 0;

        private MockObjectSupplier(List<T> objects, Supplier<T> defaultSupplier) {
            mObjects = objects;
            mDefaultSupplier = defaultSupplier;
        }

        @Override
        public T get() {
            final T mock =
                    mNumOfCalls < mObjects.size() ? mObjects.get(mNumOfCalls)
                        : mDefaultSupplier.get();
            ++mNumOfCalls;
            return mock;
        }
    }
}
