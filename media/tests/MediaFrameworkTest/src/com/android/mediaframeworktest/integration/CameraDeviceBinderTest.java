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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.AttributionSourceState;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.ICameraService;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.utils.SubmitInfo;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.util.Log;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.mediaframeworktest.MediaFrameworkIntegrationTestRunner;
import com.android.mediaframeworktest.helpers.CameraTestUtils;

import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

public class CameraDeviceBinderTest extends AndroidTestCase {
    private static String TAG = "CameraDeviceBinderTest";
    // Number of streaming callbacks need to check.
    private static int NUM_CALLBACKS_CHECKED = 10;
    // Wait for capture result timeout value: 1500ms
    private final static int WAIT_FOR_COMPLETE_TIMEOUT_MS = 1500;
    // Wait for flush timeout value: 1000ms
    private final static int WAIT_FOR_FLUSH_TIMEOUT_MS = 1000;
    // Wait for idle timeout value: 2000ms
    private final static int WAIT_FOR_IDLE_TIMEOUT_MS = 2000;
    // Wait while camera device starts working on requests
    private final static int WAIT_FOR_WORK_MS = 300;
    // Default size is VGA, which is mandatory camera supported image size by CDD.
    private static final int DEFAULT_IMAGE_WIDTH = 640;
    private static final int DEFAULT_IMAGE_HEIGHT = 480;
    private static final int MAX_NUM_IMAGES = 5;

    private String mCameraId;
    private ICameraDeviceUser mCameraUser;
    private CameraBinderTestUtils mUtils;
    private ICameraDeviceCallbacks.Stub mMockCb;
    private Surface mSurface;
    private OutputConfiguration mOutputConfiguration;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    ImageReader mImageReader;

    public CameraDeviceBinderTest() {
    }

    private class ImageDropperListener implements ImageReader.OnImageAvailableListener {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireNextImage();
            if (image != null) image.close();
        }
    }

    public class DummyCameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

        /*
         * (non-Javadoc)
         * @see
         * android.hardware.camera2.ICameraDeviceCallbacks#onDeviceError(int,
         * android.hardware.camera2.CaptureResultExtras)
         */
        public void onDeviceError(int errorCode, CaptureResultExtras resultExtras)
                throws RemoteException {
            // TODO Auto-generated method stub

        }

        /*
         * (non-Javadoc)
         * @see android.hardware.camera2.ICameraDeviceCallbacks#onDeviceIdle()
         */
        public void onDeviceIdle() throws RemoteException {
            // TODO Auto-generated method stub

        }

        /*
         * (non-Javadoc)
         * @see
         * android.hardware.camera2.ICameraDeviceCallbacks#onCaptureStarted(
         * android.hardware.camera2.CaptureResultExtras, long)
         */
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
        public void onResultReceived(CameraMetadataNative result, CaptureResultExtras resultExtras,
                PhysicalCaptureResultInfo physicalResults[]) throws RemoteException {
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

    class IsMetadataNotEmpty implements ArgumentMatcher<CameraMetadataNative> {
        @Override
        public boolean matches(CameraMetadataNative obj) {
            return !obj.isEmpty();
        }
    }

    private void createDefaultSurface() {
        mImageReader =
                ImageReader.newInstance(DEFAULT_IMAGE_WIDTH,
                        DEFAULT_IMAGE_HEIGHT,
                        ImageFormat.YUV_420_888,
                        MAX_NUM_IMAGES);
        mImageReader.setOnImageAvailableListener(new ImageDropperListener(), mHandler);
        mSurface = mImageReader.getSurface();
        mOutputConfiguration = new OutputConfiguration(mSurface);
    }

    private CaptureRequest.Builder createDefaultBuilder(boolean needStream) throws Exception {
        CameraMetadataNative metadata = null;
        assertTrue(metadata.isEmpty());

        metadata = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW);
        assertFalse(metadata.isEmpty());

        CaptureRequest.Builder request = new CaptureRequest.Builder(metadata, /*reprocess*/false,
                CameraCaptureSession.SESSION_ID_NONE, mCameraId, /*physicalCameraIdSet*/null);
        assertFalse(request.isEmpty());
        assertFalse(metadata.isEmpty());
        if (needStream) {
            int streamId = mCameraUser.createStream(mOutputConfiguration);
            assertEquals(0, streamId);
            request.addTarget(mSurface);
        }
        return request;
    }

    private SubmitInfo submitCameraRequest(CaptureRequest request, boolean streaming) throws Exception {
        SubmitInfo requestInfo = mCameraUser.submitRequest(request, streaming);
        assertTrue(
                "Request IDs should be non-negative (expected: >= 0, actual: " +
                requestInfo.getRequestId() + ")",
                requestInfo.getRequestId() >= 0);
        return requestInfo;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        /**
         * Workaround for mockito and JB-MR2 incompatibility
         *
         * Avoid java.lang.IllegalArgumentException: dexcache == null
         * https://code.google.com/p/dexmaker/issues/detail?id=2
         */
        System.setProperty("dexmaker.dexcache", getContext().getCacheDir().toString());
        mUtils = new CameraBinderTestUtils(getContext());

        // This cannot be set in the constructor, since the onCreate isn't
        // called yet
        mCameraId = MediaFrameworkIntegrationTestRunner.mCameraId;

        ICameraDeviceCallbacks.Stub dummyCallbacks = new DummyCameraDeviceCallbacks();

        String clientPackageName = getContext().getPackageName();
        String clientAttributionTag = getContext().getAttributionTag();

        mMockCb = spy(dummyCallbacks);

        AttributionSourceState clientAttribution = CameraTestUtils.getClientAttribution(mContext);
        clientAttribution.deviceId = DEVICE_ID_DEFAULT;
        clientAttribution.uid = ICameraService.USE_CALLING_UID;

        mCameraUser = mUtils.getCameraService().connectDevice(mMockCb, mCameraId,
                clientPackageName, clientAttributionTag,
                /*oomScoreOffset*/0, getContext().getApplicationInfo().targetSdkVersion,
                ICameraService.ROTATION_OVERRIDE_NONE, clientAttribution, DEVICE_POLICY_DEFAULT);
        assertNotNull(String.format("Camera %s was null", mCameraId), mCameraUser);
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        createDefaultSurface();

        Log.v(TAG, String.format("Camera %s connected", mCameraId));
    }

    @Override
    protected void tearDown() throws Exception {
        mCameraUser.disconnect();
        mCameraUser = null;
        mSurface.release();
        mImageReader.close();
        mHandlerThread.quitSafely();
    }

    @SmallTest
    public void testCreateDefaultRequest() throws Exception {
        CameraMetadataNative metadata = null;
        assertTrue(metadata.isEmpty());

        metadata = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW);
        assertFalse(metadata.isEmpty());
    }

    @SmallTest
    public void testCreateStream() throws Exception {
        int streamId = mCameraUser.createStream(mOutputConfiguration);
        assertEquals(0, streamId);

        try {
            mCameraUser.createStream(mOutputConfiguration);
            fail("Creating same stream twice");
        } catch (ServiceSpecificException e) {
            assertEquals("Creating same stream twice",
                    e.errorCode, ICameraService.ERROR_ALREADY_EXISTS);
        }

        mCameraUser.deleteStream(streamId);
    }

    @SmallTest
    public void testDeleteInvalidStream() throws Exception {
        int[] badStreams = { -1, 0, 1, 0xC0FFEE };
        for (int badStream : badStreams) {
            try {
                mCameraUser.deleteStream(badStream);
                fail("Allowed bad stream delete");
            } catch (ServiceSpecificException e) {
                assertEquals(e.errorCode, ICameraService.ERROR_ILLEGAL_ARGUMENT);
            }
        }
    }

    @SmallTest
    public void testCreateStreamTwo() throws Exception {
        // Create first stream
        int streamId = mCameraUser.createStream(mOutputConfiguration);
        assertEquals(0, streamId);

        try {
            mCameraUser.createStream(mOutputConfiguration);
            fail("Created same stream twice");
        } catch (ServiceSpecificException e) {
            assertEquals("Created same stream twice",
                    ICameraService.ERROR_ALREADY_EXISTS, e.errorCode);
        }

        // Create second stream with a different surface.
        SurfaceTexture surfaceTexture = new SurfaceTexture(/* ignored */0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        Surface surface2 = new Surface(surfaceTexture);
        OutputConfiguration output2 = new OutputConfiguration(surface2);

        int streamId2 = mCameraUser.createStream(output2);
        assertEquals(1, streamId2);

        // Clean up streams
        mCameraUser.deleteStream(streamId);
        mCameraUser.deleteStream(streamId2);
    }

    @SmallTest
    public void testSubmitBadRequest() throws Exception {
        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */false);
        CaptureRequest request1 = builder.build();
        try {
            SubmitInfo requestInfo = mCameraUser.submitRequest(request1, /* streaming */false);
            fail("Exception expected");
        } catch(ServiceSpecificException e) {
            assertEquals("Expected submitRequest to throw ServiceSpecificException with BAD_VALUE " +
                    "since we had 0 surface targets set.", ICameraService.ERROR_ILLEGAL_ARGUMENT,
                    e.errorCode);
        }

        builder.addTarget(mSurface);
        CaptureRequest request2 = builder.build();
        try {
            SubmitInfo requestInfo = mCameraUser.submitRequest(request2, /* streaming */false);
            fail("Exception expected");
        } catch(ServiceSpecificException e) {
            assertEquals("Expected submitRequest to throw ILLEGAL_ARGUMENT " +
                    "ServiceSpecificException since the target wasn't registered with createStream.",
                    ICameraService.ERROR_ILLEGAL_ARGUMENT, e.errorCode);
        }
    }

    @SmallTest
    public void testSubmitGoodRequest() throws Exception {
        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */true);
        CaptureRequest request = builder.build();

        // Submit valid request twice.
        SubmitInfo requestInfo1 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo2 = submitCameraRequest(request, /* streaming */false);
        assertNotSame("Request IDs should be unique for multiple requests",
                requestInfo1.getRequestId(), requestInfo2.getRequestId());
    }

    @SmallTest
    public void testSubmitStreamingRequest() throws Exception {
        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */true);

        CaptureRequest request = builder.build();

        // Submit valid request once (non-streaming), and another time
        // (streaming)
        SubmitInfo requestInfo1 = submitCameraRequest(request, /* streaming */false);

        SubmitInfo requestInfoStreaming = submitCameraRequest(request, /* streaming */true);
        assertNotSame("Request IDs should be unique for multiple requests",
                requestInfo1.getRequestId(),
                requestInfoStreaming.getRequestId());

        try {
            long lastFrameNumber = mCameraUser.cancelRequest(-1);
            fail("Expected exception");
        } catch (ServiceSpecificException e) {
            assertEquals("Invalid request IDs should not be cancellable",
                    ICameraService.ERROR_ILLEGAL_ARGUMENT, e.errorCode);
        }

        try {
            long lastFrameNumber = mCameraUser.cancelRequest(requestInfo1.getRequestId());
            fail("Expected exception");
        } catch (ServiceSpecificException e) {
            assertEquals("Non-streaming request IDs should not be cancellable",
                    ICameraService.ERROR_ILLEGAL_ARGUMENT, e.errorCode);
        }

        long lastFrameNumber = mCameraUser.cancelRequest(requestInfoStreaming.getRequestId());
    }

    @SmallTest
    public void testCameraInfo() throws RemoteException {
        CameraMetadataNative info = mCameraUser.getCameraInfo();

        assertFalse(info.isEmpty());
        assertNotNull(info.get(CameraCharacteristics.SCALER_AVAILABLE_FORMATS));
    }

    @SmallTest
    public void testCameraCharacteristics() throws RemoteException {
        AttributionSourceState clientAttribution = CameraTestUtils.getClientAttribution(mContext);
        clientAttribution.deviceId = DEVICE_ID_DEFAULT;

        CameraMetadataNative info = mUtils.getCameraService().getCameraCharacteristics(mCameraId,
                getContext().getApplicationInfo().targetSdkVersion,
                ICameraService.ROTATION_OVERRIDE_NONE,
                clientAttribution, DEVICE_POLICY_DEFAULT);

        assertFalse(info.isEmpty());
        assertNotNull(info.get(CameraCharacteristics.SCALER_AVAILABLE_FORMATS));
    }

    @SmallTest
    public void testWaitUntilIdle() throws Exception {
        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */true);
        SubmitInfo requestInfoStreaming = submitCameraRequest(builder.build(), /* streaming */true);

        // Test Bad case first: waitUntilIdle when there is active repeating request
        try {
            mCameraUser.waitUntilIdle();
        } catch (ServiceSpecificException e) {
            assertEquals("waitUntilIdle is invalid operation when there is active repeating request",
                    ICameraService.ERROR_INVALID_OPERATION, e.errorCode);
        }

        // Test good case, waitUntilIdle when there is no active repeating request
        long lastFrameNumber = mCameraUser.cancelRequest(requestInfoStreaming.getRequestId());
        mCameraUser.waitUntilIdle();
    }

    @SmallTest
    public void testCaptureResultCallbacks() throws Exception {
        IsMetadataNotEmpty matcher = new IsMetadataNotEmpty();
        CaptureRequest request = createDefaultBuilder(/* needStream */true).build();

        // Test both single request and streaming request.
        verify(mMockCb, timeout(WAIT_FOR_COMPLETE_TIMEOUT_MS).times(1)).onResultReceived(
                argThat(matcher),
                any(CaptureResultExtras.class),
                any(PhysicalCaptureResultInfo[].class));

        verify(mMockCb, timeout(WAIT_FOR_COMPLETE_TIMEOUT_MS).atLeast(NUM_CALLBACKS_CHECKED))
                .onResultReceived(
                        argThat(matcher),
                        any(CaptureResultExtras.class),
                        any(PhysicalCaptureResultInfo[].class));
    }

    @SmallTest
    public void testCaptureStartedCallbacks() throws Exception {
        CaptureRequest request = createDefaultBuilder(/* needStream */true).build();

        ArgumentCaptor<Long> timestamps = ArgumentCaptor.forClass(Long.class);

        // Test both single request and streaming request.
        SubmitInfo requestInfo1 = submitCameraRequest(request, /* streaming */false);
        verify(mMockCb, timeout(WAIT_FOR_COMPLETE_TIMEOUT_MS).times(1)).onCaptureStarted(
                any(CaptureResultExtras.class),
                anyLong());

        SubmitInfo streamingInfo = submitCameraRequest(request, /* streaming */true);
        verify(mMockCb, timeout(WAIT_FOR_COMPLETE_TIMEOUT_MS).atLeast(NUM_CALLBACKS_CHECKED))
                .onCaptureStarted(
                        any(CaptureResultExtras.class),
                        timestamps.capture());

        long timestamp = 0; // All timestamps should be larger than 0.
        for (Long nextTimestamp : timestamps.getAllValues()) {
            Log.v(TAG, "next t: " + nextTimestamp + " current t: " + timestamp);
            assertTrue("Captures are out of order", timestamp < nextTimestamp);
            timestamp = nextTimestamp;
        }
    }

    @SmallTest
    public void testIdleCallback() throws Exception {
        int status;
        CaptureRequest request = createDefaultBuilder(/* needStream */true).build();

        // Try streaming
        SubmitInfo streamingInfo = submitCameraRequest(request, /* streaming */true);

        // Wait a bit to fill up the queue
        SystemClock.sleep(WAIT_FOR_WORK_MS);

        // Cancel and make sure we eventually quiesce
        long lastFrameNumber = mCameraUser.cancelRequest(streamingInfo.getRequestId());

        verify(mMockCb, timeout(WAIT_FOR_IDLE_TIMEOUT_MS).times(1)).onDeviceIdle();

        // Submit a few capture requests
        SubmitInfo requestInfo1 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo2 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo3 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo4 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo5 = submitCameraRequest(request, /* streaming */false);

        // And wait for more idle
        verify(mMockCb, timeout(WAIT_FOR_IDLE_TIMEOUT_MS).times(2)).onDeviceIdle();
    }

    @SmallTest
    public void testFlush() throws Exception {
        int status;

        // Initial flush should work
        long lastFrameNumber = mCameraUser.flush();

        // Then set up a stream
        CaptureRequest request = createDefaultBuilder(/* needStream */true).build();

        // Flush should still be a no-op, really
        lastFrameNumber = mCameraUser.flush();

        // Submit a few capture requests
        SubmitInfo requestInfo1 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo2 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo3 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo4 = submitCameraRequest(request, /* streaming */false);
        SubmitInfo requestInfo5 = submitCameraRequest(request, /* streaming */false);

        // Then flush and wait for idle
        lastFrameNumber = mCameraUser.flush();

        verify(mMockCb, timeout(WAIT_FOR_FLUSH_TIMEOUT_MS).times(1)).onDeviceIdle();

        // Now a streaming request
        SubmitInfo streamingInfo = submitCameraRequest(request, /* streaming */true);

        // Wait a bit to fill up the queue
        SystemClock.sleep(WAIT_FOR_WORK_MS);

        // Then flush and wait for the idle callback
        lastFrameNumber = mCameraUser.flush();

        verify(mMockCb, timeout(WAIT_FOR_FLUSH_TIMEOUT_MS).times(2)).onDeviceIdle();

        // TODO: When errors are hooked up, count that errors + successful
        // requests equal to 5.
    }
}
