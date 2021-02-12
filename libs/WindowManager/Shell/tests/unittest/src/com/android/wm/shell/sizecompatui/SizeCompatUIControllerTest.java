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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;

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

    private final TestShellExecutor mShellMainExecutor = new TestShellExecutor();

    private SizeCompatUIController mController;
    private @Mock DisplayController mMockDisplayController;
    private @Mock DisplayImeController mMockImeController;
    private @Mock SizeCompatRestartButton mMockButton;
    private @Mock IBinder mMockActivityToken;
    private @Mock ShellTaskOrganizer.TaskListener mMockTaskListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(true).when(mMockButton).show();

        mController = new SizeCompatUIController(mContext, mMockDisplayController,
                mMockImeController, mShellMainExecutor) {
            @Override
            SizeCompatRestartButton createRestartButton(Context context, int displayId) {
                return mMockButton;
            }
        };
    }

    @Test
    public void testListenerRegistered() {
        verify(mMockDisplayController).addDisplayWindowListener(mController);
        verify(mMockImeController).addPositionProcessor(mController);
    }

    @Test
    public void testOnSizeCompatInfoChanged() {
        final int taskId = 12;
        final Rect taskBounds = new Rect(0, 0, 1000, 2000);

        // Verify that the restart button is added with non-null size compat activity.
        mController.onSizeCompatInfoChanged(DISPLAY_ID, taskId, taskBounds,
                mMockActivityToken, mMockTaskListener);
        mShellMainExecutor.flushAll();

        verify(mMockButton).show();
        verify(mMockButton).updateLastTargetActivity(eq(mMockActivityToken));

        // Verify that the restart button is removed with null size compat activity.
        mController.onSizeCompatInfoChanged(DISPLAY_ID, taskId, null, null, null);

        mShellMainExecutor.flushAll();
        verify(mMockButton).remove();
    }

    @Test
    public void testChangeButtonVisibilityOnImeShowHide() {
        final int taskId = 12;
        final Rect taskBounds = new Rect(0, 0, 1000, 2000);
        mController.onSizeCompatInfoChanged(DISPLAY_ID, taskId, taskBounds,
                mMockActivityToken, mMockTaskListener);
        mShellMainExecutor.flushAll();

        // Verify that the restart button is hidden when IME is visible.
        doReturn(View.VISIBLE).when(mMockButton).getVisibility();
        mController.onImeVisibilityChanged(DISPLAY_ID, true /* isShowing */);

        verify(mMockButton).setVisibility(eq(View.GONE));

        // Verify that the restart button is visible when IME is hidden.
        doReturn(View.GONE).when(mMockButton).getVisibility();
        mController.onImeVisibilityChanged(DISPLAY_ID, false /* isShowing */);

        verify(mMockButton).setVisibility(eq(View.VISIBLE));
    }
}
