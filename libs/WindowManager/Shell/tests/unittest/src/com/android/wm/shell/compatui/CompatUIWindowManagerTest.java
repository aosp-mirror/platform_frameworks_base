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

import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_DISMISSED;
import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_HIDDEN;
import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED;
import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
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
 * Tests for {@link CompatUIWindowManager}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:CompatUIWindowManagerTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class CompatUIWindowManagerTest extends ShellTestCase {

    private static final int TASK_ID = 1;

    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private CompatUIController.CompatUICallback mCallback;
    @Mock private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock private CompatUILayout mCompatUILayout;
    @Mock private SurfaceControlViewHost mViewHost;
    private Configuration mTaskConfig;

    private CompatUIWindowManager mWindowManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTaskConfig = new Configuration();

        mWindowManager = new CompatUIWindowManager(mContext, new Configuration(),
                mSyncTransactionQueue, mCallback, TASK_ID, mTaskListener, new DisplayLayout(),
                false /* hasShownSizeCompatHint */, false /* hasShownSizeCompatHint */);

        spyOn(mWindowManager);
        doReturn(mCompatUILayout).when(mWindowManager).inflateCompatUILayout();
        doReturn(mViewHost).when(mWindowManager).createSurfaceViewHost();
    }

    @Test
    public void testCreateSizeCompatButton() {
        // Not create layout if show is false.
        mWindowManager.createLayout(false /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager, never()).inflateCompatUILayout();

        // Not create hint popup.
        mWindowManager.mShouldShowSizeCompatHint = false;
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager).inflateCompatUILayout();
        verify(mCompatUILayout, never()).setSizeCompatHintVisibility(true /* show */);

        // Create hint popup.
        mWindowManager.release();
        mWindowManager.mShouldShowSizeCompatHint = true;
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager, times(2)).inflateCompatUILayout();
        assertNotNull(mCompatUILayout);
        verify(mCompatUILayout).setSizeCompatHintVisibility(true /* show */);
        assertFalse(mWindowManager.mShouldShowSizeCompatHint);
    }

    @Test
    public void testRelease() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager).inflateCompatUILayout();

        mWindowManager.release();

        verify(mViewHost).release();
    }

    @Test
    public void testUpdateCompatInfo() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        // No diff
        clearInvocations(mWindowManager);
        mWindowManager.updateCompatInfo(mTaskConfig, mTaskListener, true /* show */,
                true /* hasSizeCompat */, CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager, never()).updateSurfacePosition();
        verify(mWindowManager, never()).release();
        verify(mWindowManager, never()).createLayout(anyBoolean(), anyBoolean(), anyInt());

        // Change task listener, recreate button.
        clearInvocations(mWindowManager);
        final ShellTaskOrganizer.TaskListener newTaskListener = mock(
                ShellTaskOrganizer.TaskListener.class);
        mWindowManager.updateCompatInfo(mTaskConfig, newTaskListener,
                true /* show */, true /* hasSizeCompat */, CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager).release();
        verify(mWindowManager).createLayout(anyBoolean(), anyBoolean(), anyInt());

        // Change Camera Compat state, show a control.
        mWindowManager.updateCompatInfo(mTaskConfig, newTaskListener, true /* show */,
                true /* hasSizeCompat */, CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);

        verify(mCompatUILayout).setCameraControlVisibility(/* show */ true);
        verify(mCompatUILayout).updateCameraTreatmentButton(
                CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);

        clearInvocations(mWindowManager);
        clearInvocations(mCompatUILayout);
        // Change Camera Compat state, update a control.
        mWindowManager.updateCompatInfo(mTaskConfig, newTaskListener, true /* show */,
                true /* hasSizeCompat */, CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        verify(mCompatUILayout).setCameraControlVisibility(/* show */ true);
        verify(mCompatUILayout).updateCameraTreatmentButton(
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        clearInvocations(mWindowManager);
        clearInvocations(mCompatUILayout);
        // Change Camera Compat state to hidden, hide a control.
        mWindowManager.updateCompatInfo(mTaskConfig, newTaskListener,
                true /* show */, true /* hasSizeCompat */, CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mCompatUILayout).setCameraControlVisibility(/* show */ false);

        // Change task bounds, update position.
        clearInvocations(mWindowManager);
        final Configuration newTaskConfiguration = new Configuration();
        newTaskConfiguration.windowConfiguration.setBounds(new Rect(0, 1000, 0, 2000));
        mWindowManager.updateCompatInfo(newTaskConfiguration, newTaskListener,
                true /* show */, true /* hasSizeCompat */, CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager).updateSurfacePosition();
    }

    @Test
    public void testUpdateDisplayLayout() {
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = 1000;
        displayInfo.logicalHeight = 2000;
        final DisplayLayout displayLayout1 = new DisplayLayout(displayInfo,
                mContext.getResources(), /* hasNavigationBar= */ false, /* hasStatusBar= */ false);

        mWindowManager.updateDisplayLayout(displayLayout1);
        verify(mWindowManager).updateSurfacePosition();

        // No update if the display bounds is the same.
        clearInvocations(mWindowManager);
        final DisplayLayout displayLayout2 = new DisplayLayout(displayInfo,
                mContext.getResources(), /* hasNavigationBar= */ false, /* hasStatusBar= */ false);
        mWindowManager.updateDisplayLayout(displayLayout2);
        verify(mWindowManager, never()).updateSurfacePosition();
    }

    @Test
    public void testUpdateDisplayLayoutInsets() {
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = 1000;
        displayInfo.logicalHeight = 2000;
        final DisplayLayout displayLayout = new DisplayLayout(displayInfo,
                mContext.getResources(), /* hasNavigationBar= */ true, /* hasStatusBar= */ false);

        mWindowManager.updateDisplayLayout(displayLayout);
        verify(mWindowManager).updateSurfacePosition();

        // Update if the insets change on the existing display layout
        clearInvocations(mWindowManager);
        InsetsState insetsState = new InsetsState();
        InsetsSource insetsSource = new InsetsSource(ITYPE_EXTRA_NAVIGATION_BAR);
        insetsSource.setFrame(0, 0, 1000, 1000);
        insetsState.addSource(insetsSource);
        displayLayout.setInsets(mContext.getResources(), insetsState);
        mWindowManager.updateDisplayLayout(displayLayout);
        verify(mWindowManager).updateSurfacePosition();
    }

    @Test
    public void testUpdateVisibility() {
        // Create button if it is not created.
        mWindowManager.mCompatUILayout = null;
        mWindowManager.mHasSizeCompat = true;
        mWindowManager.updateVisibility(true /* show */);

        verify(mWindowManager).createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        // Hide button.
        clearInvocations(mWindowManager);
        doReturn(View.VISIBLE).when(mCompatUILayout).getVisibility();
        mWindowManager.updateVisibility(false /* show */);

        verify(mWindowManager, never()).createLayout(anyBoolean(), anyBoolean(), anyInt());
        verify(mCompatUILayout).setVisibility(View.GONE);

        // Show button.
        doReturn(View.GONE).when(mCompatUILayout).getVisibility();
        mWindowManager.updateVisibility(true /* show */);

        verify(mWindowManager, never()).createLayout(anyBoolean(), anyBoolean(), anyInt());
        verify(mCompatUILayout).setVisibility(View.VISIBLE);
    }

    @Test
    public void testAttachToParentSurface() {
        final SurfaceControl.Builder b = new SurfaceControl.Builder();
        mWindowManager.attachToParentSurface(b);

        verify(mTaskListener).attachChildSurfaceToTask(TASK_ID, b);
    }

    @Test
    public void testOnCameraDismissButtonClicked() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
        clearInvocations(mCompatUILayout);
        mWindowManager.onCameraDismissButtonClicked();

        verify(mCallback).onCameraControlStateUpdated(TASK_ID, CAMERA_COMPAT_CONTROL_DISMISSED);
        verify(mCompatUILayout).setCameraControlVisibility(/* show= */ false);
    }

    @Test
    public void testOnCameraTreatmentButtonClicked() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
        clearInvocations(mCompatUILayout);
        mWindowManager.onCameraTreatmentButtonClicked();

        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);
        verify(mCompatUILayout).updateCameraTreatmentButton(
                CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);

        mWindowManager.onCameraTreatmentButtonClicked();

        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
        verify(mCompatUILayout).updateCameraTreatmentButton(
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
    }

    @Test
    public void testOnRestartButtonClicked() {
        mWindowManager.onRestartButtonClicked();

        verify(mCallback).onSizeCompatRestartButtonClicked(TASK_ID);
    }

    @Test
    public void testOnRestartButtonLongClicked_showHint() {
       // Not create hint popup.
        mWindowManager.mShouldShowSizeCompatHint = false;
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);

        verify(mWindowManager).inflateCompatUILayout();
        verify(mCompatUILayout, never()).setSizeCompatHintVisibility(true /* show */);

        mWindowManager.onRestartButtonLongClicked();

        verify(mCompatUILayout).setSizeCompatHintVisibility(true /* show */);
    }

    @Test
    public void testOnCamerControlLongClicked_showHint() {
       // Not create hint popup.
        mWindowManager.mShouldShowCameraCompatHint = false;
        mWindowManager.createLayout(true /* show */, false /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        verify(mWindowManager).inflateCompatUILayout();
        verify(mCompatUILayout, never()).setCameraCompatHintVisibility(true /* show */);

        mWindowManager.onCameraButtonLongClicked();

        verify(mCompatUILayout).setCameraCompatHintVisibility(true /* show */);
    }

    @Test
    public void testCreateCameraCompatControl() {
        // Not create layout if show is false.
        mWindowManager.createLayout(false /* show */, false /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        verify(mWindowManager, never()).inflateCompatUILayout();

        // Not create hint popup.
        mWindowManager.mShouldShowCameraCompatHint = false;
        mWindowManager.createLayout(true /* show */, false /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        verify(mWindowManager).inflateCompatUILayout();
        verify(mCompatUILayout, never()).setCameraCompatHintVisibility(true /* show */);
        verify(mCompatUILayout).setCameraControlVisibility(true /* show */);

        // Create hint popup.
        mWindowManager.release();
        mWindowManager.mShouldShowCameraCompatHint = true;
        mWindowManager.createLayout(true /* show */, false /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        verify(mWindowManager, times(2)).inflateCompatUILayout();
        assertNotNull(mCompatUILayout);
        verify(mCompatUILayout, times(2)).setCameraControlVisibility(true /* show */);
        assertFalse(mWindowManager.mShouldShowCameraCompatHint);
    }

}
