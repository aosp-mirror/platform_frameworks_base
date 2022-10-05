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

package com.android.wm.shell.compatui;

import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_HIDDEN;
import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.TaskInfo;
import android.app.TaskInfo.CameraCompatControlState;
import android.content.Context;
import android.content.res.Configuration;
import android.testing.AndroidTestingRunner;
import android.view.InsetsSource;
import android.view.InsetsState;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayInsetsController.OnInsetsChangedListener;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.DockStateReader;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.compatui.letterboxedu.LetterboxEduWindowManager;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import dagger.Lazy;

/**
 * Tests for {@link CompatUIController}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:CompatUIControllerTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class CompatUIControllerTest extends ShellTestCase {
    private static final int DISPLAY_ID = 0;
    private static final int TASK_ID = 12;

    private CompatUIController mController;
    private ShellInit mShellInit;
    private @Mock ShellController mMockShellController;
    private @Mock DisplayController mMockDisplayController;
    private @Mock DisplayInsetsController mMockDisplayInsetsController;
    private @Mock DisplayLayout mMockDisplayLayout;
    private @Mock DisplayImeController mMockImeController;
    private @Mock ShellTaskOrganizer.TaskListener mMockTaskListener;
    private @Mock SyncTransactionQueue mMockSyncQueue;
    private @Mock ShellExecutor mMockExecutor;
    private @Mock Lazy<Transitions> mMockTransitionsLazy;
    private @Mock CompatUIWindowManager mMockCompatLayout;
    private @Mock LetterboxEduWindowManager mMockLetterboxEduLayout;
    private @Mock DockStateReader mDockStateReader;

    @Captor
    ArgumentCaptor<OnInsetsChangedListener> mOnInsetsChangedListenerCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(mMockDisplayLayout).when(mMockDisplayController).getDisplayLayout(anyInt());
        doReturn(DISPLAY_ID).when(mMockCompatLayout).getDisplayId();
        doReturn(TASK_ID).when(mMockCompatLayout).getTaskId();
        doReturn(true).when(mMockCompatLayout).createLayout(anyBoolean());
        doReturn(true).when(mMockCompatLayout).updateCompatInfo(any(), any(), anyBoolean());
        doReturn(DISPLAY_ID).when(mMockLetterboxEduLayout).getDisplayId();
        doReturn(TASK_ID).when(mMockLetterboxEduLayout).getTaskId();
        doReturn(true).when(mMockLetterboxEduLayout).createLayout(anyBoolean());
        doReturn(true).when(mMockLetterboxEduLayout).updateCompatInfo(any(), any(), anyBoolean());
        mShellInit = spy(new ShellInit(mMockExecutor));
        mController = new CompatUIController(mContext, mShellInit, mMockShellController,
                mMockDisplayController, mMockDisplayInsetsController, mMockImeController,
                mMockSyncQueue, mMockExecutor, mMockTransitionsLazy, mDockStateReader) {
            @Override
            CompatUIWindowManager createCompatUiWindowManager(Context context, TaskInfo taskInfo,
                    ShellTaskOrganizer.TaskListener taskListener) {
                return mMockCompatLayout;
            }

            @Override
            LetterboxEduWindowManager createLetterboxEduWindowManager(Context context,
                    TaskInfo taskInfo, ShellTaskOrganizer.TaskListener taskListener) {
                return mMockLetterboxEduLayout;
            }
        };
        mShellInit.init();
        spyOn(mController);
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void instantiateController_registerKeyguardChangeListener() {
        verify(mMockShellController, times(1)).addKeyguardChangeListener(any());
    }

    @Test
    public void testListenerRegistered() {
        verify(mMockDisplayController).addDisplayWindowListener(mController);
        verify(mMockImeController).addPositionProcessor(mController);
    }

    @Test
    public void testOnCompatInfoChanged() {
        TaskInfo taskInfo = createTaskInfo(DISPLAY_ID, TASK_ID, /* hasSizeCompat= */ true,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        // Verify that the compat controls are added with non-null task listener.
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mController).createCompatUiWindowManager(any(), eq(taskInfo), eq(mMockTaskListener));
        verify(mController).createLetterboxEduWindowManager(any(), eq(taskInfo),
                eq(mMockTaskListener));

        // Verify that the compat controls and letterbox education are updated with new size compat
        // info.
        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout, mController);
        taskInfo = createTaskInfo(DISPLAY_ID, TASK_ID, /* hasSizeCompat= */ true,
                CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mMockCompatLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                true);
        verify(mMockLetterboxEduLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                true);

        // Verify that compat controls and letterbox education are removed with null task listener.
        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout, mController);
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN),
                /* taskListener= */ null);

        verify(mMockCompatLayout).release();
        verify(mMockLetterboxEduLayout).release();
    }

    @Test
    public void testOnCompatInfoChanged_createLayoutReturnsFalse() {
        doReturn(false).when(mMockCompatLayout).createLayout(anyBoolean());
        doReturn(false).when(mMockLetterboxEduLayout).createLayout(anyBoolean());

        TaskInfo taskInfo = createTaskInfo(DISPLAY_ID, TASK_ID, /* hasSizeCompat= */ true,
                CAMERA_COMPAT_CONTROL_HIDDEN);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mController).createCompatUiWindowManager(any(), eq(taskInfo), eq(mMockTaskListener));
        verify(mController).createLetterboxEduWindowManager(any(), eq(taskInfo),
                eq(mMockTaskListener));

        // Verify that the layout is created again.
        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout, mController);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mMockCompatLayout, never()).updateCompatInfo(any(), any(), anyBoolean());
        verify(mMockLetterboxEduLayout, never()).updateCompatInfo(any(), any(), anyBoolean());
        verify(mController).createCompatUiWindowManager(any(), eq(taskInfo), eq(mMockTaskListener));
        verify(mController).createLetterboxEduWindowManager(any(), eq(taskInfo),
                eq(mMockTaskListener));
    }

    @Test
    public void testOnCompatInfoChanged_updateCompatInfoReturnsFalse() {
        doReturn(false).when(mMockCompatLayout).updateCompatInfo(any(), any(), anyBoolean());
        doReturn(false).when(mMockLetterboxEduLayout).updateCompatInfo(any(), any(), anyBoolean());

        TaskInfo taskInfo = createTaskInfo(DISPLAY_ID, TASK_ID, /* hasSizeCompat= */ true,
                CAMERA_COMPAT_CONTROL_HIDDEN);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mController).createCompatUiWindowManager(any(), eq(taskInfo), eq(mMockTaskListener));
        verify(mController).createLetterboxEduWindowManager(any(), eq(taskInfo),
                eq(mMockTaskListener));

        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout, mController);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mMockCompatLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                true);
        verify(mMockLetterboxEduLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                true);

        // Verify that the layout is created again.
        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout, mController);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mMockCompatLayout, never()).updateCompatInfo(any(), any(), anyBoolean());
        verify(mMockLetterboxEduLayout, never()).updateCompatInfo(any(), any(), anyBoolean());
        verify(mController).createCompatUiWindowManager(any(), eq(taskInfo), eq(mMockTaskListener));
        verify(mController).createLetterboxEduWindowManager(any(), eq(taskInfo),
                eq(mMockTaskListener));
    }


    @Test
    public void testOnDisplayAdded() {
        mController.onDisplayAdded(DISPLAY_ID);
        mController.onDisplayAdded(DISPLAY_ID + 1);

        verify(mMockDisplayInsetsController).addInsetsChangedListener(eq(DISPLAY_ID), any());
        verify(mMockDisplayInsetsController).addInsetsChangedListener(eq(DISPLAY_ID + 1), any());
    }

    @Test
    public void testOnDisplayRemoved() {
        mController.onDisplayAdded(DISPLAY_ID);
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN),
                mMockTaskListener);

        mController.onDisplayRemoved(DISPLAY_ID + 1);

        verify(mMockCompatLayout, never()).release();
        verify(mMockLetterboxEduLayout, never()).release();
        verify(mMockDisplayInsetsController, never()).removeInsetsChangedListener(eq(DISPLAY_ID),
                any());

        mController.onDisplayRemoved(DISPLAY_ID);

        verify(mMockDisplayInsetsController).removeInsetsChangedListener(eq(DISPLAY_ID), any());
        verify(mMockCompatLayout).release();
        verify(mMockLetterboxEduLayout).release();
    }

    @Test
    public void testOnDisplayConfigurationChanged() {
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN), mMockTaskListener);

        mController.onDisplayConfigurationChanged(DISPLAY_ID + 1, new Configuration());

        verify(mMockCompatLayout, never()).updateDisplayLayout(any());
        verify(mMockLetterboxEduLayout, never()).updateDisplayLayout(any());

        mController.onDisplayConfigurationChanged(DISPLAY_ID, new Configuration());

        verify(mMockCompatLayout).updateDisplayLayout(mMockDisplayLayout);
        verify(mMockLetterboxEduLayout).updateDisplayLayout(mMockDisplayLayout);
    }

    @Test
    public void testInsetsChanged() {
        mController.onDisplayAdded(DISPLAY_ID);
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN), mMockTaskListener);
        InsetsState insetsState = new InsetsState();
        InsetsSource insetsSource = new InsetsSource(ITYPE_EXTRA_NAVIGATION_BAR);
        insetsSource.setFrame(0, 0, 1000, 1000);
        insetsState.addSource(insetsSource);

        verify(mMockDisplayInsetsController).addInsetsChangedListener(eq(DISPLAY_ID),
                mOnInsetsChangedListenerCaptor.capture());
        mOnInsetsChangedListenerCaptor.getValue().insetsChanged(insetsState);

        verify(mMockCompatLayout).updateDisplayLayout(mMockDisplayLayout);
        verify(mMockLetterboxEduLayout).updateDisplayLayout(mMockDisplayLayout);

        // No update if the insets state is the same.
        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout);
        mOnInsetsChangedListenerCaptor.getValue().insetsChanged(new InsetsState(insetsState));
        verify(mMockCompatLayout, never()).updateDisplayLayout(mMockDisplayLayout);
        verify(mMockLetterboxEduLayout, never()).updateDisplayLayout(mMockDisplayLayout);
    }

    @Test
    public void testChangeLayoutsVisibilityOnImeShowHide() {
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN), mMockTaskListener);

        // Verify that the restart button is hidden after IME is showing.
        mController.onImeVisibilityChanged(DISPLAY_ID, /* isShowing= */ true);

        verify(mMockCompatLayout).updateVisibility(false);
        verify(mMockLetterboxEduLayout).updateVisibility(false);

        // Verify button remains hidden while IME is showing.
        TaskInfo taskInfo = createTaskInfo(DISPLAY_ID, TASK_ID, /* hasSizeCompat= */ true,
                CAMERA_COMPAT_CONTROL_HIDDEN);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mMockCompatLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                false);
        verify(mMockLetterboxEduLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                false);

        // Verify button is shown after IME is hidden.
        mController.onImeVisibilityChanged(DISPLAY_ID, /* isShowing= */ false);

        verify(mMockCompatLayout).updateVisibility(true);
        verify(mMockLetterboxEduLayout).updateVisibility(true);
    }

    @Test
    public void testChangeLayoutsVisibilityOnKeyguardShowingChanged() {
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN), mMockTaskListener);

        // Verify that the restart button is hidden after keyguard becomes showing.
        mController.onKeyguardVisibilityChanged(true, false, false);

        verify(mMockCompatLayout).updateVisibility(false);
        verify(mMockLetterboxEduLayout).updateVisibility(false);

        // Verify button remains hidden while keyguard is showing.
        TaskInfo taskInfo = createTaskInfo(DISPLAY_ID, TASK_ID, /* hasSizeCompat= */ true,
                CAMERA_COMPAT_CONTROL_HIDDEN);
        mController.onCompatInfoChanged(taskInfo, mMockTaskListener);

        verify(mMockCompatLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                false);
        verify(mMockLetterboxEduLayout).updateCompatInfo(taskInfo, mMockTaskListener, /* canShow= */
                false);

        // Verify button is shown after keyguard becomes not showing.
        mController.onKeyguardVisibilityChanged(false, false, false);

        verify(mMockCompatLayout).updateVisibility(true);
        verify(mMockLetterboxEduLayout).updateVisibility(true);
    }

    @Test
    public void testLayoutsRemainHiddenOnKeyguardShowingFalseWhenImeIsShowing() {
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN), mMockTaskListener);

        mController.onImeVisibilityChanged(DISPLAY_ID, /* isShowing= */ true);
        mController.onKeyguardVisibilityChanged(true, false, false);

        verify(mMockCompatLayout, times(2)).updateVisibility(false);
        verify(mMockLetterboxEduLayout, times(2)).updateVisibility(false);

        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout);

        // Verify button remains hidden after keyguard becomes not showing since IME is showing.
        mController.onKeyguardVisibilityChanged(false, false, false);

        verify(mMockCompatLayout).updateVisibility(false);
        verify(mMockLetterboxEduLayout).updateVisibility(false);

        // Verify button is shown after IME is not showing.
        mController.onImeVisibilityChanged(DISPLAY_ID, /* isShowing= */ false);

        verify(mMockCompatLayout).updateVisibility(true);
        verify(mMockLetterboxEduLayout).updateVisibility(true);
    }

    @Test
    public void testLayoutsRemainHiddenOnImeHideWhenKeyguardIsShowing() {
        mController.onCompatInfoChanged(createTaskInfo(DISPLAY_ID, TASK_ID,
                /* hasSizeCompat= */ true, CAMERA_COMPAT_CONTROL_HIDDEN), mMockTaskListener);

        mController.onImeVisibilityChanged(DISPLAY_ID, /* isShowing= */ true);
        mController.onKeyguardVisibilityChanged(true, false, false);

        verify(mMockCompatLayout, times(2)).updateVisibility(false);
        verify(mMockLetterboxEduLayout, times(2)).updateVisibility(false);

        clearInvocations(mMockCompatLayout, mMockLetterboxEduLayout);

        // Verify button remains hidden after IME is hidden since keyguard is showing.
        mController.onImeVisibilityChanged(DISPLAY_ID, /* isShowing= */ false);

        verify(mMockCompatLayout).updateVisibility(false);
        verify(mMockLetterboxEduLayout).updateVisibility(false);

        // Verify button is shown after keyguard becomes not showing.
        mController.onKeyguardVisibilityChanged(false, false, false);

        verify(mMockCompatLayout).updateVisibility(true);
        verify(mMockLetterboxEduLayout).updateVisibility(true);
    }

    private static TaskInfo createTaskInfo(int displayId, int taskId, boolean hasSizeCompat,
            @CameraCompatControlState int cameraCompatControlState) {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.displayId = displayId;
        taskInfo.topActivityInSizeCompat = hasSizeCompat;
        taskInfo.cameraCompatControlState = cameraCompatControlState;
        return taskInfo;
    }
}
