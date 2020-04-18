/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.biometrics.face;

import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.face.IFaceService;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.biometrics.SensorConfig;

/**
 * Shim that converts IFaceService into a common reusable IBiometricAuthenticator interface.
 */
public final class FaceAuthenticator extends IBiometricAuthenticator.Stub {
    private final IFaceService mFaceService;

    public FaceAuthenticator(IFaceService faceService, SensorConfig config)
            throws RemoteException {
        mFaceService = faceService;
        mFaceService.initializeConfiguration(config.id);
    }

    @Override
    public void prepareForAuthentication(boolean requireConfirmation, IBinder token,
            long operationId, int userId, IBiometricSensorReceiver sensorReceiver,
            String opPackageName, int cookie, int callingUid, int callingPid, int callingUserId)
            throws RemoteException {
        mFaceService.prepareForAuthentication(requireConfirmation, token, operationId, userId,
                sensorReceiver, opPackageName, cookie, callingUid, callingPid, callingUserId);
    }

    @Override
    public void startPreparedClient(int cookie) throws RemoteException {
        mFaceService.startPreparedClient(cookie);
    }

    @Override
    public void cancelAuthenticationFromService(IBinder token, String opPackageName, int callingUid,
            int callingPid, int callingUserId, boolean fromClient) throws RemoteException {
        mFaceService.cancelAuthenticationFromService(token, opPackageName, callingUid, callingPid,
                callingUserId, fromClient);
    }

    @Override
    public boolean isHardwareDetected(String opPackageName) throws RemoteException {
        return mFaceService.isHardwareDetected(opPackageName);
    }

    @Override
    public boolean hasEnrolledTemplates(int userId, String opPackageName) throws RemoteException {
        return mFaceService.hasEnrolledFaces(userId, opPackageName);
    }

    @Override
    public void resetLockout(byte[] token) throws RemoteException {
        mFaceService.resetLockout(token);
    }

    @Override
    public void setActiveUser(int uid) throws RemoteException {
        mFaceService.setActiveUser(uid);
    }

    @Override
    public long getAuthenticatorId() throws RemoteException {
        return mFaceService.getAuthenticatorId();
    }
}
