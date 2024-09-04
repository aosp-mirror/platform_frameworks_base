/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.face;

import android.annotation.NonNull;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.face.IFaceService;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.biometrics.sensors.LockoutTracker;

/**
 * Shim that converts IFaceService into a common reusable IBiometricAuthenticator interface.
 */
public final class FaceAuthenticator extends IBiometricAuthenticator.Stub {
    private final IFaceService mFaceService;
    private final int mSensorId;

    public FaceAuthenticator(IFaceService faceService, int sensorId) {
        mFaceService = faceService;
        mSensorId = sensorId;
    }

    @Override
    public ITestSession createTestSession(@NonNull ITestSessionCallback callback,
            @NonNull String opPackageName) throws RemoteException {
        return mFaceService.createTestSession(mSensorId, callback, opPackageName);
    }

    @Override
    public SensorPropertiesInternal getSensorProperties(@NonNull String opPackageName)
            throws RemoteException {
        return mFaceService.getSensorProperties(mSensorId, opPackageName);
    }

    @Override
    public byte[] dumpSensorServiceStateProto(boolean clearSchedulerBuffer) throws RemoteException {
        return mFaceService.dumpSensorServiceStateProto(mSensorId, clearSchedulerBuffer);
    }

    @Override
    public void prepareForAuthentication(boolean requireConfirmation, IBinder token,
            long operationId, int userId, IBiometricSensorReceiver sensorReceiver,
            String opPackageName, long requestId, int cookie, boolean allowBackgroundAuthentication,
            boolean isForLegacyFingerprintManager, boolean isMandatoryBiometrics)
            throws RemoteException {
        mFaceService.prepareForAuthentication(requireConfirmation, token, operationId,
                sensorReceiver, new FaceAuthenticateOptions.Builder()
                        .setUserId(userId)
                        .setSensorId(mSensorId)
                        .setOpPackageName(opPackageName)
                        .setIsMandatoryBiometrics(isMandatoryBiometrics)
                        .build(),
                requestId, cookie, allowBackgroundAuthentication);
    }

    @Override
    public void startPreparedClient(int cookie) throws RemoteException {
        mFaceService.startPreparedClient(mSensorId, cookie);
    }

    @Override
    public void cancelAuthenticationFromService(IBinder token, String opPackageName, long requestId)
            throws RemoteException {
        mFaceService.cancelAuthenticationFromService(mSensorId, token, opPackageName, requestId);
    }

    @Override
    public boolean isHardwareDetected(String opPackageName) throws RemoteException {
        return mFaceService.isHardwareDetected(mSensorId, opPackageName);
    }

    @Override
    public boolean hasEnrolledTemplates(int userId, String opPackageName) throws RemoteException {
        return mFaceService.hasEnrolledFaces(mSensorId, userId, opPackageName);
    }

    @Override
    public void invalidateAuthenticatorId(int userId, IInvalidationCallback callback)
            throws RemoteException {
        mFaceService.invalidateAuthenticatorId(mSensorId, userId, callback);
    }

    @Override
    public @LockoutTracker.LockoutMode int getLockoutModeForUser(int userId)
            throws RemoteException {
        return mFaceService.getLockoutModeForUser(mSensorId, userId);
    }

    @Override
    public long getAuthenticatorId(int callingUserId) throws RemoteException {
        return mFaceService.getAuthenticatorId(mSensorId, callingUserId);
    }

    @Override
    public void resetLockout(IBinder token, String opPackageName, int userId,
            byte[] hardwareAuthToken) throws RemoteException {
        mFaceService.resetLockout(token, mSensorId, userId, hardwareAuthToken,
                opPackageName);
    }
}
