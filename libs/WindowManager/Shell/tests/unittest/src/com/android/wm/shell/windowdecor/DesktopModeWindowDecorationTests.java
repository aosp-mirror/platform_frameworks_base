/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.view.Choreographer;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.function.Supplier;

/**
 * Tests for {@link DesktopModeWindowDecoration}.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:DesktopModeWindowDecorationTests
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DesktopModeWindowDecorationTests extends ShellTestCase {
    @Mock
    private DisplayController mMockDisplayController;
    @Mock
    private ShellTaskOrganizer mMockShellTaskOrganizer;
    @Mock
    private Handler mMockHandler;
    @Mock
    private Choreographer mMockChoreographer;
    @Mock
    private SyncTransactionQueue mMockSyncQueue;
    @Mock
    private RootTaskDisplayAreaOrganizer mMockRootTaskDisplayAreaOrganizer;
    @Mock
    private Supplier<SurfaceControl.Transaction> mMockTransactionSupplier;
    @Mock
    private SurfaceControl.Transaction mMockTransaction;
    @Mock
    private SurfaceControl mMockSurfaceControl;
    @Mock
    private SurfaceControlViewHost mMockSurfaceControlViewHost;
    @Mock
    private WindowDecoration.SurfaceControlViewHostFactory mMockSurfaceControlViewHostFactory;

    private final Configuration mConfiguration = new Configuration();

    @Before
    public void setUp() {
        doReturn(mMockSurfaceControlViewHost).when(mMockSurfaceControlViewHostFactory).create(
                any(), any(), any());
        doReturn(mMockTransaction).when(mMockTransactionSupplier).get();
    }

    @Test
    public void testMenusClosedWhenTaskIsInvisible() {
        doReturn(mMockTransaction).when(mMockTransaction).hide(any());

        final ActivityManager.RunningTaskInfo taskInfo = createTaskInfo(false /* visible */);
        final DesktopModeWindowDecoration spyWindowDecor =
                spy(createWindowDecoration(taskInfo));

        spyWindowDecor.relayout(taskInfo);

        // Menus should close if open before the task being invisible causes relayout to return.
        verify(spyWindowDecor).closeHandleMenu();
        verify(spyWindowDecor).closeMaximizeMenu();

    }

    private DesktopModeWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo) {
        return new DesktopModeWindowDecoration(mContext, mMockDisplayController,
                mMockShellTaskOrganizer, taskInfo, mMockSurfaceControl, mConfiguration,
                mMockHandler, mMockChoreographer, mMockSyncQueue, mMockRootTaskDisplayAreaOrganizer,
                SurfaceControl.Builder::new, mMockTransactionSupplier,
                WindowContainerTransaction::new, SurfaceControl::new,
                mMockSurfaceControlViewHostFactory);
    }

    private ActivityManager.RunningTaskInfo createTaskInfo(boolean visible) {
        final ActivityManager.TaskDescription.Builder taskDescriptionBuilder =
                new ActivityManager.TaskDescription.Builder();
        ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setVisible(visible)
                .build();
        taskInfo.realActivity = new ComponentName("com.android.wm.shell.windowdecor",
                "DesktopModeWindowDecorationTests");
        taskInfo.baseActivity = new ComponentName("com.android.wm.shell.windowdecor",
                "DesktopModeWindowDecorationTests");
        return taskInfo;

    }
}
