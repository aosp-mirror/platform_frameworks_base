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

import android.graphics.SurfaceTexture;
import android.hardware.photography.CameraMetadata;
import android.hardware.photography.CameraPropertiesKeys;
import android.hardware.photography.CaptureRequest;
import android.hardware.photography.ICameraDeviceCallbacks;
import android.hardware.photography.ICameraDeviceUser;
import android.os.RemoteException;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.view.Surface;

import static android.hardware.photography.CameraDevice.TEMPLATE_PREVIEW;

import com.android.mediaframeworktest.MediaFrameworkIntegrationTestRunner;
import com.android.mediaframeworktest.integration.CameraBinderTest.DummyBase;

public class CameraDeviceBinderTest extends AndroidTestCase {
    private static String TAG = "CameraDeviceBinderTest";

    private int mCameraId;
    private ICameraDeviceUser mCameraUser;
    private CameraBinderTestUtils mUtils;

    public CameraDeviceBinderTest() {
    }

    static class DummyCameraDeviceCallbacks extends DummyBase implements ICameraDeviceCallbacks {

        @Override
        public void notifyCallback(int msgType, int ext1, int ext2) throws RemoteException {
        }

        @Override
        public void onResultReceived(int frameId, CameraMetadata result) throws RemoteException {
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mUtils = new CameraBinderTestUtils(getContext());

        // This cannot be set in the constructor, since the onCreate isn't
        // called yet
        mCameraId = MediaFrameworkIntegrationTestRunner.mCameraId;

        ICameraDeviceCallbacks dummyCallbacks = new DummyCameraDeviceCallbacks();

        String clientPackageName = getContext().getPackageName();

        mCameraUser = mUtils.getCameraService().connectDevice(dummyCallbacks, mCameraId,
                clientPackageName, CameraBinderTestUtils.USE_CALLING_UID);
        assertNotNull(String.format("Camera %s was null", mCameraId), mCameraUser);

        Log.v(TAG, String.format("Camera %s connected", mCameraId));
    }

    @Override
    protected void tearDown() throws Exception {
        mCameraUser.disconnect();
        mCameraUser = null;
    }

    @SmallTest
    public void testCreateDefaultRequest() throws Exception {
        CameraMetadata metadata = new CameraMetadata();
        assertTrue(metadata.isEmpty());

        int status = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW, /* out */metadata);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
        assertFalse(metadata.isEmpty());

        metadata.close();
    }

    @SmallTest
    public void testCreateStream() throws Exception {
        SurfaceTexture surfaceTexture = new SurfaceTexture(/* ignored */0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        Surface surface = new Surface(surfaceTexture);

        int streamId = mCameraUser.createStream(/* ignored */10, /* ignored */20, /* ignored */30,
                surface);

        assertEquals(0, streamId);

        assertEquals(CameraBinderTestUtils.ALREADY_EXISTS,
                mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0, surface));

        assertEquals(CameraBinderTestUtils.NO_ERROR, mCameraUser.deleteStream(streamId));
    }

    @SmallTest
    public void testDeleteInvalidStream() throws Exception {
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(-1));
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(0));
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(1));
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(0xC0FFEE));
    }

    @SmallTest
    public void testCreateStreamTwo() throws Exception {

        // Create first stream

        SurfaceTexture surfaceTexture = new SurfaceTexture(/* ignored */0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        Surface surface = new Surface(surfaceTexture);

        int streamId = mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0,
                surface);

        assertEquals(0, streamId);

        assertEquals(CameraBinderTestUtils.ALREADY_EXISTS,
                mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0, surface));

        // Create second stream.

        SurfaceTexture surfaceTexture2 = new SurfaceTexture(/* ignored */0);
        surfaceTexture2.setDefaultBufferSize(640, 480);
        Surface surface2 = new Surface(surfaceTexture2);

        int streamId2 = mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0,
                surface2);

        assertEquals(1, streamId2);

        // Clean up streams

        assertEquals(CameraBinderTestUtils.NO_ERROR, mCameraUser.deleteStream(streamId));
        assertEquals(CameraBinderTestUtils.NO_ERROR, mCameraUser.deleteStream(streamId2));
    }

    @SmallTest
    public void testSubmitBadRequest() throws Exception {

        CameraMetadata metadata = new CameraMetadata();
        assertTrue(metadata.isEmpty());

        CaptureRequest request = new CaptureRequest();
        assertTrue(request.isEmpty());

        int status = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW, /* out */metadata);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
        assertFalse(metadata.isEmpty());

        request.swap(metadata);
        assertFalse(request.isEmpty());
        assertTrue(metadata.isEmpty());

        status = mCameraUser.submitRequest(request, /* streaming */false);
        assertEquals("Expected submitRequest to return BAD_VALUE " +
                "since we had 0 surface targets set.", CameraBinderTestUtils.BAD_VALUE, status);

        SurfaceTexture surfaceTexture = new SurfaceTexture(/* ignored */0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        Surface surface = new Surface(surfaceTexture);
        request.addTarget(surface);

        status = mCameraUser.submitRequest(request, /* streaming */false);
        assertEquals("Expected submitRequest to return BAD_VALUE since " +
                "the target surface wasn't registered with createStream.",
                CameraBinderTestUtils.BAD_VALUE, status);

        request.close();
        metadata.close();
        surface.release();
    }

    @SmallTest
    public void testSubmitGoodRequest() throws Exception {

        CameraMetadata metadata = new CameraMetadata();
        assertTrue(metadata.isEmpty());

        CaptureRequest request = new CaptureRequest();
        assertTrue(request.isEmpty());

        // Create default request from template.

        int status = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW, /* out */metadata);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
        assertFalse(metadata.isEmpty());

        request.swap(metadata);
        assertFalse(request.isEmpty());
        assertTrue(metadata.isEmpty());

        SurfaceTexture surfaceTexture = new SurfaceTexture(/* ignored */0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        Surface surface = new Surface(surfaceTexture);

        // Create stream first. Pre-requisite to submitting a request using that
        // stream.

        int streamId = mCameraUser.createStream(/* ignored */10, /* ignored */20, /* ignored */30,
                surface);
        assertEquals(0, streamId);

        request.addTarget(surface);

        // Submit valid request twice.

        int requestId1;
        requestId1 = mCameraUser.submitRequest(request, /* streaming */false);
        assertTrue("Request IDs should be non-negative", requestId1 >= 0);

        int requestId2 = mCameraUser.submitRequest(request, /* streaming */false);
        assertTrue("Request IDs should be non-negative", requestId2 >= 0);
        assertNotSame("Request IDs should be unique for multiple requests", requestId1, requestId2);

        surface.release();
        request.close();
        metadata.close();
    }

    @SmallTest
    public void testSubmitStreamingRequest() throws Exception {

        CameraMetadata metadata = new CameraMetadata();
        assertTrue(metadata.isEmpty());

        CaptureRequest request = new CaptureRequest();
        assertTrue(request.isEmpty());

        // Create default request from template.

        int status = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW, /* out */metadata);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
        assertFalse(metadata.isEmpty());

        request.swap(metadata);
        assertFalse(request.isEmpty());
        assertTrue(metadata.isEmpty());

        SurfaceTexture surfaceTexture = new SurfaceTexture(/* ignored */0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        Surface surface = new Surface(surfaceTexture);

        // Create stream first. Pre-requisite to submitting a request using that
        // stream.

        int streamId = mCameraUser.createStream(/* ignored */10, /* ignored */20, /* ignored */30,
                surface);
        assertEquals(0, streamId);

        request.addTarget(surface);

        // Submit valid request once (non-streaming), and another time
        // (streaming)

        int requestId1;
        requestId1 = mCameraUser.submitRequest(request, /* streaming */true);
        assertTrue("Request IDs should be non-negative", requestId1 >= 0);

        int requestIdStreaming = mCameraUser.submitRequest(request, /* streaming */false);
        assertTrue("Request IDs should be non-negative", requestIdStreaming >= 0);
        assertNotSame("Request IDs should be unique for multiple requests", requestId1,
                requestIdStreaming);

        status = mCameraUser.cancelRequest(-1);
        assertEquals("Invalid request IDs should not be cancellable",
                CameraBinderTestUtils.BAD_VALUE, status);

        status = mCameraUser.cancelRequest(requestId1);
        assertEquals("Non-streaming request IDs should not be cancellable",
                CameraBinderTestUtils.BAD_VALUE, status);

        status = mCameraUser.cancelRequest(requestIdStreaming);
        assertEquals("Streaming request IDs should be cancellable", CameraBinderTestUtils.NO_ERROR,
                status);

        surface.release();
        request.close();
        metadata.close();
    }

    @SmallTest
    public void testCameraInfo() throws RemoteException {
        CameraMetadata info = new CameraMetadata();

        int status = mCameraUser.getCameraInfo(/*out*/info);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);

        assertFalse(info.isEmpty());
        assertNotNull(info.get(CameraPropertiesKeys.Scaler.AVAILABLE_FORMATS));
    }

}
