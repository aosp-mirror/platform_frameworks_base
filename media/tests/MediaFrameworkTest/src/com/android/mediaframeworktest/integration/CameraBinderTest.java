/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mediaframeworktest.integration;

import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.hardware.CameraInfo;
import android.hardware.ICamera;
import android.hardware.ICameraClient;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceListener;
import android.hardware.IProCameraCallbacks;
import android.hardware.IProCameraUser;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

/**
 * Junit / Instrumentation test case for the camera2 api
 *
 * To run only tests in
 * this class:
 *
 * adb shell am instrument \
 *      -e class com.android.mediaframeworktest.integration.CameraBinderTest \
 *      -w com.android.mediaframeworktest/.MediaFrameworkIntegrationTestRunner
 */
public class CameraBinderTest extends AndroidTestCase {
    private static String TAG = "CameraBinderTest";

    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";
    private static final int NO_ERROR = 0;
    private static final int ALREADY_EXISTS = -17;
    private static final int BAD_VALUE = -22;

    private static final int USE_CALLING_UID = -1;

    private ICameraService mCameraService;
    private int mGuessedNumCameras = 0;

    public CameraBinderTest() {
    }

    private final static boolean isFeatureAvailable(Context context, String feature) {
        final PackageManager packageManager = context.getPackageManager();
        final FeatureInfo[] featuresList = packageManager.getSystemAvailableFeatures();
        for (FeatureInfo f : featuresList) {
            if (f.name != null && f.name.equals(feature)) {
                return true;
            }
        }

        return false;
    }

    // Guess the lower bound for how many cameras there are
    private void guessNumCameras() {

        /**
         * Why do we need this? This way we have no dependency on getNumCameras
         * actually working. On most systems there are only 0, 1, or 2 cameras,
         * and this covers that 'usual case'. On other systems there might be 3+
         * cameras, but this will at least check the first 2.
         */
        mGuessedNumCameras = 0;

        // Front facing camera
        if (isFeatureAvailable(getContext(), PackageManager.FEATURE_CAMERA_FRONT)) {
            mGuessedNumCameras++;
        }

        // Back facing camera
        if (isFeatureAvailable(getContext(), PackageManager.FEATURE_CAMERA)) {
            mGuessedNumCameras++;
        }

        // Any facing camera
        if (mGuessedNumCameras == 0
                && isFeatureAvailable(getContext(), PackageManager.FEATURE_CAMERA_ANY)) {
            mGuessedNumCameras++;
        }

        Log.v(TAG, "Guessing there are at least " + mGuessedNumCameras + " cameras");
    }

    protected void setUp() throws Exception {
        super.setUp();

        guessNumCameras();

        IBinder cameraServiceBinder = ServiceManager.getService(CAMERA_SERVICE_BINDER_NAME);
        assertNotNull("Camera service IBinder should not be null", cameraServiceBinder);

        mCameraService = ICameraService.Stub.asInterface(cameraServiceBinder);
        assertNotNull("Camera service should not be null", mCameraService);
    }

    @SmallTest
    public void testNumberOfCameras() throws Exception {
        int numCameras = mCameraService.getNumberOfCameras();
        assertTrue("At least this many cameras: " + mGuessedNumCameras,
                numCameras >= mGuessedNumCameras);
        Log.v(TAG, "Number of cameras " + numCameras);
    }

    @SmallTest
    public void testCameraInfo() throws Exception {
        for (int cameraId = 0; cameraId < mGuessedNumCameras; ++cameraId) {

            CameraInfo info = new CameraInfo();
            info.info.facing = -1;
            info.info.orientation = -1;

            assertTrue("Camera service returned info for camera " + cameraId,
                    mCameraService.getCameraInfo(cameraId, info) == NO_ERROR);
            assertTrue("Facing was not set for camera " + cameraId, info.info.facing != -1);
            assertTrue("Orientation was not set for camera " + cameraId,
                    info.info.orientation != -1);

            Log.v(TAG, "Camera " + cameraId + " info: facing " + info.info.facing
                    + ", orientation " + info.info.orientation);
        }
    }

    static abstract class DummyBase extends Binder implements android.os.IInterface {
        @Override
        public IBinder asBinder() {
            return this;
        }
    }

    static class DummyCameraClient extends DummyBase implements ICameraClient {
    }

    @SmallTest
    public void testConnect() throws Exception {
        for (int cameraId = 0; cameraId < mGuessedNumCameras; ++cameraId) {

            ICameraClient dummyCallbacks = new DummyCameraClient();

            String clientPackageName = getContext().getPackageName();

            ICamera cameraUser = mCameraService.connect(dummyCallbacks, cameraId, clientPackageName,
                    USE_CALLING_UID);
            assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

            Log.v(TAG, String.format("Camera %s connected", cameraId));

            cameraUser.disconnect();
        }
    }

    static class DummyProCameraCallbacks extends DummyBase implements IProCameraCallbacks {
    }

    @SmallTest
    public void testConnectPro() throws Exception {
        for (int cameraId = 0; cameraId < mGuessedNumCameras; ++cameraId) {

            IProCameraCallbacks dummyCallbacks = new DummyProCameraCallbacks();

            String clientPackageName = getContext().getPackageName();

            IProCameraUser cameraUser = mCameraService.connectPro(dummyCallbacks, cameraId,
                    clientPackageName, USE_CALLING_UID);
            assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

            Log.v(TAG, String.format("Camera %s connected", cameraId));

            cameraUser.disconnect();
        }
    }

    static class DummyCameraServiceListener extends DummyBase implements ICameraServiceListener {
        @Override
        public void onStatusChanged(int status, int cameraId)
                throws RemoteException {
            Log.v(TAG, String.format("Camera %d has status changed to 0x%x", cameraId, status));
        }
    }

    /**
     * adb shell am instrument \
     *     -e class 'com.android.mediaframeworktest.integration.CameraBinderTest#testAddRemoveListeners' \
     *     -w com.android.mediaframeworktest/.MediaFrameworkIntegrationTestRunner
     */
    @SmallTest
    public void testAddRemoveListeners() throws Exception {
        for (int cameraId = 0; cameraId < mGuessedNumCameras; ++cameraId) {

            ICameraServiceListener listener = new DummyCameraServiceListener();

            assertTrue("Listener was removed before added",
                    mCameraService.removeListener(listener) == BAD_VALUE);

            assertTrue("Listener was not added", mCameraService.addListener(listener) == NO_ERROR);
            assertTrue("Listener was wrongly added again",
                    mCameraService.addListener(listener) == ALREADY_EXISTS);

            assertTrue("Listener was not removed",
                    mCameraService.removeListener(listener) == NO_ERROR);
            assertTrue("Listener was wrongly removed again",
                    mCameraService.removeListener(listener) == BAD_VALUE);
        }
    }
}
