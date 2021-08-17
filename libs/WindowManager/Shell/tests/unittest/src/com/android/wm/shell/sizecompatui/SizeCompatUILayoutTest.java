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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link SizeCompatUILayout}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:SizeCompatUILayoutTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SizeCompatUILayoutTest extends ShellTestCase {

    private static final int TASK_ID = 1;

    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private SizeCompatUIController.SizeCompatUICallback mCallback;
    @Mock private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock private DisplayLayout mDisplayLayout;
    @Mock private SizeCompatRestartButton mButton;
    @Mock private SizeCompatHintPopup mHint;
    private Configuration mTaskConfig;

    private SizeCompatUILayout mLayout;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTaskConfig = new Configuration();

        mLayout = new SizeCompatUILayout(mSyncTransactionQueue, mCallback, mContext,
                new Configuration(), TASK_ID, mTaskListener, mDisplayLayout,
                false /* hasShownHint */);

        spyOn(mLayout);
        spyOn(mLayout.mButtonWindowManager);
        doReturn(mButton).when(mLayout.mButtonWindowManager).createSizeCompatButton();

        final SizeCompatUIWindowManager hintWindowManager = mLayout.createHintWindowManager();
        spyOn(hintWindowManager);
        doReturn(mHint).when(hintWindowManager).createSizeCompatHint();
        doReturn(hintWindowManager).when(mLayout).createHintWindowManager();
    }

    @Test
    public void testCreateSizeCompatButton() {
        // Not create button if IME is showing.
        mLayout.createSizeCompatButton(true /* isImeShowing */);

        verify(mLayout.mButtonWindowManager, never()).createSizeCompatButton();
        assertNull(mLayout.mButton);
        assertNull(mLayout.mHintWindowManager);
        assertNull(mLayout.mHint);

        // Not create hint popup.
        mLayout.mShouldShowHint = false;
        mLayout.createSizeCompatButton(false /* isImeShowing */);

        verify(mLayout.mButtonWindowManager).createSizeCompatButton();
        assertNotNull(mLayout.mButton);
        assertNull(mLayout.mHintWindowManager);
        assertNull(mLayout.mHint);

        // Create hint popup.
        mLayout.release();
        mLayout.mShouldShowHint = true;
        mLayout.createSizeCompatButton(false /* isImeShowing */);

        verify(mLayout.mButtonWindowManager, times(2)).createSizeCompatButton();
        assertNotNull(mLayout.mButton);
        assertNotNull(mLayout.mHintWindowManager);
        verify(mLayout.mHintWindowManager).createSizeCompatHint();
        assertNotNull(mLayout.mHint);
        assertFalse(mLayout.mShouldShowHint);
    }

    @Test
    public void testRelease() {
        mLayout.createSizeCompatButton(false /* isImeShowing */);
        final SizeCompatUIWindowManager hintWindowManager = mLayout.mHintWindowManager;

        mLayout.release();

        assertNull(mLayout.mButton);
        assertNull(mLayout.mHint);
        verify(hintWindowManager).release();
        assertNull(mLayout.mHintWindowManager);
        verify(mLayout.mButtonWindowManager).release();
    }

    @Test
    public void testUpdateSizeCompatInfo() {
        mLayout.createSizeCompatButton(false /* isImeShowing */);

        // No diff
        clearInvocations(mLayout);
        mLayout.updateSizeCompatInfo(mTaskConfig, mTaskListener,
                false /* isImeShowing */);

        verify(mLayout, never()).updateButtonSurfacePosition();
        verify(mLayout, never()).release();
        verify(mLayout, never()).createSizeCompatButton(anyBoolean());

        // Change task listener, recreate button.
        clearInvocations(mLayout);
        final ShellTaskOrganizer.TaskListener newTaskListener = mock(
                ShellTaskOrganizer.TaskListener.class);
        mLayout.updateSizeCompatInfo(mTaskConfig, newTaskListener,
                false /* isImeShowing */);

        verify(mLayout).release();
        verify(mLayout).createSizeCompatButton(anyBoolean());

        // Change task bounds, update position.
        clearInvocations(mLayout);
        final Configuration newTaskConfiguration = new Configuration();
        newTaskConfiguration.windowConfiguration.setBounds(new Rect(0, 1000, 0, 2000));
        mLayout.updateSizeCompatInfo(newTaskConfiguration, newTaskListener,
                false /* isImeShowing */);

        verify(mLayout).updateButtonSurfacePosition();
        verify(mLayout).updateHintSurfacePosition();
    }

    @Test
    public void testUpdateDisplayLayout() {
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = 1000;
        displayInfo.logicalHeight = 2000;
        final DisplayLayout displayLayout1 = new DisplayLayout(displayInfo,
                mContext.getResources(), false, false);

        mLayout.updateDisplayLayout(displayLayout1);
        verify(mLayout).updateButtonSurfacePosition();
        verify(mLayout).updateHintSurfacePosition();

        // No update if the display bounds is the same.
        clearInvocations(mLayout);
        final DisplayLayout displayLayout2 = new DisplayLayout(displayInfo,
                mContext.getResources(), false, false);
        mLayout.updateDisplayLayout(displayLayout2);
        verify(mLayout, never()).updateButtonSurfacePosition();
        verify(mLayout, never()).updateHintSurfacePosition();
    }

    @Test
    public void testUpdateImeVisibility() {
        // Create button if it is not created.
        mLayout.mButton = null;
        mLayout.updateImeVisibility(false /* isImeShowing */);

        verify(mLayout).createSizeCompatButton(false /* isImeShowing */);

        // Hide button if ime is shown.
        clearInvocations(mLayout);
        doReturn(View.VISIBLE).when(mButton).getVisibility();
        mLayout.updateImeVisibility(true /* isImeShowing */);

        verify(mLayout, never()).createSizeCompatButton(anyBoolean());
        verify(mButton).setVisibility(View.GONE);

        // Show button if ime is not shown.
        doReturn(View.GONE).when(mButton).getVisibility();
        mLayout.updateImeVisibility(false /* isImeShowing */);

        verify(mLayout, never()).createSizeCompatButton(anyBoolean());
        verify(mButton).setVisibility(View.VISIBLE);
    }

    @Test
    public void testAttachToParentSurface() {
        final SurfaceControl.Builder b = new SurfaceControl.Builder();
        mLayout.attachToParentSurface(b);

        verify(mTaskListener).attachChildSurfaceToTask(TASK_ID, b);
    }

    @Test
    public void testOnRestartButtonClicked() {
        mLayout.onRestartButtonClicked();

        verify(mCallback).onSizeCompatRestartButtonClicked(TASK_ID);
    }

    @Test
    public void testOnRestartButtonLongClicked_showHint() {
        mLayout.dismissHint();

        assertNull(mLayout.mHint);

        mLayout.onRestartButtonLongClicked();

        assertNotNull(mLayout.mHint);
    }

    @Test
    public void testDismissHint() {
        mLayout.onRestartButtonLongClicked();
        final SizeCompatUIWindowManager hintWindowManager = mLayout.mHintWindowManager;
        assertNotNull(mLayout.mHint);
        assertNotNull(hintWindowManager);

        mLayout.dismissHint();

        assertNull(mLayout.mHint);
        assertNull(mLayout.mHintWindowManager);
        verify(hintWindowManager).release();
    }
}
