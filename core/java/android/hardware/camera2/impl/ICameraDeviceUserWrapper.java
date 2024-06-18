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

import android.hardware.ICameraService;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.ICameraOfflineSession;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.ExceptionUtils;
import android.hardware.camera2.utils.SubmitInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
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
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public SubmitInfo submitRequestList(CaptureRequest[] requestList, boolean streaming)
            throws CameraAccessException {
        try {
            return mRemoteDevice.submitRequestList(requestList, streaming);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public long cancelRequest(int requestId) throws CameraAccessException {
        try {
            return mRemoteDevice.cancelRequest(requestId);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void beginConfigure() throws CameraAccessException {
        try {
            mRemoteDevice.beginConfigure();
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public int[] endConfigure(int operatingMode, CameraMetadataNative sessionParams,
            long startTimeMs) throws CameraAccessException {
        try {
            return mRemoteDevice.endConfigure(operatingMode, (sessionParams == null) ?
                    new CameraMetadataNative() : sessionParams, startTimeMs);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void deleteStream(int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.deleteStream(streamId);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public int createStream(OutputConfiguration outputConfiguration)
            throws CameraAccessException {
        try {
            return mRemoteDevice.createStream(outputConfiguration);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public int createInputStream(int width, int height, int format, boolean isMultiResolution)
            throws CameraAccessException {
        try {
            return mRemoteDevice.createInputStream(width, height, format, isMultiResolution);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public Surface getInputSurface() throws CameraAccessException {
        try {
            return mRemoteDevice.getInputSurface();
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public CameraMetadataNative createDefaultRequest(int templateId) throws CameraAccessException {
        try {
            return mRemoteDevice.createDefaultRequest(templateId);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public CameraMetadataNative getCameraInfo() throws CameraAccessException {
        try {
            return mRemoteDevice.getCameraInfo();
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void waitUntilIdle() throws CameraAccessException {
        try {
            mRemoteDevice.waitUntilIdle();
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public boolean isSessionConfigurationSupported(SessionConfiguration sessionConfig)
            throws CameraAccessException {
        try {
            return mRemoteDevice.isSessionConfigurationSupported(sessionConfig);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ICameraService.ERROR_INVALID_OPERATION) {
                throw new UnsupportedOperationException("Session configuration query not " +
                        "supported");
            } else if (e.errorCode == ICameraService.ERROR_ILLEGAL_ARGUMENT) {
                throw new IllegalArgumentException("Invalid session configuration");
            }

            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public long flush() throws CameraAccessException {
        try {
            return mRemoteDevice.flush();
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void prepare(int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.prepare(streamId);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void tearDown(int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.tearDown(streamId);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void prepare2(int maxCount, int streamId) throws CameraAccessException {
        try {
            mRemoteDevice.prepare2(maxCount, streamId);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void updateOutputConfiguration(int streamId, OutputConfiguration config)
            throws CameraAccessException {
        try {
            mRemoteDevice.updateOutputConfiguration(streamId, config);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public ICameraOfflineSession switchToOffline(ICameraDeviceCallbacks cbs,
            int[] offlineOutputIds) throws CameraAccessException {
        try {
            return mRemoteDevice.switchToOffline(cbs, offlineOutputIds);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void finalizeOutputConfigurations(int streamId, OutputConfiguration deferredConfig)
            throws CameraAccessException {
        try {
            mRemoteDevice.finalizeOutputConfigurations(streamId, deferredConfig);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public void setCameraAudioRestriction(int mode) throws CameraAccessException {
        try {
            mRemoteDevice.setCameraAudioRestriction(mode);
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

    public int getGlobalAudioRestriction() throws CameraAccessException {
        try {
            return mRemoteDevice.getGlobalAudioRestriction();
        } catch (ServiceSpecificException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        } catch (RemoteException e) {
            throw ExceptionUtils.throwAsPublicException(e);
        }
    }

}
