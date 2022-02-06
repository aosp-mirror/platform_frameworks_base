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

package com.android.wm.shell.back;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.hardware.HardwareBuffer;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.window.BackNavigationInfo;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * atest WMShellUnitTests:BackAnimationControllerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class BackAnimationControllerTest {

    private final ShellExecutor mShellExecutor = new TestShellExecutor();

    @Mock
    private Context mContext;

    @Mock
    private SurfaceControl.Transaction mTransaction;

    @Mock
    private IActivityTaskManager mActivityTaskManager;

    private BackAnimationController mController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mController = new BackAnimationController(
                mShellExecutor, mTransaction, mActivityTaskManager, mContext);
    }

    private void createNavigationInfo(SurfaceControl topWindowLeash,
            SurfaceControl screenshotSurface,
            HardwareBuffer hardwareBuffer) {
        BackNavigationInfo navigationInfo = new BackNavigationInfo(
                BackNavigationInfo.TYPE_RETURN_TO_HOME,
                topWindowLeash,
                screenshotSurface,
                hardwareBuffer,
                new WindowConfiguration(),
                new RemoteCallback((bundle) -> {}),
                null);
        try {
            doReturn(navigationInfo).when(mActivityTaskManager).startBackNavigation();
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    @Test
    public void screenshotAttachedAndVisible() {
        SurfaceControl topWindowLeash = new SurfaceControl();
        SurfaceControl screenshotSurface = new SurfaceControl();
        HardwareBuffer hardwareBuffer = mock(HardwareBuffer.class);
        createNavigationInfo(topWindowLeash, screenshotSurface, hardwareBuffer);
        mController.onMotionEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0));
        verify(mTransaction).setBuffer(screenshotSurface, hardwareBuffer);
        verify(mTransaction).setVisibility(screenshotSurface, true);
        verify(mTransaction).apply();
    }

    @Test
    public void surfaceMovesWithGesture() {
        SurfaceControl topWindowLeash = new SurfaceControl();
        SurfaceControl screenshotSurface = new SurfaceControl();
        HardwareBuffer hardwareBuffer = mock(HardwareBuffer.class);
        createNavigationInfo(topWindowLeash, screenshotSurface, hardwareBuffer);
        mController.onMotionEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0));
        mController.onMotionEvent(MotionEvent.obtain(10, 0, MotionEvent.ACTION_MOVE, 100, 100, 0));
        verify(mTransaction).setPosition(topWindowLeash, 100, 100);
        verify(mTransaction, atLeastOnce()).apply();
    }
}
