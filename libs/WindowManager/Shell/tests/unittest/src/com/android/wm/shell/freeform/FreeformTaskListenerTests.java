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

package com.android.wm.shell.freeform;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.platform.test.annotations.EnableFlags;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.LaunchAdjacentController;
import com.android.wm.shell.desktopmode.DesktopRepository;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.Optional;

/**
 * Tests for {@link FreeformTaskListener}
 * Build/Install/Run:
 * atest WMShellUnitTests:FreeformTaskListenerTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class FreeformTaskListenerTests extends ShellTestCase {

    @Mock
    private ShellTaskOrganizer mTaskOrganizer;
    @Mock
    private ShellInit mShellInit;
    @Mock
    private WindowDecorViewModel mWindowDecorViewModel;
    @Mock
    private SurfaceControl mMockSurfaceControl;
    @Mock
    private DesktopRepository mDesktopRepository;
    @Mock
    private LaunchAdjacentController mLaunchAdjacentController;
    private FreeformTaskListener mFreeformTaskListener;
    private StaticMockitoSession mMockitoSession;

    @Before
    public void setup() {
        mMockitoSession = mockitoSession().initMocks(this)
                .strictness(Strictness.LENIENT).mockStatic(DesktopModeStatus.class).startMocking();
        doReturn(true).when(() -> DesktopModeStatus.canEnterDesktopMode(any()));

        mFreeformTaskListener = new FreeformTaskListener(
                mContext,
                mShellInit,
                mTaskOrganizer,
                Optional.of(mDesktopRepository),
                mLaunchAdjacentController,
                mWindowDecorViewModel);
    }

    @Test
    public void testFocusTaskChanged_freeformTaskIsAddedToRepo() {
        ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        task.isFocused = true;

        mFreeformTaskListener.onFocusTaskChanged(task);

        verify(mDesktopRepository)
            .addOrMoveFreeformTaskToTop(task.displayId, task.taskId);
    }

    @Test
    public void testFocusTaskChanged_fullscreenTaskIsNotAddedToRepo() {
        ActivityManager.RunningTaskInfo fullscreenTask = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).build();
        fullscreenTask.isFocused = true;

        mFreeformTaskListener.onFocusTaskChanged(fullscreenTask);

        verify(mDesktopRepository, never())
                .addOrMoveFreeformTaskToTop(fullscreenTask.displayId, fullscreenTask.taskId);
    }

    @Test
    public void testVisibilityTaskChanged_visible_setLaunchAdjacentDisabled() {
        ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        task.isVisible = true;

        mFreeformTaskListener.onTaskAppeared(task, mMockSurfaceControl);

        verify(mLaunchAdjacentController).setLaunchAdjacentEnabled(false);
    }

    @Test
    public void testVisibilityTaskChanged_NotVisible_setLaunchAdjacentEnabled() {
        ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        task.isVisible = true;

        mFreeformTaskListener.onTaskAppeared(task, mMockSurfaceControl);

        task.isVisible = false;
        mFreeformTaskListener.onTaskInfoChanged(task);

        verify(mLaunchAdjacentController).setLaunchAdjacentEnabled(true);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    public void onTaskVanished_nonClosingTask_isMinimized() {
        ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        task.isVisible = true;

        mFreeformTaskListener.onTaskAppeared(task, mMockSurfaceControl);

        task.isVisible = false;
        task.displayId = INVALID_DISPLAY;
        mFreeformTaskListener.onTaskVanished(task);

        verify(mDesktopRepository).minimizeTask(task.displayId, task.taskId);
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    public void onTaskVanished_closingTask_isNotMinimized() {
        ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        task.isVisible = true;

        mFreeformTaskListener.onTaskAppeared(task, mMockSurfaceControl);

        when(mDesktopRepository.isClosingTask(task.taskId)).thenReturn(true);
        task.isVisible = false;
        task.displayId = INVALID_DISPLAY;
        mFreeformTaskListener.onTaskVanished(task);

        verify(mDesktopRepository, never()).minimizeTask(task.displayId, task.taskId);
        verify(mDesktopRepository).removeFreeformTask(task.displayId, task.taskId);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }
}
