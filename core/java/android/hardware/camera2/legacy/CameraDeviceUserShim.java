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

import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.utils.LongParcelable;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

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

    private static final boolean DEBUG = Log.isLoggable(LegacyCameraDevice.DEBUG_PROP, Log.DEBUG);
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

            mThread = new Thread(this);
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
         * @throws CameraRuntimeException if the camera open times out with ({@code CAMERA_ERROR})
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

                throw new CameraRuntimeException(CameraAccessException.CAMERA_ERROR);
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
        public void onCameraError(final int errorCode, final CaptureResultExtras resultExtras) {
            Message msg = getHandler().obtainMessage(CAMERA_ERROR,
                /*arg1*/ errorCode, /*arg2*/ 0,
                /*obj*/ resultExtras);
            getHandler().sendMessage(msg);
        }

        @Override
        public void onCameraIdle() {
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
                final CaptureResultExtras resultExtras) {
            Object[] resultArray = new Object[] { result, resultExtras };
            Message msg = getHandler().obtainMessage(RESULT_RECEIVED,
                    /*obj*/ resultArray);
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
                            mCallbacks.onCameraError(errorCode, resultExtras);
                            break;
                        }
                        case CAMERA_IDLE:
                            mCallbacks.onCameraIdle();
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
                            mCallbacks.onResultReceived(result, resultExtras);
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
                                                         int cameraId) {
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
        CameraBinderDecorator.throwOnError(initErrors);

        // Disable shutter sounds (this will work unconditionally) for api2 clients
        legacyCamera.disableShutterSound();

        CameraInfo info = new CameraInfo();
        Camera.getCameraInfo(cameraId, info);

        CameraCharacteristics characteristics =
                LegacyMetadataMapper.createCharacteristics(legacyCamera.getParameters(), info);
        LegacyCameraDevice device = new LegacyCameraDevice(
                cameraId, legacyCamera, characteristics, threadCallbacks);
        return new CameraDeviceUserShim(cameraId, device, characteristics, init, threadCallbacks);
    }

    @Override
    public void disconnect() {
        if (DEBUG) {
            Log.d(TAG, "disconnect called.");
        }

        try {
            mLegacyDevice.close();
        } finally {
            mCameraInit.close();
            mCameraCallbacks.close();
        }
    }

    @Override
    public int submitRequest(CaptureRequest request, boolean streaming,
                             /*out*/LongParcelable lastFrameNumber) {
        if (DEBUG) {
            Log.d(TAG, "submitRequest called.");
        }
        synchronized(mConfigureLock) {
            if (mConfiguring) {
                Log.e(TAG, "Cannot submit request, configuration change in progress.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
        }
        return mLegacyDevice.submitRequest(request, streaming, lastFrameNumber);
    }

    @Override
    public int submitRequestList(List<CaptureRequest> request, boolean streaming,
                                 /*out*/LongParcelable lastFrameNumber) {
        if (DEBUG) {
            Log.d(TAG, "submitRequestList called.");
        }
        synchronized(mConfigureLock) {
            if (mConfiguring) {
                Log.e(TAG, "Cannot submit request, configuration change in progress.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
        }
        return mLegacyDevice.submitRequestList(request, streaming, lastFrameNumber);
    }

    @Override
    public int cancelRequest(int requestId, /*out*/LongParcelable lastFrameNumber) {
        if (DEBUG) {
            Log.d(TAG, "cancelRequest called.");
        }
        synchronized(mConfigureLock) {
            if (mConfiguring) {
                Log.e(TAG, "Cannot cancel request, configuration change in progress.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
        }
        long lastFrame = mLegacyDevice.cancelRequest(requestId);
        lastFrameNumber.setNumber(lastFrame);
        return CameraBinderDecorator.NO_ERROR;
    }

    @Override
    public int beginConfigure() {
        if (DEBUG) {
            Log.d(TAG, "beginConfigure called.");
        }
        synchronized(mConfigureLock) {
            if (mConfiguring) {
                Log.e(TAG, "Cannot begin configure, configuration change already in progress.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
            mConfiguring = true;
        }
        return CameraBinderDecorator.NO_ERROR;
    }

    @Override
    public int endConfigure() {
        if (DEBUG) {
            Log.d(TAG, "endConfigure called.");
        }
        ArrayList<Surface> surfaces = null;
        synchronized(mConfigureLock) {
            if (!mConfiguring) {
                Log.e(TAG, "Cannot end configure, no configuration change in progress.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
            int numSurfaces = mSurfaces.size();
            if (numSurfaces > 0) {
                surfaces = new ArrayList<>();
                for (int i = 0; i < numSurfaces; ++i) {
                    surfaces.add(mSurfaces.valueAt(i));
                }
            }
            mConfiguring = false;
        }
        return mLegacyDevice.configureOutputs(surfaces);
    }

    @Override
    public int deleteStream(int streamId) {
        if (DEBUG) {
            Log.d(TAG, "deleteStream called.");
        }
        synchronized(mConfigureLock) {
            if (!mConfiguring) {
                Log.e(TAG, "Cannot delete stream, beginConfigure hasn't been called yet.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
            int index = mSurfaces.indexOfKey(streamId);
            if (index < 0) {
                Log.e(TAG, "Cannot delete stream, stream id " + streamId + " doesn't exist.");
                return CameraBinderDecorator.BAD_VALUE;
            }
            mSurfaces.removeAt(index);
        }
        return CameraBinderDecorator.NO_ERROR;
    }

    @Override
    public int createStream(int width, int height, int format, Surface surface) {
        if (DEBUG) {
            Log.d(TAG, "createStream called.");
        }
        synchronized(mConfigureLock) {
            if (!mConfiguring) {
                Log.e(TAG, "Cannot create stream, beginConfigure hasn't been called yet.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
            int id = ++mSurfaceIdCounter;
            mSurfaces.put(id, surface);
            return id;
        }
    }

    @Override
    public int createDefaultRequest(int templateId, /*out*/CameraMetadataNative request) {
        if (DEBUG) {
            Log.d(TAG, "createDefaultRequest called.");
        }

        CameraMetadataNative template;
        try {
            template =
                    LegacyMetadataMapper.createRequestTemplate(mCameraCharacteristics, templateId);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "createDefaultRequest - invalid templateId specified");
            return CameraBinderDecorator.BAD_VALUE;
        }

        request.swap(template);
        return CameraBinderDecorator.NO_ERROR;
    }

    @Override
    public int getCameraInfo(/*out*/CameraMetadataNative info) {
        if (DEBUG) {
            Log.d(TAG, "getCameraInfo called.");
        }
        // TODO: implement getCameraInfo.
        Log.e(TAG, "getCameraInfo unimplemented.");
        return CameraBinderDecorator.NO_ERROR;
    }

    @Override
    public int waitUntilIdle() throws RemoteException {
        if (DEBUG) {
            Log.d(TAG, "waitUntilIdle called.");
        }
        synchronized(mConfigureLock) {
            if (mConfiguring) {
                Log.e(TAG, "Cannot wait until idle, configuration change in progress.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
        }
        mLegacyDevice.waitUntilIdle();
        return CameraBinderDecorator.NO_ERROR;
    }

    @Override
    public int flush(/*out*/LongParcelable lastFrameNumber) {
        if (DEBUG) {
            Log.d(TAG, "flush called.");
        }
        synchronized(mConfigureLock) {
            if (mConfiguring) {
                Log.e(TAG, "Cannot flush, configuration change in progress.");
                return CameraBinderDecorator.INVALID_OPERATION;
            }
        }
        // TODO: implement flush.
        return CameraBinderDecorator.NO_ERROR;
    }

    @Override
    public IBinder asBinder() {
        // This is solely intended to be used for in-process binding.
        return null;
    }
}
