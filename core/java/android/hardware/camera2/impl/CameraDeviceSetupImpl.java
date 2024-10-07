/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.content.Context;
import android.hardware.ICameraService;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.ExceptionUtils;
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import androidx.annotation.NonNull;

import com.android.internal.camera.flags.Flags;

import java.util.concurrent.Executor;

@FlaggedApi(Flags.FLAG_CAMERA_DEVICE_SETUP)
public class CameraDeviceSetupImpl extends CameraDevice.CameraDeviceSetup {
    private final String mCameraId;
    private final CameraManager mCameraManager;
    private final Context mContext;
    private final int mTargetSdkVersion;

    private final Object mInterfaceLock = new Object();

    public CameraDeviceSetupImpl(@NonNull String cameraId, @NonNull CameraManager cameraManager,
            @NonNull Context context) {
        mCameraId = cameraId;
        mCameraManager = cameraManager;
        mContext = context;
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
    }

    @NonNull
    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (mCameraManager.isCameraServiceDisabled()) {
                throw new IllegalArgumentException("No cameras available on device");
            }

            ICameraService cameraService = mCameraManager.getCameraService();
            if (cameraService == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable.");
            }

            try {
                CameraMetadataNative defaultRequest =
                        cameraService.createDefaultRequest(
                                mCameraId,
                                templateType,
                                mCameraManager.getClientAttribution(),
                                mCameraManager.getDevicePolicyFromContext(mContext));
                CameraDeviceImpl.disableZslIfNeeded(defaultRequest, mTargetSdkVersion,
                        templateType);

                return new CaptureRequest.Builder(
                        defaultRequest, /*reprocess=*/ false,
                        CameraCaptureSession.SESSION_ID_NONE, mCameraId,
                        /*physicalCameraIdSet=*/ null);
            } catch (ServiceSpecificException e) {
                throw ExceptionUtils.throwAsPublicException(e);
            } catch (RemoteException e) {
                throw ExceptionUtils.throwAsPublicException(e);
            }
        }
    }

    @Override
    public boolean isSessionConfigurationSupported(@NonNull SessionConfiguration config)
            throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (mCameraManager.isCameraServiceDisabled()) {
                throw new IllegalArgumentException("No cameras available on device");
            }

            ICameraService cameraService = mCameraManager.getCameraService();
            if (cameraService == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable.");
            }

            try {
                return cameraService.isSessionConfigurationWithParametersSupported(
                        mCameraId,
                        mTargetSdkVersion,
                        config,
                        mCameraManager.getClientAttribution(),
                        mCameraManager.getDevicePolicyFromContext(mContext));
            } catch (ServiceSpecificException e) {
                throw ExceptionUtils.throwAsPublicException(e);
            } catch (RemoteException e) {
                throw ExceptionUtils.throwAsPublicException(e);
            }
        }
    }

    @NonNull
    @Override
    public CameraCharacteristics getSessionCharacteristics(
            @NonNull SessionConfiguration sessionConfig) throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (mCameraManager.isCameraServiceDisabled()) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently disabled");
            }

            ICameraService cameraService = mCameraManager.getCameraService();
            if (cameraService == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "Camera service is currently unavailable");
            }

            try {
                CameraMetadataNative metadata =
                        cameraService.getSessionCharacteristics(
                                mCameraId,
                                mTargetSdkVersion,
                                CameraManager.getRotationOverride(mContext),
                                sessionConfig,
                                mCameraManager.getClientAttribution(),
                                mCameraManager.getDevicePolicyFromContext(mContext));

                return mCameraManager.prepareCameraCharacteristics(mCameraId, metadata,
                        cameraService);
            } catch (ServiceSpecificException e) {
                switch (e.errorCode) {
                    case ICameraService.ERROR_INVALID_OPERATION ->
                            throw new UnsupportedOperationException(
                                    "Session Characteristics Query not supported by device.");
                    case ICameraService.ERROR_ILLEGAL_ARGUMENT ->
                            throw new IllegalArgumentException("Invalid Session Configuration");
                    default -> throw ExceptionUtils.throwAsPublicException(e);
                }
            } catch (RemoteException e) {
                throw ExceptionUtils.throwAsPublicException(e);
            }
        }
    }

    @Override
    public void openCamera(@NonNull @CallbackExecutor Executor executor,
            @NonNull CameraDevice.StateCallback callback) throws CameraAccessException {
        mCameraManager.openCamera(mCameraId, executor, callback);
    }

    @NonNull
    @Override
    public String getId() {
        return mCameraId;
    }

    @Override
    public int hashCode() {
        return mCameraId.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof CameraDeviceSetupImpl other) {
            return mCameraId.equals(other.mCameraId);
        }
        return false;
    }

    @Override
    public String toString() {
        return "CameraDeviceSetup(cameraId='" + mCameraId + "')";
    }

    /**
     * Returns true if HAL supports calls to {@code isSessionConfigurationWithParametersSupported};
     * false otherwise.
     * <p>
     * Suppressing AndroidFrameworkCompatChange because we are querying HAL support here
     * and HAL's return value happens to follow the same scheme as SDK version.
     * AndroidFrameworkCompatChange incorrectly flags this as an SDK version check.
     * @hide
     */
    @SuppressWarnings("AndroidFrameworkCompatChange")
    public static boolean isCameraDeviceSetupSupported(CameraCharacteristics chars) {
        Integer queryVersion = chars.get(
                CameraCharacteristics.INFO_SESSION_CONFIGURATION_QUERY_VERSION);
        return queryVersion != null && queryVersion > Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }
}
