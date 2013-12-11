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

package android.hardware.camera2.impl;

import static android.hardware.camera2.CameraAccessException.CAMERA_IN_USE;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/**
 * HAL2.1+ implementation of CameraDevice. Use CameraManager#open to instantiate
 */
public class CameraDevice implements android.hardware.camera2.CameraDevice {

    private final String TAG;
    private final boolean DEBUG;

    private static final int REQUEST_ID_NONE = -1;

    // TODO: guard every function with if (!mRemoteDevice) check (if it was closed)
    private ICameraDeviceUser mRemoteDevice;

    private final Object mLock = new Object();
    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    private final StateListener mDeviceListener;
    private final Handler mDeviceHandler;

    private boolean mIdle = true;

    private final SparseArray<CaptureListenerHolder> mCaptureListenerMap =
            new SparseArray<CaptureListenerHolder>();

    private int mRepeatingRequestId = REQUEST_ID_NONE;
    private final ArrayList<Integer> mRepeatingRequestIdDeletedList = new ArrayList<Integer>();
    // Map stream IDs to Surfaces
    private final SparseArray<Surface> mConfiguredOutputs = new SparseArray<Surface>();

    private final String mCameraId;

    // Runnables for all state transitions, except error, which needs the
    // error code argument

    private final Runnable mCallOnOpened = new Runnable() {
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onOpened(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnUnconfigured = new Runnable() {
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onUnconfigured(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnActive = new Runnable() {
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onActive(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnBusy = new Runnable() {
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onBusy(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnClosed = new Runnable() {
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onClosed(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnIdle = new Runnable() {
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onIdle(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnDisconnected = new Runnable() {
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onDisconnected(CameraDevice.this);
            }
        }
    };

    public CameraDevice(String cameraId, StateListener listener, Handler handler) {
        if (cameraId == null || listener == null || handler == null) {
            throw new IllegalArgumentException("Null argument given");
        }
        mCameraId = cameraId;
        mDeviceListener = listener;
        mDeviceHandler = handler;
        TAG = String.format("CameraDevice-%s-JV", mCameraId);
        DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    }

    public CameraDeviceCallbacks getCallbacks() {
        return mCallbacks;
    }

    public void setRemoteDevice(ICameraDeviceUser remoteDevice) {
        // TODO: Move from decorator to direct binder-mediated exceptions
        synchronized(mLock) {
            mRemoteDevice = CameraBinderDecorator.newInstance(remoteDevice);

            mDeviceHandler.post(mCallOnOpened);
            mDeviceHandler.post(mCallOnUnconfigured);
        }
    }

    @Override
    public String getId() {
        return mCameraId;
    }

    @Override
    public void configureOutputs(List<Surface> outputs) throws CameraAccessException {
        // Treat a null input the same an empty list
        if (outputs == null) {
            outputs = new ArrayList<Surface>();
        }
        synchronized (mLock) {
            checkIfCameraClosed();

            HashSet<Surface> addSet = new HashSet<Surface>(outputs);    // Streams to create
            List<Integer> deleteList = new ArrayList<Integer>();        // Streams to delete

            // Determine which streams need to be created, which to be deleted
            for (int i = 0; i < mConfiguredOutputs.size(); ++i) {
                int streamId = mConfiguredOutputs.keyAt(i);
                Surface s = mConfiguredOutputs.valueAt(i);

                if (!outputs.contains(s)) {
                    deleteList.add(streamId);
                } else {
                    addSet.remove(s);  // Don't create a stream previously created
                }
            }

            mDeviceHandler.post(mCallOnBusy);
            stopRepeating();

            try {
                waitUntilIdle();

                // TODO: mRemoteDevice.beginConfigure
                // Delete all streams first (to free up HW resources)
                for (Integer streamId : deleteList) {
                    mRemoteDevice.deleteStream(streamId);
                    mConfiguredOutputs.delete(streamId);
                }

                // Add all new streams
                for (Surface s : addSet) {
                    // TODO: remove width,height,format since we are ignoring
                    // it.
                    int streamId = mRemoteDevice.createStream(0, 0, 0, s);
                    mConfiguredOutputs.put(streamId, s);
                }

                // TODO: mRemoteDevice.endConfigure
            } catch (CameraRuntimeException e) {
                if (e.getReason() == CAMERA_IN_USE) {
                    throw new IllegalStateException("The camera is currently busy." +
                            " You must wait until the previous operation completes.");
                }

                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }

            if (outputs.size() > 0) {
                mDeviceHandler.post(mCallOnIdle);
            } else {
                mDeviceHandler.post(mCallOnUnconfigured);
            }
        }
    }

    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException {
        synchronized (mLock) {
            checkIfCameraClosed();

            CameraMetadataNative templatedRequest = new CameraMetadataNative();

            try {
                mRemoteDevice.createDefaultRequest(templateType, /* out */templatedRequest);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return null;
            }

            CaptureRequest.Builder builder =
                    new CaptureRequest.Builder(templatedRequest);

            return builder;
        }
    }

    @Override
    public int capture(CaptureRequest request, CaptureListener listener, Handler handler)
            throws CameraAccessException {
        return submitCaptureRequest(request, listener, handler, /*streaming*/false);
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        if (requests.isEmpty()) {
            Log.w(TAG, "Capture burst request list is empty, do nothing!");
            return -1;
        }
        // TODO
        throw new UnsupportedOperationException("Burst capture implemented yet");

    }

    private int submitCaptureRequest(CaptureRequest request, CaptureListener listener,
            Handler handler, boolean repeating) throws CameraAccessException {

        // Need a valid handler, or current thread needs to have a looper, if
        // listener is valid
        if (listener != null) {
            handler = checkHandler(handler);
        }

        synchronized (mLock) {
            checkIfCameraClosed();
            int requestId;

            if (repeating) {
                stopRepeating();
            }

            try {
                requestId = mRemoteDevice.submitRequest(request, repeating);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return -1;
            }
            if (listener != null) {
                mCaptureListenerMap.put(requestId, new CaptureListenerHolder(listener, request,
                        handler, repeating));
            }

            if (repeating) {
                mRepeatingRequestId = requestId;
            }

            if (mIdle) {
                mDeviceHandler.post(mCallOnActive);
            }
            mIdle = false;

            return requestId;
        }
    }

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        return submitCaptureRequest(request, listener, handler, /*streaming*/true);
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        if (requests.isEmpty()) {
            Log.w(TAG, "Set Repeating burst request list is empty, do nothing!");
            return -1;
        }
        // TODO
        throw new UnsupportedOperationException("Burst capture implemented yet");
    }

    @Override
    public void stopRepeating() throws CameraAccessException {

        synchronized (mLock) {
            checkIfCameraClosed();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {

                int requestId = mRepeatingRequestId;
                mRepeatingRequestId = REQUEST_ID_NONE;

                // Queue for deletion after in-flight requests finish
                mRepeatingRequestIdDeletedList.add(requestId);

                try {
                    mRemoteDevice.cancelRequest(requestId);
                } catch (CameraRuntimeException e) {
                    throw e.asChecked();
                } catch (RemoteException e) {
                    // impossible
                    return;
                }
            }
        }
    }

    @Override
    public void waitUntilIdle() throws CameraAccessException {

        synchronized (mLock) {
            checkIfCameraClosed();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {
                throw new IllegalStateException("Active repeating request ongoing");
            }

            try {
                mRemoteDevice.waitUntilIdle();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }

            mRepeatingRequestId = REQUEST_ID_NONE;
            mRepeatingRequestIdDeletedList.clear();
            mCaptureListenerMap.clear();
        }
    }

    @Override
    public void flush() throws CameraAccessException {
        synchronized (mLock) {
            checkIfCameraClosed();

            mDeviceHandler.post(mCallOnBusy);
            try {
                mRemoteDevice.flush();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {

            try {
                if (mRemoteDevice != null) {
                    mRemoteDevice.disconnect();
                }
            } catch (CameraRuntimeException e) {
                Log.e(TAG, "Exception while closing: ", e.asChecked());
            } catch (RemoteException e) {
                // impossible
            }

            if (mRemoteDevice != null) {
                mDeviceHandler.post(mCallOnClosed);
            }

            mRemoteDevice = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        }
        finally {
            super.finalize();
        }
    }

    static class CaptureListenerHolder {

        private final boolean mRepeating;
        private final CaptureListener mListener;
        private final CaptureRequest mRequest;
        private final Handler mHandler;

        CaptureListenerHolder(CaptureListener listener, CaptureRequest request, Handler handler,
                boolean repeating) {
            if (listener == null || handler == null) {
                throw new UnsupportedOperationException(
                    "Must have a valid handler and a valid listener");
            }
            mRepeating = repeating;
            mHandler = handler;
            mRequest = request;
            mListener = listener;
        }

        public boolean isRepeating() {
            return mRepeating;
        }

        public CaptureListener getListener() {
            return mListener;
        }

        public CaptureRequest getRequest() {
            return mRequest;
        }

        public Handler getHandler() {
            return mHandler;
        }

    }

    public class CameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

        //
        // Constants below need to be kept up-to-date with
        // frameworks/av/include/camera/camera2/ICameraDeviceCallbacks.h
        //

        //
        // Error codes for onCameraError
        //

        /**
         * Camera has been disconnected
         */
        static final int ERROR_CAMERA_DISCONNECTED = 0;

        /**
         * Camera has encountered a device-level error
         * Matches CameraDevice.StateListener#ERROR_CAMERA_DEVICE
         */
        static final int ERROR_CAMERA_DEVICE = 1;

        /**
         * Camera has encountered a service-level error
         * Matches CameraDevice.StateListener#ERROR_CAMERA_SERVICE
         */
        static final int ERROR_CAMERA_SERVICE = 2;

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onCameraError(final int errorCode) {
            Runnable r = null;
            if (isClosed()) return;

            synchronized(mLock) {
                switch (errorCode) {
                    case ERROR_CAMERA_DISCONNECTED:
                        r = mCallOnDisconnected;
                        break;
                    default:
                        Log.e(TAG, "Unknown error from camera device: " + errorCode);
                        // no break
                    case ERROR_CAMERA_DEVICE:
                    case ERROR_CAMERA_SERVICE:
                        r = new Runnable() {
                            public void run() {
                                if (!CameraDevice.this.isClosed()) {
                                    mDeviceListener.onError(CameraDevice.this, errorCode);
                                }
                            }
                        };
                        break;
                }
                CameraDevice.this.mDeviceHandler.post(r);
            }
        }

        @Override
        public void onCameraIdle() {
            if (isClosed()) return;

            if (DEBUG) {
                Log.d(TAG, "Camera now idle");
            }
            synchronized (mLock) {
                if (!CameraDevice.this.mIdle) {
                    CameraDevice.this.mDeviceHandler.post(mCallOnIdle);
                }
                CameraDevice.this.mIdle = true;
            }
        }

        @Override
        public void onCaptureStarted(int requestId, final long timestamp) {
            if (DEBUG) {
                Log.d(TAG, "Capture started for id " + requestId);
            }
            final CaptureListenerHolder holder;

            // Get the listener for this frame ID, if there is one
            synchronized (mLock) {
                holder = CameraDevice.this.mCaptureListenerMap.get(requestId);
            }

            if (holder == null) {
                return;
            }

            if (isClosed()) return;

            // Dispatch capture start notice
            holder.getHandler().post(
                new Runnable() {
                    public void run() {
                        if (!CameraDevice.this.isClosed()) {
                            holder.getListener().onCaptureStarted(
                                CameraDevice.this,
                                holder.getRequest(),
                                timestamp);
                        }
                    }
                });
        }

        @Override
        public void onResultReceived(int requestId, CameraMetadataNative result)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "Received result for id " + requestId);
            }
            final CaptureListenerHolder holder;

            Boolean quirkPartial = result.get(CaptureResult.QUIRKS_PARTIAL_RESULT);
            boolean quirkIsPartialResult = (quirkPartial != null && quirkPartial);

            synchronized (mLock) {
                // TODO: move this whole map into this class to make it more testable,
                //        exposing the methods necessary like subscribeToRequest, unsubscribe..
                // TODO: make class static class

                holder = CameraDevice.this.mCaptureListenerMap.get(requestId);

                // Clean up listener once we no longer expect to see it.
                if (holder != null && !holder.isRepeating() && !quirkIsPartialResult) {
                    CameraDevice.this.mCaptureListenerMap.remove(requestId);
                }

                // TODO: add 'capture sequence completed' callback to the
                // service, and clean up repeating requests there instead.

                // If we received a result for a repeating request and have
                // prior repeating requests queued for deletion, remove those
                // requests from mCaptureListenerMap.
                if (holder != null && holder.isRepeating() && !quirkIsPartialResult
                        && mRepeatingRequestIdDeletedList.size() > 0) {
                    Iterator<Integer> iter = mRepeatingRequestIdDeletedList.iterator();
                    while (iter.hasNext()) {
                        int deletedRequestId = iter.next();
                        if (deletedRequestId < requestId) {
                            CameraDevice.this.mCaptureListenerMap.remove(deletedRequestId);
                            iter.remove();
                        }
                    }
                }

            }

            // Check if we have a listener for this
            if (holder == null) {
                return;
            }

            if (isClosed()) return;

            final CaptureRequest request = holder.getRequest();
            final CaptureResult resultAsCapture = new CaptureResult(result, request, requestId);

            Runnable resultDispatch = null;

            // Either send a partial result or the final capture completed result
            if (quirkIsPartialResult) {
                // Partial result
                resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDevice.this.isClosed()){
                            holder.getListener().onCapturePartial(
                                CameraDevice.this,
                                request,
                                resultAsCapture);
                        }
                    }
                };
            } else {
                // Final capture result
                resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDevice.this.isClosed()){
                            holder.getListener().onCaptureCompleted(
                                CameraDevice.this,
                                request,
                                resultAsCapture);
                        }
                    }
                };
            }

            holder.getHandler().post(resultDispatch);
        }

    }

    /**
     * Default handler management. If handler is null, get the current thread's
     * Looper to create a Handler with. If no looper exists, throw exception.
     */
    private Handler checkHandler(Handler handler) {
        if (handler == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalArgumentException(
                    "No handler given, and current thread has no looper!");
            }
            handler = new Handler(looper);
        }
        return handler;
    }

    private void checkIfCameraClosed() {
        if (mRemoteDevice == null) {
            throw new IllegalStateException("CameraDevice was already closed");
        }
    }

    private boolean isClosed() {
        synchronized(mLock) {
            return (mRemoteDevice == null);
        }
    }
}
