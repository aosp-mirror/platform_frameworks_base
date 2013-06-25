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

package android.hardware.photography.impl;

import android.hardware.photography.CameraMetadata;
import android.hardware.photography.CaptureResult;
import android.hardware.photography.ICameraDeviceUser;
import android.hardware.photography.ICameraDeviceCallbacks;
import android.hardware.photography.CameraAccessException;
import android.hardware.photography.CameraProperties;
import android.hardware.photography.CaptureRequest;
import android.hardware.photography.utils.CameraRuntimeException;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import java.util.HashMap;
import java.util.List;
import java.util.Stack;

/**
 * HAL2.1+ implementation of CameraDevice Use CameraManager#open to instantiate
 */
public class CameraDevice implements android.hardware.photography.CameraDevice {

    private final String TAG;

    // TODO: guard every function with if (!mRemoteDevice) check (if it was closed)
    private ICameraDeviceUser mRemoteDevice;

    private final Object mLock = new Object();
    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    // XX: Make this a WeakReference<CaptureListener> ?
    private final HashMap<Integer, CaptureListenerHolder> mCaptureListenerMap =
            new HashMap<Integer, CaptureListenerHolder>();

    private final Stack<Integer> mRepeatingRequestIdStack = new Stack<Integer>();

    private final String mCameraId;

    public CameraDevice(String cameraId) {
        mCameraId = cameraId;
        TAG = String.format("CameraDevice-%s-JV", mCameraId);
    }

    public CameraDeviceCallbacks getCallbacks() {
        return mCallbacks;
    }

    /**
     * @hide
     */
    public void setRemoteDevice(ICameraDeviceUser remoteDevice) {
        mRemoteDevice = remoteDevice;
    }

    @Override
    public CameraProperties getProperties() throws CameraAccessException {
        // TODO
        Log.v(TAG, "TODO: Implement getProperties");
        return new CameraProperties();
    }

    @Override
    public void configureOutputs(List<Surface> outputs) throws CameraAccessException {
        synchronized (mLock) {
            // TODO: delete outputs that aren't in this list that were configured previously
            for (Surface s : outputs) {
                try {
                    // TODO: remove width,height,format since we are ignoring
                    // it.
                    mRemoteDevice.createStream(0, 0, 0, s);
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
    public CaptureRequest createCaptureRequest(int templateType) throws CameraAccessException {

        synchronized (mLock) {

            CameraMetadata templatedRequest = new CameraMetadata();

            try {
                mRemoteDevice.createDefaultRequest(templateType, /* out */templatedRequest);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return null;
            }

            CaptureRequest request = new CaptureRequest();

            // XX: could also change binder signature but that's more work than
            // just using swap.
            request.swap(templatedRequest);

            return request;

        }
    }

    @Override
    public void capture(CaptureRequest request, CaptureListener listener)
            throws CameraAccessException {
        submitCaptureRequest(request, listener, /*streaming*/false);
    }

    @Override
    public void captureBurst(List<CaptureRequest> requests, CaptureListener listener)
            throws CameraAccessException {
        // TODO
        throw new UnsupportedOperationException("Burst capture implemented yet");

    }

    private void submitCaptureRequest(CaptureRequest request, CaptureListener listener,
            boolean repeating) throws CameraAccessException {

        synchronized (mLock) {

            int requestId;

            try {
                requestId = mRemoteDevice.submitRequest(request, repeating);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }

            mCaptureListenerMap.put(requestId, new CaptureListenerHolder(listener, request,
                    repeating));

            if (repeating) {
                mRepeatingRequestIdStack.add(requestId);
            }

        }
    }

    @Override
    public void setRepeatingRequest(CaptureRequest request, CaptureListener listener)
            throws CameraAccessException {
        submitCaptureRequest(request, listener, /*streaming*/true);
    }

    @Override
    public void setRepeatingBurst(List<CaptureRequest> requests, CaptureListener listener)
            throws CameraAccessException {
        // TODO
        throw new UnsupportedOperationException("Burst capture implemented yet");
    }

    @Override
    public void stopRepeating() throws CameraAccessException {

        synchronized (mLock) {

            while (!mRepeatingRequestIdStack.isEmpty()) {
                int requestId = mRepeatingRequestIdStack.pop();

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
        // TODO: implement
    }

    @Override
    public void setErrorListener(ErrorListener listener) {
        // TODO Auto-generated method stub

    }

    @Override
    public void close() throws Exception {

        // TODO: every method should throw IllegalStateException after close has been called

        synchronized (mLock) {

            try {
                mRemoteDevice.disconnect();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
            }

            mRemoteDevice = null;

        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } catch (CameraRuntimeException e) {
            Log.e(TAG, "Got error while trying to finalize, ignoring: " + e.getMessage());
        }
        finally {
            super.finalize();
        }
    }

    static class CaptureListenerHolder {

        private final boolean mRepeating;
        private final CaptureListener mListener;
        private final CaptureRequest mRequest;

        CaptureListenerHolder(CaptureListener listener, CaptureRequest request, boolean repeating) {
            mRepeating = repeating;
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
    }

    // TODO: unit tests
    public class CameraDeviceCallbacks extends Binder implements ICameraDeviceCallbacks {

        @Override
        public IBinder asBinder() {
            return this;
        }

        // TODO: consider rename to onMessageReceived
        @Override
        public void notifyCallback(int msgType, int ext1, int ext2) throws RemoteException {
            Log.d(TAG, "Got message " + msgType + " ext1: " + ext1 + " , ext2: " + ext2);
            // TODO implement rest
        }

        @Override
        public void onResultReceived(int frameId, CameraMetadata result) throws RemoteException {
            Log.d(TAG, "Received result for frameId " + frameId);

            CaptureListenerHolder holder;

            synchronized (mLock) {
                // TODO: move this whole map into this class to make it more testable,
                //        exposing the methods necessary like subscribeToRequest, unsubscribe..
                // TODO: make class static class

                holder = CameraDevice.this.mCaptureListenerMap.get(frameId);

                // Clean up listener once we no longer expect to see it.

                // TODO: how to handle repeating listeners?
                // we probably want cancelRequest to return # of times it already enqueued and
                // keep a counter.
                if (holder != null && !holder.isRepeating()) {
                    CameraDevice.this.mCaptureListenerMap.remove(frameId);
                }
            }

            if (holder == null) {
                Log.e(TAG, "Result had no listener holder associated with it, dropping result");
                return;
            }

            CaptureResult resultAsCapture = new CaptureResult();
            resultAsCapture.swap(result);

            if (holder.getListener() != null) {
                holder.getListener().onCaptureComplete(CameraDevice.this, holder.getRequest(),
                        resultAsCapture);
            }
        }

    }

}
