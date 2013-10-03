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
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.impl.CameraMetadataNative;
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
    static String TAG = "CameraBinderTest";

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

    static class DummyCameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

        @Override
        public void onCameraError(int errorCode) {
        }

        @Override
        public void onCameraIdle() {
        }

        @Override
        public void onCaptureStarted(int requestId, long timestamp) {
        }

        @Override
        public void onResultReceived(int frameId, CameraMetadataNative result)
                throws RemoteException {
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
