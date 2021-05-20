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
import android.hardware.ICameraService;
import android.hardware.ICameraServiceListener;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
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

    private static final int CAMERA_TYPE_BACKWARD_COMPATIBLE = 0;
    private static final int CAMERA_TYPE_ALL = 1;

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

        int numCameras = mUtils.getCameraService().getNumberOfCameras(CAMERA_TYPE_ALL);
        assertTrue("At least this many cameras: " + mUtils.getGuessedNumCameras(),
                numCameras >= mUtils.getGuessedNumCameras());
        Log.v(TAG, "Number of cameras " + numCameras);
    }

    @SmallTest
    public void testCameraInfo() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            CameraInfo info = mUtils.getCameraService().getCameraInfo(cameraId);
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

            String parameters = mUtils.getCameraService().getLegacyParameters(cameraId);
            assertNotNull(parameters);
            assertTrue("Parameters should have at least one character in it",
                    parameters.length() > 0);

            int end = parameters.length();
            if (end > MAX_PARAMETERS_LENGTH) {
                end = MAX_PARAMETERS_LENGTH;
            }

            Log.v(TAG, "Camera " + cameraId + " parameters: " + parameters.substring(0, end));
        }
    }

    /** The camera2 api is only supported on HAL3.2+ devices */
    @SmallTest
    public void testSupportsCamera2Api() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {
            boolean supports = mUtils.getCameraService().supportsCameraApi(
                String.valueOf(cameraId), API_VERSION_2);

            Log.v(TAG, "Camera " + cameraId + " supports api2: " + supports);
        }
    }

    /** The camera1 api is supported on *all* devices regardless of HAL version */
    @SmallTest
    public void testSupportsCamera1Api() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            boolean supports = mUtils.getCameraService().supportsCameraApi(
                String.valueOf(cameraId), API_VERSION_1);
            assertTrue(
                    "Camera service returned false when queried if it supports camera1 api " +
                    " for camera ID " + cameraId, supports);
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

            ICamera cameraUser = mUtils.getCameraService()
                    .connect(dummyCallbacks, cameraId, clientPackageName,
                            ICameraService.USE_CALLING_UID,
                            ICameraService.USE_CALLING_PID);
            assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

            Log.v(TAG, String.format("Camera %s connected", cameraId));

            cameraUser.disconnect();
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
        public void onResultReceived(CameraMetadataNative result, CaptureResultExtras resultExtras,
                PhysicalCaptureResultInfo physicalResults[]) throws RemoteException {
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

        /*
         * (non-Javadoc)
         * @see android.hardware.camera2.ICameraDeviceCallbacks#onPrepared()
         */
        @Override
        public void onPrepared(int streamId) throws RemoteException {
            // TODO Auto-generated method stub

        }

        /*
         * (non-Javadoc)
         * @see android.hardware.camera2.ICameraDeviceCallbacks#onRequestQueueEmpty()
         */
        @Override
        public void onRequestQueueEmpty() throws RemoteException {
            // TODO Auto-generated method stub

        }

        /*
         * (non-Javadoc)
         * @see android.hardware.camera2.ICameraDeviceCallbacks#onRepeatingRequestError()
         */
        @Override
        public void onRepeatingRequestError(long lastFrameNumber, int repeatingRequestId) {
            // TODO Auto-generated method stub
        }
    }

    @SmallTest
    public void testConnectDevice() throws Exception {
        for (int cameraId = 0; cameraId < mUtils.getGuessedNumCameras(); ++cameraId) {

            ICameraDeviceCallbacks dummyCallbacks = new DummyCameraDeviceCallbacks();

            String clientPackageName = getContext().getPackageName();
            String clientAttributionTag = getContext().getAttributionTag();

            ICameraDeviceUser cameraUser =
                    mUtils.getCameraService().connectDevice(
                        dummyCallbacks, String.valueOf(cameraId),
                        clientPackageName, clientAttributionTag,
                        ICameraService.USE_CALLING_UID, 0 /*oomScoreOffset*/);
            assertNotNull(String.format("Camera %s was null", cameraId), cameraUser);

            Log.v(TAG, String.format("Camera %s connected", cameraId));

            cameraUser.disconnect();
        }
    }

    static class DummyCameraServiceListener extends ICameraServiceListener.Stub {
        @Override
        public void onStatusChanged(int status, String cameraId)
                throws RemoteException {
            Log.v(TAG, String.format("Camera %s has status changed to 0x%x", cameraId, status));
        }
        public void onTorchStatusChanged(int status, String cameraId)
                throws RemoteException {
            Log.v(TAG, String.format("Camera %s has torch status changed to 0x%x",
                    cameraId, status));
        }
        @Override
        public void onPhysicalCameraStatusChanged(int status, String cameraId,
                String physicalCameraId) throws RemoteException {
            Log.v(TAG, String.format("Camera %s : %s has status changed to 0x%x",
                    cameraId, physicalCameraId, status));
        }
        @Override
        public void onCameraAccessPrioritiesChanged() {
            Log.v(TAG, "Camera access permission change");
        }
        @Override
        public void onCameraOpened(String cameraId, String clientPackageName) {
            Log.v(TAG, String.format("Camera %s is opened by client package %s",
                    cameraId, clientPackageName));
        }
        @Override
        public void onCameraClosed(String cameraId) {
            Log.v(TAG, String.format("Camera %s is closed", cameraId));
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

            try {
                mUtils.getCameraService().removeListener(listener);
                fail("Listener was removed before added");
            } catch (ServiceSpecificException e) {
                assertEquals("Listener was removed before added",
                        e.errorCode, ICameraService.ERROR_ILLEGAL_ARGUMENT);
            }

            mUtils.getCameraService().addListener(listener);

            try {
                mUtils.getCameraService().addListener(listener);
                fail("Listener was wrongly added again");
            } catch (ServiceSpecificException e) {
                assertEquals("Listener was wrongly added again",
                        e.errorCode, ICameraService.ERROR_ALREADY_EXISTS);
            }

            mUtils.getCameraService().removeListener(listener);

            try {
                mUtils.getCameraService().removeListener(listener);
                fail("Listener was wrongly removed twice");
            } catch (ServiceSpecificException e) {
                assertEquals("Listener was wrongly removed twice",
                        e.errorCode, ICameraService.ERROR_ILLEGAL_ARGUMENT);
            }
        }
    }
}
