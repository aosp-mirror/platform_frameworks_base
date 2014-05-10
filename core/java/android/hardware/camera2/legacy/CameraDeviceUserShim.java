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
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.utils.LongParcelable;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
public class CameraDeviceUserShim implements ICameraDeviceUser {
    private static final String TAG = "CameraDeviceUserShim";

    private static final boolean DEBUG = Log.isLoggable(LegacyCameraDevice.DEBUG_PROP, Log.DEBUG);

    private final LegacyCameraDevice mLegacyDevice;

    private final Object mConfigureLock = new Object();
    private int mSurfaceIdCounter;
    private boolean mConfiguring;
    private final SparseArray<Surface> mSurfaces;

    protected CameraDeviceUserShim(int cameraId, LegacyCameraDevice legacyCamera) {
        mLegacyDevice = legacyCamera;
        mConfiguring = false;
        mSurfaces = new SparseArray<Surface>();

        mSurfaceIdCounter = 0;
    }

    public static CameraDeviceUserShim connectBinderShim(ICameraDeviceCallbacks callbacks,
                                                         int cameraId) {
        if (DEBUG) {
            Log.d(TAG, "Opening shim Camera device");
        }
        // TODO: Move open/init into LegacyCameraDevice thread when API is switched to async.
        Camera legacyCamera = Camera.openUninitialized();
        int initErrors = legacyCamera.cameraInit(cameraId);
        // Check errors old HAL initialization
        if (Camera.checkInitErrors(initErrors)) {
            // TODO: Map over old camera error codes.  This likely involves improving the error
            // reporting in the HAL1 connect path.
            throw new CameraRuntimeException(CameraAccessException.CAMERA_DISCONNECTED);
        }
        LegacyCameraDevice device = new LegacyCameraDevice(cameraId, legacyCamera, callbacks);
        return new CameraDeviceUserShim(cameraId, device);
    }

    @Override
    public void disconnect() {
        if (DEBUG) {
            Log.d(TAG, "disconnect called.");
        }
        mLegacyDevice.close();
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
                surfaces = new ArrayList<Surface>();
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
        // TODO: implement createDefaultRequest.
        Log.e(TAG, "createDefaultRequest unimplemented.");
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
