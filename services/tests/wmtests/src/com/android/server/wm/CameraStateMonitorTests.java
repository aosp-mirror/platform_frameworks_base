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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;

import android.content.ComponentName;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;

/**
 * Tests for {@link DisplayRotationCompatPolicy}.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayRotationCompatPolicyTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public final class CameraStateMonitorTests extends WindowTestsBase {

    private static final String TEST_PACKAGE_1 = "com.test.package.one";
    private static final String TEST_PACKAGE_2 = "com.test.package.two";
    private static final String CAMERA_ID_1 = "camera-1";
    private static final String CAMERA_ID_2 = "camera-2";
    private static final String TEST_PACKAGE_1_LABEL = "testPackage1";
    private CameraManager mMockCameraManager;
    private Handler mMockHandler;
    private LetterboxConfiguration mLetterboxConfiguration;

    private CameraStateMonitor mCameraStateMonitor;
    private CameraManager.AvailabilityCallback mCameraAvailabilityCallback;

    private ActivityRecord mActivity;
    private Task mTask;

    // Simulates a listener which will not react to the change on a particular activity.
    private final FakeCameraCompatStateListener mNotInterestedListener =
            new FakeCameraCompatStateListener(
                    /*onCameraOpenedReturnValue=*/ false,
                    /*simulateUnsuccessfulCloseOnce=*/ false);
    // Simulates a listener which will react to the change on a particular activity - for example
    // put the activity in a camera compat mode.
    private final FakeCameraCompatStateListener mInterestedListener =
            new FakeCameraCompatStateListener(
                    /*onCameraOpenedReturnValue=*/ true,
                    /*simulateUnsuccessfulCloseOnce=*/ false);
    // Simulates a listener which for some reason cannot process `onCameraClosed` event once it
    // first arrives - this means that the update needs to be postponed.
    private final FakeCameraCompatStateListener mListenerCannotClose =
            new FakeCameraCompatStateListener(
                    /*onCameraOpenedReturnValue=*/ true,
                    /*simulateUnsuccessfulCloseOnce=*/ true);

    @Before
    public void setUp() throws Exception {
        mLetterboxConfiguration = mDisplayContent.mWmService.mLetterboxConfiguration;
        spyOn(mLetterboxConfiguration);
        when(mLetterboxConfiguration.isCameraCompatTreatmentEnabled())
                .thenReturn(true);
        when(mLetterboxConfiguration.isCameraCompatRefreshEnabled())
                .thenReturn(true);
        when(mLetterboxConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(true);

        mMockCameraManager = mock(CameraManager.class);
        doAnswer(invocation -> {
            mCameraAvailabilityCallback = invocation.getArgument(1);
            return null;
        }).when(mMockCameraManager).registerAvailabilityCallback(
                any(Executor.class), any(CameraManager.AvailabilityCallback.class));

        spyOn(mContext);
        when(mContext.getSystemService(CameraManager.class)).thenReturn(mMockCameraManager);

        spyOn(mDisplayContent);

        mDisplayContent.setIgnoreOrientationRequest(true);

        mMockHandler = mock(Handler.class);

        when(mMockHandler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(
                invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                });
        mCameraStateMonitor =
                new CameraStateMonitor(mDisplayContent, mMockHandler);
        configureActivity(TEST_PACKAGE_1);
        configureActivity(TEST_PACKAGE_2);

        mCameraStateMonitor.startListeningToCameraState();
    }

    @After
    public void tearDown() {
        // Remove all listeners.
        mCameraStateMonitor.removeCameraStateListener(mNotInterestedListener);
        mCameraStateMonitor.removeCameraStateListener(mInterestedListener);
        mCameraStateMonitor.removeCameraStateListener(mListenerCannotClose);

        // Reset the listener's state.
        mNotInterestedListener.resetCounters();
        mInterestedListener.resetCounters();
        mListenerCannotClose.resetCounters();
    }

    @Test
    public void testOnCameraOpened_listenerAdded_notifiesCameraOpened() {
        mCameraStateMonitor.addCameraStateListener(mNotInterestedListener);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        assertEquals(1, mNotInterestedListener.mOnCameraOpenedCounter);
    }

    @Test
    public void testOnCameraOpened_listenerReturnsFalse_doesNotNotifyCameraClosed() {
        mCameraStateMonitor.addCameraStateListener(mNotInterestedListener);
        // Listener returns false on `onCameraOpened`.
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);

        assertEquals(0, mNotInterestedListener.mOnCameraClosedCounter);
    }

    @Test
    public void testOnCameraOpened_listenerReturnsTrue_notifyCameraClosed() {
        mCameraStateMonitor.addCameraStateListener(mInterestedListener);
        // Listener returns true on `onCameraOpened`.
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);

        assertEquals(1, mInterestedListener.mOnCameraClosedCounter);
    }

    @Test
    public void testOnCameraOpened_listenerCannotCloseYet_notifyCameraClosedAgain() {
        mCameraStateMonitor.addCameraStateListener(mListenerCannotClose);
        // Listener returns true on `onCameraOpened`.
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);

        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);

        assertEquals(2, mListenerCannotClose.mOnCameraClosedCounter);
    }

    @Test
    public void testReconnectedToDifferentCamera_notifiesListener() {
        mCameraStateMonitor.addCameraStateListener(mInterestedListener);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_2, TEST_PACKAGE_1);

        assertEquals(2, mInterestedListener.mOnCameraOpenedCounter);
    }

    @Test
    public void testDifferentAppConnectedToCamera_notifiesListener() {
        mCameraStateMonitor.addCameraStateListener(mInterestedListener);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_2);

        assertEquals(2, mInterestedListener.mOnCameraOpenedCounter);
    }

    @Test
    public void testCameraAlreadyClosed_notifiesListenerOnce() {
        mCameraStateMonitor.addCameraStateListener(mInterestedListener);
        mCameraAvailabilityCallback.onCameraOpened(CAMERA_ID_1, TEST_PACKAGE_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);
        mCameraAvailabilityCallback.onCameraClosed(CAMERA_ID_1);

        assertEquals(1, mInterestedListener.mOnCameraClosedCounter);
    }

    private void configureActivity(@NonNull String packageName) {
        mTask = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent)
                .build();

        mActivity = new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(packageName, ".TestActivity"))
                .setTask(mTask)
                .build();

        spyOn(mActivity.mAtmService.getLifecycleManager());
        spyOn(mActivity.mLetterboxUiController);

        doReturn(mActivity).when(mDisplayContent).topRunningActivity(anyBoolean());
    }

    private class FakeCameraCompatStateListener implements
            CameraStateMonitor.CameraCompatStateListener {

        int mOnCameraOpenedCounter = 0;
        int mOnCameraClosedCounter = 0;

        boolean mOnCameraOpenedReturnValue = true;
        private boolean mOnCameraClosedReturnValue = true;

        /**
         * @param simulateUnsuccessfulCloseOnce When false, returns `true` on every
         *                                      `onCameraClosed`. When true, returns `false` on the
         *                                      first `onCameraClosed` callback, and `true on the
         *                                      subsequent calls. This fake implementation tests the
         *                                      retry mechanism in {@link CameraStateMonitor}.
         */
        FakeCameraCompatStateListener(boolean onCameraOpenedReturnValue,
                boolean simulateUnsuccessfulCloseOnce) {
            mOnCameraOpenedReturnValue = onCameraOpenedReturnValue;
            mOnCameraClosedReturnValue = !simulateUnsuccessfulCloseOnce;
        }

        @Override
        public boolean onCameraOpened(@NonNull ActivityRecord cameraActivity,
                @NonNull String cameraId) {
            mOnCameraOpenedCounter++;
            return mOnCameraOpenedReturnValue;
        }

        @Override
        public boolean onCameraClosed(@NonNull ActivityRecord cameraActivity,
                @NonNull String cameraId) {
            mOnCameraClosedCounter++;
            boolean returnValue = mOnCameraClosedReturnValue;
            // If false, return false only the first time, so it doesn't fall in the infinite retry
            // loop.
            mOnCameraClosedReturnValue = true;
            return returnValue;
        }

        void resetCounters() {
            mOnCameraOpenedCounter = 0;
            mOnCameraClosedCounter = 0;
        }
    }
}
