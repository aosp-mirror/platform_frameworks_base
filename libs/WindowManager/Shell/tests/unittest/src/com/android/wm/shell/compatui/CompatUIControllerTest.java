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

import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    private @Mock DisplayController mMockDisplayController;
    private @Mock DisplayInsetsController mMockDisplayInsetsController;
    private @Mock DisplayLayout mMockDisplayLayout;
    private @Mock DisplayImeController mMockImeController;
    private @Mock ShellTaskOrganizer.TaskListener mMockTaskListener;
    private @Mock SyncTransactionQueue mMockSyncQueue;
    private @Mock ShellExecutor mMockExecutor;
    private @Mock CompatUIWindowManager mMockLayout;

    @Captor
    ArgumentCaptor<OnInsetsChangedListener> mOnInsetsChangedListenerCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(mMockDisplayLayout).when(mMockDisplayController).getDisplayLayout(anyInt());
        doReturn(DISPLAY_ID).when(mMockLayout).getDisplayId();
        doReturn(TASK_ID).when(mMockLayout).getTaskId();
        mController = new CompatUIController(mContext, mMockDisplayController,
                mMockDisplayInsetsController, mMockImeController, mMockSyncQueue, mMockExecutor) {
            @Override
            CompatUIWindowManager createLayout(Context context, int displayId, int taskId,
                    Configuration taskConfig, ShellTaskOrganizer.TaskListener taskListener) {
                return mMockLayout;
            }
        };
        spyOn(mController);
    }

    @Test
    public void testListenerRegistered() {
        verify(mMockDisplayController).addDisplayWindowListener(mController);
        verify(mMockImeController).addPositionProcessor(mController);
    }

    @Test
    public void testOnCompatInfoChanged() {
        final Configuration taskConfig = new Configuration();

        // Verify that the restart button is added with non-null size compat info.
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig, mMockTaskListener);

        verify(mController).createLayout(any(), eq(DISPLAY_ID), eq(TASK_ID), eq(taskConfig),
                eq(mMockTaskListener));

        // Verify that the restart button is updated with non-null new size compat info.
        final Configuration newTaskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, newTaskConfig, mMockTaskListener);

        verify(mMockLayout).updateCompatInfo(taskConfig, mMockTaskListener,
                true /* show */);

        // Verify that the restart button is removed with null size compat info.
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, null, mMockTaskListener);

        verify(mMockLayout).release();
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
        final Configuration taskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig,
                mMockTaskListener);

        mController.onDisplayRemoved(DISPLAY_ID + 1);

        verify(mMockLayout, never()).release();
        verify(mMockDisplayInsetsController, never()).removeInsetsChangedListener(eq(DISPLAY_ID),
                any());

        mController.onDisplayRemoved(DISPLAY_ID);

        verify(mMockDisplayInsetsController).removeInsetsChangedListener(eq(DISPLAY_ID), any());
        verify(mMockLayout).release();
    }

    @Test
    public void testOnDisplayConfigurationChanged() {
        final Configuration taskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig,
                mMockTaskListener);

        final Configuration newTaskConfig = new Configuration();
        mController.onDisplayConfigurationChanged(DISPLAY_ID + 1, newTaskConfig);

        verify(mMockLayout, never()).updateDisplayLayout(any());

        mController.onDisplayConfigurationChanged(DISPLAY_ID, newTaskConfig);

        verify(mMockLayout).updateDisplayLayout(mMockDisplayLayout);
    }

    @Test
    public void testInsetsChanged() {
        mController.onDisplayAdded(DISPLAY_ID);
        final Configuration taskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig,
                mMockTaskListener);
        InsetsState insetsState = new InsetsState();
        InsetsSource insetsSource = new InsetsSource(ITYPE_EXTRA_NAVIGATION_BAR);
        insetsSource.setFrame(0, 0, 1000, 1000);
        insetsState.addSource(insetsSource);

        verify(mMockDisplayInsetsController).addInsetsChangedListener(eq(DISPLAY_ID),
                mOnInsetsChangedListenerCaptor.capture());
        mOnInsetsChangedListenerCaptor.getValue().insetsChanged(insetsState);

        verify(mMockLayout).updateDisplayLayout(mMockDisplayLayout);

        // No update if the insets state is the same.
        clearInvocations(mMockLayout);
        mOnInsetsChangedListenerCaptor.getValue().insetsChanged(new InsetsState(insetsState));
        verify(mMockLayout, never()).updateDisplayLayout(mMockDisplayLayout);
    }

    @Test
    public void testChangeButtonVisibilityOnImeShowHide() {
        final Configuration taskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig, mMockTaskListener);

        // Verify that the restart button is hidden after IME is showing.
        mController.onImeVisibilityChanged(DISPLAY_ID, true /* isShowing */);

        verify(mMockLayout).updateVisibility(false);

        // Verify button remains hidden while IME is showing.
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig, mMockTaskListener);

        verify(mMockLayout).updateCompatInfo(taskConfig, mMockTaskListener,
                false /* show */);

        // Verify button is shown after IME is hidden.
        mController.onImeVisibilityChanged(DISPLAY_ID, false /* isShowing */);

        verify(mMockLayout).updateVisibility(true);
    }

    @Test
    public void testChangeButtonVisibilityOnKeyguardOccludedChanged() {
        final Configuration taskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig, mMockTaskListener);

        // Verify that the restart button is hidden after keyguard becomes occluded.
        mController.onKeyguardOccludedChanged(true);

        verify(mMockLayout).updateVisibility(false);

        // Verify button remains hidden while keyguard is occluded.
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig, mMockTaskListener);

        verify(mMockLayout).updateCompatInfo(taskConfig, mMockTaskListener,
                false /* show */);

        // Verify button is shown after keyguard becomes not occluded.
        mController.onKeyguardOccludedChanged(false);

        verify(mMockLayout).updateVisibility(true);
    }

    @Test
    public void testButtonRemainsHiddenOnKeyguardOccludedFalseWhenImeIsShowing() {
        final Configuration taskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig, mMockTaskListener);

        mController.onImeVisibilityChanged(DISPLAY_ID, true /* isShowing */);
        mController.onKeyguardOccludedChanged(true);

        verify(mMockLayout, times(2)).updateVisibility(false);

        clearInvocations(mMockLayout);

        // Verify button remains hidden after keyguard becomes not occluded since IME is showing.
        mController.onKeyguardOccludedChanged(false);

        verify(mMockLayout).updateVisibility(false);

        // Verify button is shown after IME is not showing.
        mController.onImeVisibilityChanged(DISPLAY_ID, false /* isShowing */);

        verify(mMockLayout).updateVisibility(true);
    }

    @Test
    public void testButtonRemainsHiddenOnImeHideWhenKeyguardIsOccluded() {
        final Configuration taskConfig = new Configuration();
        mController.onCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig, mMockTaskListener);

        mController.onImeVisibilityChanged(DISPLAY_ID, true /* isShowing */);
        mController.onKeyguardOccludedChanged(true);

        verify(mMockLayout, times(2)).updateVisibility(false);

        clearInvocations(mMockLayout);

        // Verify button remains hidden after IME is hidden since keyguard is occluded.
        mController.onImeVisibilityChanged(DISPLAY_ID, false /* isShowing */);

        verify(mMockLayout).updateVisibility(false);

        // Verify button is shown after keyguard becomes not occluded.
        mController.onKeyguardOccludedChanged(false);

        verify(mMockLayout).updateVisibility(true);
    }
}
