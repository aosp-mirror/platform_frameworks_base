/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.hardware.camera2.CameraAccessException.CAMERA_DISABLED;
import static android.hardware.camera2.CameraAccessException.CAMERA_DISCONNECTED;
import static android.hardware.camera2.CameraAccessException.CAMERA_IN_USE;
import static android.hardware.camera2.CameraAccessException.CAMERA_ERROR;
import static android.hardware.camera2.CameraAccessException.MAX_CAMERAS_IN_USE;
import static android.hardware.camera2.CameraAccessException.CAMERA_DEPRECATED_HAL;

import android.hardware.ICameraService;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.utils.SubmitInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;

/**
 * A wrapper around ICameraDeviceUser.
 *
 * Mainly used to convert ServiceSpecificExceptions to the correct
 * checked / unchecked exception.
 *
 * @hide
 */
public class ICameraDeviceUserWrapper {

    private final ICameraDeviceUser mRemoteDevice;

    public ICameraDeviceUserWrapper(ICameraDeviceUser remoteDevice) {
        if (remoteDevice == null) {
            throw new NullPointerException("Remote device may not be null");
        }
        mRemoteDevice = remoteDevice;
    }

    public void unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
        if (mRemoteDevice.asBinder() != null) {
            mRemoteDevice.asBinder().unlinkToDeath(recipient, flags);
        }
    }

    public void disconnect()  {
        try {
            mRemoteDevice.disconnect();
        } catch (RemoteException t) {
            // ignore binder errors for disconnect
        }
    }

    public SubmitInfo submitRequest(CaptureRequest request, boolean streaming)
            throws CameraAccessException  {
        try {
            return mRemoteDevice.submitRequest(request, streaming);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public SubmitInfo submitRequestList(CaptureRequest[] requestList, boolean streaming)
            throws CameraAccessException {
        try {
            return mRemoteDevice.submitRequestList(requestList, streaming);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public long cancelRequest(int requestId) throws CameraAccessException {
        try {
            return mRemoteDevice.cancelRequest(requestId);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void beginConfigure() throws CameraAccessException {
        try {
            mRemoteDevice.beginConfigure();
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void endConfigure(boolean isConstrainedHighSpeed) throws CameraAccessException {
        try {
            mRemoteDevice.endConfigure(isConstrainedHighSpeed);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void deleteStream(int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.deleteStream(streamId);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public int createStream(OutputConfiguration outputConfiguration)
            throws CameraAccessException {
        try {
            return mRemoteDevice.createStream(outputConfiguration);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public int createInputStream(int width, int height, int format) throws CameraAccessException {
        try {
            return mRemoteDevice.createInputStream(width, height, format);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public Surface getInputSurface() throws CameraAccessException {
        try {
            return mRemoteDevice.getInputSurface();
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public CameraMetadataNative createDefaultRequest(int templateId) throws CameraAccessException {
        try {
            return mRemoteDevice.createDefaultRequest(templateId);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public CameraMetadataNative getCameraInfo() throws CameraAccessException {
        try {
            return mRemoteDevice.getCameraInfo();
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void waitUntilIdle() throws CameraAccessException {
        try {
            mRemoteDevice.waitUntilIdle();
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public long flush() throws CameraAccessException {
        try {
            return mRemoteDevice.flush();
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void prepare(int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.prepare(streamId);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void tearDown(int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.tearDown(streamId);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void prepare2(int maxCount, int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.prepare2(maxCount, streamId);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

    public void setDeferredConfiguration(int streamId, OutputConfiguration deferredConfig)
            throws CameraAccessException {
        try {
            mRemoteDevice.setDeferredConfiguration(streamId, deferredConfig);
        } catch (Throwable t) {
            CameraManager.throwAsPublicException(t);
            throw new UnsupportedOperationException("Unexpected exception", t);
        }
    }

}
