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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.app.TaskInfo.CameraCompatControlState;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.SurfaceControlViewHost;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.compatui.CompatUIWindowManager.CompatUIHintsState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CompatUILayout}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:CompatUILayoutTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class CompatUILayoutTest extends ShellTestCase {

    private static final int TASK_ID = 1;

    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private CompatUIController.CompatUICallback mCallback;
    @Mock private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock private SurfaceControlViewHost mViewHost;

    private CompatUIWindowManager mWindowManager;
    private CompatUILayout mLayout;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mWindowManager = new CompatUIWindowManager(mContext,
                createTaskInfo(/* hasSizeCompat= */ false, CAMERA_COMPAT_CONTROL_HIDDEN),
                mSyncTransactionQueue, mCallback, mTaskListener,
                new DisplayLayout(), new CompatUIHintsState());

        mLayout = (CompatUILayout)
                LayoutInflater.from(mContext).inflate(R.layout.compat_ui_layout, null);
        mLayout.inject(mWindowManager);

        spyOn(mWindowManager);
        spyOn(mLayout);
        doReturn(mViewHost).when(mWindowManager).createSurfaceViewHost();
        doReturn(mLayout).when(mWindowManager).inflateLayout();
    }

    @Test
    public void testOnClickForRestartButton() {
        final ImageButton button = mLayout.findViewById(R.id.size_compat_restart_button);
        button.performClick();

        verify(mWindowManager).onRestartButtonClicked();
        verify(mCallback).onSizeCompatRestartButtonClicked(TASK_ID);
    }

    @Test
    public void testOnLongClickForRestartButton() {
        doNothing().when(mWindowManager).onRestartButtonLongClicked();

        final ImageButton button = mLayout.findViewById(R.id.size_compat_restart_button);
        button.performLongClick();

        verify(mWindowManager).onRestartButtonLongClicked();
    }

    @Test
    public void testOnClickForSizeCompatHint() {
        mWindowManager.mHasSizeCompat = true;
        mWindowManager.createLayout(/* canShow= */ true);
        final LinearLayout sizeCompatHint = mLayout.findViewById(R.id.size_compat_hint);
        sizeCompatHint.performClick();

        verify(mLayout).setSizeCompatHintVisibility(/* show= */ false);
    }

    @Test
    public void testUpdateCameraTreatmentButton_treatmentAppliedByDefault() {
        mWindowManager.mCameraCompatControlState = CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED;
        mWindowManager.createLayout(/* canShow= */ true);
        final ImageButton button =
                mLayout.findViewById(R.id.camera_compat_treatment_button);
        button.performClick();

        verify(mWindowManager).onCameraTreatmentButtonClicked();
        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        button.performClick();

        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);
    }

    @Test
    public void testUpdateCameraTreatmentButton_treatmentSuggestedByDefault() {
        mWindowManager.mCameraCompatControlState = CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED;
        mWindowManager.createLayout(/* canShow= */ true);
        final ImageButton button =
                mLayout.findViewById(R.id.camera_compat_treatment_button);
        button.performClick();

        verify(mWindowManager).onCameraTreatmentButtonClicked();
        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);

        button.performClick();

        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
    }

    @Test
    public void testOnCameraDismissButtonClicked() {
        mWindowManager.mCameraCompatControlState = CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED;
        mWindowManager.createLayout(/* canShow= */ true);
        final ImageButton button =
                mLayout.findViewById(R.id.camera_compat_dismiss_button);
        button.performClick();

        verify(mWindowManager).onCameraDismissButtonClicked();
        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_DISMISSED);
        verify(mLayout).setCameraControlVisibility(/* show */ false);
    }

    @Test
    public void testOnLongClickForCameraTreatmentButton() {
        doNothing().when(mWindowManager).onCameraButtonLongClicked();

        final ImageButton button =
                mLayout.findViewById(R.id.camera_compat_treatment_button);
        button.performLongClick();

        verify(mWindowManager).onCameraButtonLongClicked();
    }

    @Test
    public void testOnLongClickForCameraDismissButton() {
        doNothing().when(mWindowManager).onCameraButtonLongClicked();

        final ImageButton button = mLayout.findViewById(R.id.camera_compat_dismiss_button);
        button.performLongClick();

        verify(mWindowManager).onCameraButtonLongClicked();
    }

    @Test
    public void testOnClickForCameraCompatHint() {
        mWindowManager.mCameraCompatControlState = CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED;
        mWindowManager.createLayout(/* canShow= */ true);
        final LinearLayout hint = mLayout.findViewById(R.id.camera_compat_hint);
        hint.performClick();

        verify(mLayout).setCameraCompatHintVisibility(/* show= */ false);
    }

    private static TaskInfo createTaskInfo(boolean hasSizeCompat,
            @CameraCompatControlState int cameraCompatControlState) {
        ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = TASK_ID;
        taskInfo.topActivityInSizeCompat = hasSizeCompat;
        taskInfo.cameraCompatControlState = cameraCompatControlState;
        return taskInfo;
    }
}
