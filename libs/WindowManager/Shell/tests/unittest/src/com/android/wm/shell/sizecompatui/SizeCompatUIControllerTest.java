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

package com.android.wm.shell.sizecompatui;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.res.Configuration;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link SizeCompatUIController}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:SizeCompatUIControllerTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SizeCompatUIControllerTest extends ShellTestCase {
    private static final int DISPLAY_ID = 0;
    private static final int TASK_ID = 12;

    private SizeCompatUIController mController;
    private @Mock DisplayController mMockDisplayController;
    private @Mock DisplayLayout mMockDisplayLayout;
    private @Mock DisplayImeController mMockImeController;
    private @Mock ShellTaskOrganizer.TaskListener mMockTaskListener;
    private @Mock SyncTransactionQueue mMockSyncQueue;
    private @Mock SizeCompatUILayout mMockLayout;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(mMockDisplayLayout).when(mMockDisplayController).getDisplayLayout(anyInt());
        doReturn(DISPLAY_ID).when(mMockLayout).getDisplayId();
        doReturn(TASK_ID).when(mMockLayout).getTaskId();
        mController = new SizeCompatUIController(mContext, mMockDisplayController,
                mMockImeController, mMockSyncQueue) {
            @Override
            SizeCompatUILayout createLayout(Context context, int displayId, int taskId,
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
    public void testOnSizeCompatInfoChanged() {
        final Configuration taskConfig = new Configuration();

        // Verify that the restart button is added with non-null size compat info.
        mController.onSizeCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig,
                mMockTaskListener);

        verify(mController).createLayout(any(), eq(DISPLAY_ID), eq(TASK_ID), eq(taskConfig),
                eq(mMockTaskListener));

        // Verify that the restart button is updated with non-null new size compat info.
        final Configuration newTaskConfig = new Configuration();
        mController.onSizeCompatInfoChanged(DISPLAY_ID, TASK_ID, newTaskConfig,
                mMockTaskListener);

        verify(mMockLayout).updateSizeCompatInfo(taskConfig, mMockTaskListener,
                false /* isImeShowing */);

        // Verify that the restart button is removed with null size compat info.
        mController.onSizeCompatInfoChanged(DISPLAY_ID, TASK_ID, null, mMockTaskListener);

        verify(mMockLayout).release();
    }

    @Test
    public void testOnDisplayRemoved() {
        final Configuration taskConfig = new Configuration();
        mController.onSizeCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig,
                mMockTaskListener);

        mController.onDisplayRemoved(DISPLAY_ID + 1);

        verify(mMockLayout, never()).release();

        mController.onDisplayRemoved(DISPLAY_ID);

        verify(mMockLayout).release();
    }

    @Test
    public void testOnDisplayConfigurationChanged() {
        final Configuration taskConfig = new Configuration();
        mController.onSizeCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig,
                mMockTaskListener);

        final Configuration newTaskConfig = new Configuration();
        mController.onDisplayConfigurationChanged(DISPLAY_ID + 1, newTaskConfig);

        verify(mMockLayout, never()).updateDisplayLayout(any());

        mController.onDisplayConfigurationChanged(DISPLAY_ID, newTaskConfig);

        verify(mMockLayout).updateDisplayLayout(mMockDisplayLayout);
    }

    @Test
    public void testChangeButtonVisibilityOnImeShowHide() {
        final Configuration taskConfig = new Configuration();
        mController.onSizeCompatInfoChanged(DISPLAY_ID, TASK_ID, taskConfig,
                mMockTaskListener);

        mController.onImeVisibilityChanged(DISPLAY_ID, true /* isShowing */);

        verify(mMockLayout).updateImeVisibility(true);

        mController.onImeVisibilityChanged(DISPLAY_ID, false /* isShowing */);

        verify(mMockLayout).updateImeVisibility(false);
    }
}
