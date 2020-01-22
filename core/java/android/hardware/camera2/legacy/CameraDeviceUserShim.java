/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.hardware.ICameraService;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.ICameraOfflineSession;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.SubmitInfo;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import static android.system.OsConstants.EACCES;
import static android.system.OsConstants.ENODEV;

/**
 * Compatibility implementation of the Camera2 API binder interface.
 *
 * <p>
 * This is intended to be called from the same process as client
 * {@link android.hardware.camera2.CameraDevice}, and wraps a
 * {@link android.hardware.camera2.legacy.LegacyCameraDevice} that emulates Camera2 service using
 * the Camera1 API.
 * </p>
 *
 * <p>
 * Keep up to date with ICameraDeviceUser.aidl.
 * </p>
 */
@SuppressWarnings("deprecation")
public class CameraDeviceUserShim implements ICameraDeviceUser {
    private static final String TAG = "CameraDeviceUserShim";

    private static final boolean DEBUG = false;
    private static final int OPEN_CAMERA_TIMEOUT_MS = 5000; // 5 sec (same as api1 cts timeout)

    private final LegacyCameraDevice mLegacyDevice;

    private final Object mConfigureLock = new Object();
    private int mSurfaceIdCounter;
    private boolean mConfiguring;
    private final SparseArray<Surface> mSurfaces;
    private final CameraCharacteristics mCameraCharacteristics;
    private final CameraLooper mCameraInit;
    private final CameraCallbackThread mCameraCallbacks;


    protected CameraDeviceUserShim(int cameraId, LegacyCameraDevice legacyCamera,
            CameraCharacteristics characteristics, CameraLooper cameraInit,
            CameraCallbackThread cameraCallbacks) {
        mLegacyDevice = legacyCamera;
        mConfiguring = false;
        mSurfaces = new SparseArray<Surface>();
        mCameraCharacteristics = characteristics;
        mCameraInit = cameraInit;
        mCameraCallbacks = cameraCallbacks;

        mSurfaceIdCounter = 0;
    }

    private static int translateErrorsFromCamera1(int errorCode) {
        if (errorCode == -EACCES) {
            return ICameraService.ERROR_PERMISSION_DENIED;
        }

        return errorCode;
    }

    /**
     * Create a separate looper/thread for the camera to run on; open the camera.
     *
     * <p>Since the camera automatically latches on to the current thread's looper,
     * it's important that we have our own thread with our own looper to guarantee
     * that the camera callbacks get correctly posted to our own thread.</p>
     */
    private static class CameraLooper implements Runnable, AutoCloseable {
        private final int mCameraId;
        private Looper mLooper;
        private volatile int mInitErrors;
        private final Camera mCamera = Camera.openUninitialized();
        private final ConditionVariable mStartDone = new ConditionVariable();
        private final Thread mThread;

        /**
         * Spin up a new thread, immediately open the camera in the background.
         *
         * <p>Use {@link #waitForOpen} to block until the camera is finished opening.</p>
         *
         * @param cameraId numeric camera Id
         *
         * @see #waitForOpen
         */
        public CameraLooper(int cameraId) {
            mCameraId = cameraId;

            mThread = new Thread(this, "LegacyCameraLooper");
            mThread.start();
        }

        public Camera getCamera() {
            return mCamera;
        }

        @Override
        public void run() {
            // Set up a looper to be used by camera.
            Looper.prepare();

            // Save the looper so that we can terminate this thread
            // after we are done with it.
            mLooper = Looper.myLooper();
            mInitErrors = mCamera.cameraInitUnspecified(mCameraId);
            mStartDone.open();
            Looper.loop();  // Blocks forever until #close is called.
        }

        /**
         * Quit the looper safely; then join until the thread shuts down.
         */
        @Override
        public void close() {
            if (mLooper == null) {
                return;
            }

            mLooper.quitSafely();
            try {
                mThread.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }

            mLooper = null;
        }

        /**
         * Block until the camera opens; then return its initialization error code (if any).
         *
         * @param timeoutMs timeout in milliseconds
         *
         * @return int error code
         *
         * @throws ServiceSpecificException if the camera open times out with ({@code CAMERA_ERROR})
         */
        public int waitForOpen(int timeoutMs) {
            // Block until the camera is open asynchronously
            if (!mStartDone.block(timeoutMs)) {
                Log.e(TAG, "waitForOpen - Camera failed to open after timeout of "
                        + OPEN_CAMERA_TIMEOUT_MS + " ms");
                try {
                    mCamera.release();
                } catch (RuntimeException e) {
                    Log.e(TAG, "connectBinderShim - Failed to release camera after timeout ", e);
                }

                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION);
            }

            return mInitErrors;
        }
    }

    /**
     * A thread to process callbacks to send back to the camera client.
     *
     * <p>This effectively emulates one-way binder semantics when in the same process as the
     * callee.</p>
     */
    private static class CameraCallbackThread implements ICameraDeviceCallbacks {
        private static final int CAMERA_ERROR = 0;
        private static final int CAMERA_IDLE = 1;
        private static final int CAPTURE_STARTED = 2;
        private static final int RESULT_RECEIVED = 3;
        private static final int PREPARED = 4;
        private static final int REPEATING_REQUEST_ERROR = 5;
        private static final int REQUEST_QUEUE_EMPTY = 6;

        private final HandlerThread mHandlerThread;
        private Handler mHandler;

        private final ICameraDeviceCallbacks mCallbacks;

        public CameraCallbackThread(ICameraDeviceCallbacks callbacks) {
            mCallbacks = callbacks;

            mHandlerThread = new HandlerThread("LegacyCameraCallback");
            mHandlerThread.start();
        }

        public void close() {
            mHandlerThread.quitSafely();
        }

        @Override
        public void onDeviceError(final int errorCode, final CaptureResultExtras resultExtras) {
            Message msg = getHandler().obtainMessage(CAMERA_ERROR,
                /*arg1*/ errorCode, /*arg2*/ 0,
                /*obj*/ resultExtras);
            getHandler().sendMessage(msg);
        }

        @Override
        public void onDeviceIdle() {
            Message msg = getHandler().obtainMessage(CAMERA_IDLE);
            getHandler().sendMessage(msg);
        }

        @Override
        public void onCaptureStarted(final CaptureResultExtras resultExtras, final long timestamp) {
            Message msg = getHandler().obtainMessage(CAPTURE_STARTED,
                    /*arg1*/ (int) (timestamp & 0xFFFFFFFFL),
                    /*arg2*/ (int) ( (timestamp >> 32) & 0xFFFFFFFFL),
                    /*obj*/ resultExtras);
            getHandler().sendMessage(msg);
        }

        @Override
        public void onResultReceived(final CameraMetadataNative result,
                final CaptureResultExtras resultExtras,
                PhysicalCaptureResultInfo physicalResults[]) {
            Object[] resultArray = new Object[] { result, resultExtras };
            Message msg = getHandler().obtainMessage(RESULT_RECEIVED,
                    /*obj*/ resultArray);
            getHandler().sendMessage(msg);
        }

        @Override
        public void onPrepared(int streamId) {
            Message msg = getHandler().obtainMessage(PREPARED,
                    /*arg1*/ streamId, /*arg2*/ 0);
            getHandler().sendMessage(msg);
        }

        @Override
        public void onRepeatingRequestError(long lastFrameNumber, int repeatingRequestId) {
            Object[] objArray = new Object[] { lastFrameNumber, repeatingRequestId };
            Message msg = getHandler().obtainMessage(REPEATING_REQUEST_ERROR,
                    /*obj*/ objArray);
            getHandler().sendMessage(msg);
        }

        @Override
        public void onRequestQueueEmpty() {
            Message msg = getHandler().obtainMessage(REQUEST_QUEUE_EMPTY,
                    /* arg1 */ 0, /* arg2 */ 0);
            getHandler().sendMessage(msg);
        }

        @Override
        public IBinder asBinder() {
            // This is solely intended to be used for in-process binding.
            return null;
        }

        private Handler getHandler() {
            if (mHandler == null) {
                mHandler = new CallbackHandler(mHandlerThread.getLooper());
            }
            return mHandler;
        }

        private class CallbackHandler extends Handler {
            public CallbackHandler(Looper l) {
                super(l);
            }

            @Override
            public void handleMessage(Message msg) {
                try {
                    switch (msg.what) {
                        case CAMERA_ERROR: {
                            int errorCode = msg.arg1;
                            CaptureResultExtras resultExtras = (CaptureResultExtras) msg.obj;
                            mCallbacks.onDeviceError(errorCode, resultExtras);
                            break;
                        }
                        case CAMERA_IDLE:
                            mCallbacks.onDeviceIdle();
                            break;
                        case CAPTURE_STARTED: {
                            long timestamp = msg.arg2 & 0xFFFFFFFFL;
                            timestamp = (timestamp << 32) | (msg.arg1 & 0xFFFFFFFFL);
                            CaptureResultExtras resultExtras = (CaptureResultExtras) msg.obj;
                            mCallbacks.onCaptureStarted(resultExtras, timestamp);
                            break;
                        }
                        case RESULT_RECEIVED: {
                            Object[] resultArray = (Object[]) msg.obj;
                            CameraMetadataNative result = (CameraMetadataNative) resultArray[0];
                            CaptureResultExtras resultExtras = (CaptureResultExtras) resultArray[1];
                            mCallbacks.onResultReceived(result, resultExtras,
                                    new PhysicalCaptureResultInfo[0]);
                            break;
                        }
                        case PREPARED: {
                            int streamId = msg.arg1;
                            mCallbacks.onPrepared(streamId);
                            break;
                        }
                        case REPEATING_REQUEST_ERROR: {
                            Object[] objArray = (Object[]) msg.obj;
                            long lastFrameNumber = (Long) objArray[0];
                            int repeatingRequestId = (Integer) objArray[1];
                            mCallbacks.onRepeatingRequestError(lastFrameNumber, repeatingRequestId);
                            break;
                        }
                        case REQUEST_QUEUE_EMPTY: {
                            mCallbacks.onRequestQueueEmpty();
                            break;
                        }
                        default:
                            throw new IllegalArgumentException(
                                "Unknown callback message " + msg.what);
                    }
                } catch (RemoteException e) {
                    throw new IllegalStateException(
                        "Received remote exception during camera callback " + msg.what, e);
                }
            }
        }
    }

    public static CameraDeviceUserShim connectBinderShim(ICameraDeviceCallbacks callbacks,
                                                         int cameraId, Size displaySize) {
        if (DEBUG) {
            Log.d(TAG, "Opening shim Camera device");
        }

        /*
         * Put the camera open on a separate thread with its own looper; otherwise
         * if the main thread is used then the callbacks might never get delivered
         * (e.g. in CTS which run its own default looper only after tests)
         */

        CameraLooper init = new CameraLooper(cameraId);

        CameraCallbackThread threadCallbacks = new CameraCallbackThread(callbacks);

        // TODO: Make this async instead of blocking
        int initErrors = init.waitForOpen(OPEN_CAMERA_TIMEOUT_MS);
        Camera legacyCamera = init.getCamera();

        // Check errors old HAL initialization
        LegacyExceptionUtils.throwOnServiceError(initErrors);

        // Disable shutter sounds (this will work unconditionally) for api2 clients
        legacyCamera.disableShutterSound();

        CameraInfo info = new CameraInfo();
        Camera.getCameraInfo(cameraId, info);

        Camera.Parameters legacyParameters = null;
        try {
            legacyParameters = legacyCamera.getParameters();
        } catch (RuntimeException e) {
            throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION,
                    "Unable to get initial parameters: " + e.getMessage());
        }

        CameraCharacteristics characteristics =
                LegacyMetadataMapper.createCharacteristics(legacyParameters, info, cameraId,
                        displaySize);
        LegacyCameraDevice device = new LegacyCameraDevice(
                cameraId, legacyCamera, characteristics, threadCallbacks);
        return new CameraDeviceUserShim(cameraId, device, characteristics, init, threadCallbacks);
    }

    @Override
    public void disconnect() {
        if (DEBUG) {
            Log.d(TAG, "disconnect called.");
        }

        if (mLegacyDevice.isClosed()) {
            Log.w(TAG, "Cannot disconnect, device has already been closed.");
        }

        try {
            mLegacyDevice.close();
        } finally {
            mCameraInit.close();
            mCameraCallbacks.close();
        }
    }

    @Override
    public SubmitInfo submitRequest(CaptureRequest request, boolean streaming) {
        if (DEBUG) {
            Log.d(TAG, "submitRequest called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot submit request, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (mConfiguring) {
                String err = "Cannot submit request, configuration change in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
        }
        return mLegacyDevice.submitRequest(request, streaming);
    }

    @Override
    public SubmitInfo submitRequestList(CaptureRequest[] request, boolean streaming) {
        if (DEBUG) {
            Log.d(TAG, "submitRequestList called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot submit request list, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (mConfiguring) {
                String err = "Cannot submit request, configuration change in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
        }
        return mLegacyDevice.submitRequestList(request, streaming);
    }

    @Override
    public long cancelRequest(int requestId) {
        if (DEBUG) {
            Log.d(TAG, "cancelRequest called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot cancel request, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (mConfiguring) {
                String err = "Cannot cancel request, configuration change in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
        }
        return mLegacyDevice.cancelRequest(requestId);
    }

    @Override
    public boolean isSessionConfigurationSupported(SessionConfiguration sessionConfig) {
        if (sessionConfig.getSessionType() != SessionConfiguration.SESSION_REGULAR) {
            Log.e(TAG, "Session type: " + sessionConfig.getSessionType() + " is different from " +
                    " regular. Legacy devices support only regular session types!");
            return false;
        }

        if (sessionConfig.getInputConfiguration() != null) {
            Log.e(TAG, "Input configuration present, legacy devices do not support this feature!");
            return false;
        }

        List<OutputConfiguration> outputConfigs = sessionConfig.getOutputConfigurations();
        if (outputConfigs.isEmpty()) {
            Log.e(TAG, "Empty output configuration list!");
            return false;
        }

        SparseArray<Surface> surfaces = new SparseArray<Surface>(outputConfigs.size());
        int idx = 0;
        for (OutputConfiguration outputConfig : outputConfigs) {
            List<Surface> surfaceList = outputConfig.getSurfaces();
            if (surfaceList.isEmpty() || (surfaceList.size() > 1)) {
                Log.e(TAG, "Legacy devices do not support deferred or shared surfaces!");
                return false;
            }

            surfaces.put(idx++, outputConfig.getSurface());
        }

        int ret = mLegacyDevice.configureOutputs(surfaces, /*validateSurfacesOnly*/true);

        return ret == LegacyExceptionUtils.NO_ERROR;
    }

    @Override
    public void beginConfigure() {
        if (DEBUG) {
            Log.d(TAG, "beginConfigure called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot begin configure, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (mConfiguring) {
                String err = "Cannot begin configure, configuration change already in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
            mConfiguring = true;
        }
    }

    @Override
    public int[] endConfigure(int operatingMode, CameraMetadataNative sessionParams) {
        if (DEBUG) {
            Log.d(TAG, "endConfigure called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot end configure, device has been closed.";
            Log.e(TAG, err);
            synchronized(mConfigureLock) {
                mConfiguring = false;
            }
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        if (operatingMode != ICameraDeviceUser.NORMAL_MODE) {
            String err = "LEGACY devices do not support this operating mode";
            Log.e(TAG, err);
            synchronized(mConfigureLock) {
                mConfiguring = false;
            }
            throw new ServiceSpecificException(ICameraService.ERROR_ILLEGAL_ARGUMENT, err);
        }

        SparseArray<Surface> surfaces = null;
        synchronized(mConfigureLock) {
            if (!mConfiguring) {
                String err = "Cannot end configure, no configuration change in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
            if (mSurfaces != null) {
                surfaces = mSurfaces.clone();
            }
            mConfiguring = false;
        }
        mLegacyDevice.configureOutputs(surfaces);

        return new int[0]; // Offline mode is not supported
    }

    @Override
    public void deleteStream(int streamId) {
        if (DEBUG) {
            Log.d(TAG, "deleteStream called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot delete stream, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (!mConfiguring) {
                String err = "Cannot delete stream, no configuration change in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
            int index = mSurfaces.indexOfKey(streamId);
            if (index < 0) {
                String err = "Cannot delete stream, stream id " + streamId + " doesn't exist.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_ILLEGAL_ARGUMENT, err);
            }
            mSurfaces.removeAt(index);
        }
    }

    @Override
    public int createStream(OutputConfiguration outputConfiguration) {
        if (DEBUG) {
            Log.d(TAG, "createStream called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot create stream, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (!mConfiguring) {
                String err = "Cannot create stream, beginConfigure hasn't been called yet.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
            if (outputConfiguration.getRotation() != OutputConfiguration.ROTATION_0) {
                String err = "Cannot create stream, stream rotation is not supported.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_ILLEGAL_ARGUMENT, err);
            }
            int id = ++mSurfaceIdCounter;
            mSurfaces.put(id, outputConfiguration.getSurface());
            return id;
        }
    }

    @Override
    public void finalizeOutputConfigurations(int steamId, OutputConfiguration config) {
        String err = "Finalizing output configuration is not supported on legacy devices";
        Log.e(TAG, err);
        throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
    }

    @Override
    public int createInputStream(int width, int height, int format) {
        String err = "Creating input stream is not supported on legacy devices";
        Log.e(TAG, err);
        throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
    }

    @Override
    public Surface getInputSurface() {
        String err = "Getting input surface is not supported on legacy devices";
        Log.e(TAG, err);
        throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
    }

    @Override
    public CameraMetadataNative createDefaultRequest(int templateId) {
        if (DEBUG) {
            Log.d(TAG, "createDefaultRequest called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot create default request, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        CameraMetadataNative template;
        try {
            template =
                    LegacyMetadataMapper.createRequestTemplate(mCameraCharacteristics, templateId);
        } catch (IllegalArgumentException e) {
            String err = "createDefaultRequest - invalid templateId specified";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_ILLEGAL_ARGUMENT, err);
        }

        return template;
    }

    @Override
    public CameraMetadataNative getCameraInfo() {
        if (DEBUG) {
            Log.d(TAG, "getCameraInfo called.");
        }
        // TODO: implement getCameraInfo.
        Log.e(TAG, "getCameraInfo unimplemented.");
        return null;
    }

    @Override
    public void updateOutputConfiguration(int streamId, OutputConfiguration config) {
        // TODO: b/63912484 implement updateOutputConfiguration.
    }

    @Override
    public void waitUntilIdle() throws RemoteException {
        if (DEBUG) {
            Log.d(TAG, "waitUntilIdle called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot wait until idle, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (mConfiguring) {
                String err = "Cannot wait until idle, configuration change in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
        }
        mLegacyDevice.waitUntilIdle();
    }

    @Override
    public long flush() {
        if (DEBUG) {
            Log.d(TAG, "flush called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot flush, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        synchronized(mConfigureLock) {
            if (mConfiguring) {
                String err = "Cannot flush, configuration change in progress.";
                Log.e(TAG, err);
                throw new ServiceSpecificException(ICameraService.ERROR_INVALID_OPERATION, err);
            }
        }
        return mLegacyDevice.flush();
    }

    public void prepare(int streamId) {
        if (DEBUG) {
            Log.d(TAG, "prepare called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot prepare stream, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        // LEGACY doesn't support actual prepare, just signal success right away
        mCameraCallbacks.onPrepared(streamId);
    }

    public void prepare2(int maxCount, int streamId) {
        // We don't support this in LEGACY mode.
        prepare(streamId);
    }

    public void tearDown(int streamId) {
        if (DEBUG) {
            Log.d(TAG, "tearDown called.");
        }
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot tear down stream, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        // LEGACY doesn't support actual teardown, so just a no-op
    }

    @Override
    public void setCameraAudioRestriction(int mode) {
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot set camera audio restriction, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        mLegacyDevice.setAudioRestriction(mode);
    }

    @Override
    public int getGlobalAudioRestriction() {
        if (mLegacyDevice.isClosed()) {
            String err = "Cannot set camera audio restriction, device has been closed.";
            Log.e(TAG, err);
            throw new ServiceSpecificException(ICameraService.ERROR_DISCONNECTED, err);
        }

        return mLegacyDevice.getAudioRestriction();
    }

    @Override
    public ICameraOfflineSession switchToOffline(ICameraDeviceCallbacks cbs,
            int[] offlineOutputIds) {
        throw new UnsupportedOperationException("Legacy device does not support offline mode");
    }

    @Override
    public IBinder asBinder() {
        // This is solely intended to be used for in-process binding.
        return null;
    }
}
