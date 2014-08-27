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

import android.hardware.CameraInfo;
import android.hardware.ICamera;
import android.hardware.ICameraClient;
import android.hardware.ICameraServiceListener;
import android.hardware.IProCameraCallbacks;
import android.hardware.IProCameraUser;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.utils.BinderHolder;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

/**
 * <p>
 * Junit / Instrumentation test case for the camera2 api
 * </p>
 * <p>
 * To run only tests in this class:
 * </p>
 *
 * <pre>
 * adb shell am instrument \
 *   -e class com.android.mediaframeworktest.integration.CameraBinderTest \
 *   -w com.android.mediaframeworktest/.MediaFrameworkIntegrationTestRunner
 * </pre>
 */
public class CameraBinderTest extends AndroidTestCase {
    private static final int MAX_PARAMETERS_LENGTH = 100;

    static String TAG = "CameraBinderTest";

    // From ICameraService.h
    private static final int API_VERSION_1 = 1;
    private static final int API_VERSION_2 = 2;

    protected CameraBinderTestUtils mUtils;

    public CameraBinderTest() {
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mUtils = new CameraBinderTestUtils(getContext());
    }

    @SmallTest
    public void testNumberOfCameras() throws Exception {

        int numCameras = mUtils.getCameraService().getNumberOfCameras();
        assertTrue("At least this many cameras: " + mUtils.getGuessedNumCameras(),
                numCameras >= mUtils.getGuessedNumCameras());
        Log.v(TAG, "Number of cameras " + numCameras);
    }

    @SmallTest
    public void testCameraInfo() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            CameraInfo info = new CameraInfo();
            info.info.facing = -1;
            info.info.orientation = -1;

            assertTrue(
                    "Camera service returned info for camera " + cameraId,
                    mUtils.getCameraService().getCameraInfo(cameraId, info) ==
                    CameraBinderTestUtils.NO_ERROR);
            assertTrue("Facing was not set for camera " + cameraId, info.info.facing != -1);
            assertTrue("Orientation was not set for camera " + cameraId,
                    info.info.orientation != -1);

            Log.v(TAG, "Camera " + cameraId + " info: facing " + info.info.facing
                    + ", orientation " + info.info.orientation);
        }
    }

    @SmallTest
    public void testGetLegacyParameters() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            String[] parameters = new String[1];
            assertEquals("Camera service returned parameters for camera " + cameraId,
                    CameraBinderTestUtils.NO_ERROR,
                    mUtils.getCameraService().getLegacyParameters(cameraId, /*out*/parameters));
            assertNotNull(parameters[0]);
            assertTrue("Parameters should have at least one character in it",
                    parameters[0].length() > 0);

            int end = parameters[0].length();
            if (end > MAX_PARAMETERS_LENGTH) {
                end = MAX_PARAMETERS_LENGTH;
            }

            Log.v(TAG, "Camera " + cameraId + " parameters: " + parameters[0].substring(0, end));
        }
    }

    /** The camera2 api is only supported on HAL3.2+ devices */
    @SmallTest
    public void testSupportsCamera2Api() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            int res = mUtils.getCameraService().supportsCameraApi(cameraId, API_VERSION_2);

            if (res != CameraBinderTestUtils.NO_ERROR && res != CameraBinderTestUtils.EOPNOTSUPP) {
                fail("Camera service returned bad value when queried if it supports camera2 api: "
                        + res + " for camera ID " + cameraId);
            }

            boolean supports = res == CameraBinderTestUtils.NO_ERROR;
            Log.v(TAG, "Camera " + cameraId + " supports api2: " + supports);
        }
    }

    /** The camera1 api is supported on *all* devices regardless of HAL version */
    @SmallTest
    public void testSupportsCamera1Api() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            int res = mUtils.getCameraService().supportsCameraApi(cameraId, API_VERSION_1);
            assertEquals(
                    "Camera service returned bad value when queried if it supports camera1 api: "
                    + res + " for camera ID " + cameraId, CameraBinderTestUtils.NO_ERROR, res);
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
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            ICameraClient dummyCallbacks = new DummyCameraClient();

            String clientPackageName = getContext().getPackageName();

            BinderHolder holder = new BinderHolder();
            CameraBinderDecorator.newInstance(mUtils.getCameraService())
                    .connect(dummyCallbacks, cameraId, clientPackageName,
                    CameraBinderTestUtils.USE_CALLING_UID, holder);
            ICamera cameraUser = ICamera.Stub.asInterface(holder.getBinder());
            assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

            Log.v(TAG, String.format("Camera %s connected", cameraId));

            cameraUser.disconnect();
        }
    }

    static class DummyProCameraCallbacks extends DummyBase implements IProCameraCallbacks {
    }

    @SmallTest
    public void testConnectPro() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            IProCameraCallbacks dummyCallbacks = new DummyProCameraCallbacks();

            String clientPackageName = getContext().getPackageName();

            BinderHolder holder = new BinderHolder();
            CameraBinderDecorator.newInstance(mUtils.getCameraService())
                    .connectPro(dummyCallbacks, cameraId,
                    clientPackageName, CameraBinderTestUtils.USE_CALLING_UID, holder);
            IProCameraUser cameraUser = IProCameraUser.Stub.asInterface(holder.getBinder());
            assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

            Log.v(TAG, String.format("Camera %s connected", cameraId));

            cameraUser.disconnect();
        }
    }

    @SmallTest
    public void testConnectLegacy() throws Exception {
        final int CAMERA_HAL_API_VERSION_1_0 = 0x100;
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {
            ICamera cameraUser = null;
            ICameraClient dummyCallbacks = new DummyCameraClient();

            String clientPackageName = getContext().getPackageName();

            BinderHolder holder = new BinderHolder();

            try {
                CameraBinderDecorator.newInstance(mUtils.getCameraService())
                        .connectLegacy(dummyCallbacks, cameraId, CAMERA_HAL_API_VERSION_1_0,
                        clientPackageName,
                        CameraBinderTestUtils.USE_CALLING_UID, holder);
                cameraUser = ICamera.Stub.asInterface(holder.getBinder());
                assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

                Log.v(TAG, String.format("Camera %s connected as HAL1 legacy device", cameraId));
            } catch (RuntimeException e) {
                // Not all camera device support openLegacy.
                Log.i(TAG, "Unable to open camera as HAL1 legacy camera device " + e);
            } finally {
                if (cameraUser != null) {
                    cameraUser.disconnect();
                }
            }
        }
    }

    static class DummyCameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

        /*
         * (non-Javadoc)
         * @see
         * android.hardware.camera2.ICameraDeviceCallbacks#onCameraError(int,
         * android.hardware.camera2.CaptureResultExtras)
         */
        @Override
        public void onDeviceError(int errorCode, CaptureResultExtras resultExtras)
                throws RemoteException {
            // TODO Auto-generated method stub

        }

        /*
         * (non-Javadoc)
         * @see
         * android.hardware.camera2.ICameraDeviceCallbacks#onCaptureStarted(
         * android.hardware.camera2.CaptureResultExtras, long)
         */
        @Override
        public void onCaptureStarted(CaptureResultExtras resultExtras, long timestamp)
                throws RemoteException {
            // TODO Auto-generated method stub

        }

        /*
         * (non-Javadoc)
         * @see
         * android.hardware.camera2.ICameraDeviceCallbacks#onResultReceived(
         * android.hardware.camera2.impl.CameraMetadataNative,
         * android.hardware.camera2.CaptureResultExtras)
         */
        @Override
        public void onResultReceived(CameraMetadataNative result, CaptureResultExtras resultExtras)
                throws RemoteException {
            // TODO Auto-generated method stub

        }

        /*
         * (non-Javadoc)
         * @see android.hardware.camera2.ICameraDeviceCallbacks#onCameraIdle()
         */
        @Override
        public void onDeviceIdle() throws RemoteException {
            // TODO Auto-generated method stub

        }
    }

    @SmallTest
    public void testConnectDevice() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            ICameraDeviceCallbacks dummyCallbacks = new DummyCameraDeviceCallbacks();

            String clientPackageName = getContext().getPackageName();

            BinderHolder holder = new BinderHolder();
            CameraBinderDecorator.newInstance(mUtils.getCameraService())
                    .connectDevice(dummyCallbacks, cameraId,
                    clientPackageName, CameraBinderTestUtils.USE_CALLING_UID, holder);
            ICameraDeviceUser cameraUser = ICameraDeviceUser.Stub.asInterface(holder.getBinder());
            assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

            Log.v(TAG, String.format("Camera %s connected", cameraId));

            cameraUser.disconnect();
        }
    }

    static class DummyCameraServiceListener extends ICameraServiceListener.Stub {
        @Override
        public void onStatusChanged(int status, int cameraId)
                throws RemoteException {
            Log.v(TAG, String.format("Camera %d has status changed to 0x%x", cameraId, status));
        }
    }

    /**
     * <pre>
     * adb shell am instrument \
     *   -e class 'com.android.mediaframeworktest.integration.CameraBinderTest#testAddRemoveListeners' \
     *   -w com.android.mediaframeworktest/.MediaFrameworkIntegrationTestRunner
     * </pre>
     */
    @SmallTest
    public void testAddRemoveListeners() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            ICameraServiceListener listener = new DummyCameraServiceListener();

            assertTrue(
                    "Listener was removed before added",
                    mUtils.getCameraService().removeListener(listener) ==
                    CameraBinderTestUtils.BAD_VALUE);

            assertTrue("Listener was not added",
                    mUtils.getCameraService().addListener(listener) ==
                    CameraBinderTestUtils.NO_ERROR);
            assertTrue(
                    "Listener was wrongly added again",
                    mUtils.getCameraService().addListener(listener) ==
                    CameraBinderTestUtils.ALREADY_EXISTS);

            assertTrue(
                    "Listener was not removed",
                    mUtils.getCameraService().removeListener(listener) ==
                    CameraBinderTestUtils.NO_ERROR);
            assertTrue(
                    "Listener was wrongly removed again",
                    mUtils.getCameraService().removeListener(listener) ==
                    CameraBinderTestUtils.BAD_VALUE);
        }
    }
}
