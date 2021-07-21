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

package com.android.server.biometrics.sensors.fingerprint;

import android.annotation.NonNull;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintService;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.biometrics.sensors.LockoutTracker;

/**
 * Shim that converts IFingerprintService into a common reusable IBiometricAuthenticator interface.
 */
public final class FingerprintAuthenticator extends IBiometricAuthenticator.Stub {
    private final IFingerprintService mFingerprintService;
    private final int mSensorId;

    public FingerprintAuthenticator(IFingerprintService fingerprintService, int sensorId) {
        mFingerprintService = fingerprintService;
        mSensorId = sensorId;
    }

    @Override
    public ITestSession createTestSession(@NonNull ITestSessionCallback callback,
            @NonNull String opPackageName) throws RemoteException {
        return mFingerprintService.createTestSession(mSensorId, callback, opPackageName);
    }

    @Override
    public SensorPropertiesInternal getSensorProperties(@NonNull String opPackageName)
            throws RemoteException {
        return mFingerprintService.getSensorProperties(mSensorId, opPackageName);
    }

    @Override
    public byte[] dumpSensorServiceStateProto(boolean clearSchedulerBuffer) throws RemoteException {
        return mFingerprintService.dumpSensorServiceStateProto(mSensorId, clearSchedulerBuffer);
    }

    @Override
    public void prepareForAuthentication(boolean requireConfirmation, IBinder token,
            long operationId, int userId, IBiometricSensorReceiver sensorReceiver,
            String opPackageName, int cookie, boolean allowBackgroundAuthentication)
            throws RemoteException {
        mFingerprintService.prepareForAuthentication(mSensorId, token, operationId, userId,
                sensorReceiver, opPackageName, cookie, allowBackgroundAuthentication);
    }

    @Override
    public void startPreparedClient(int cookie) throws RemoteException {
        mFingerprintService.startPreparedClient(mSensorId, cookie);
    }

    @Override
    public void cancelAuthenticationFromService(IBinder token, String opPackageName)
            throws RemoteException {
        mFingerprintService.cancelAuthenticationFromService(mSensorId, token, opPackageName);
    }

    @Override
    public boolean isHardwareDetected(String opPackageName) throws RemoteException {
        return mFingerprintService.isHardwareDetected(mSensorId, opPackageName);
    }

    @Override
    public boolean hasEnrolledTemplates(int userId, String opPackageName) throws RemoteException {
        return mFingerprintService.hasEnrolledFingerprints(mSensorId, userId, opPackageName);
    }

    @Override
    public @LockoutTracker.LockoutMode int getLockoutModeForUser(int userId)
            throws RemoteException {
        return mFingerprintService.getLockoutModeForUser(mSensorId, userId);
    }

    @Override
    public void invalidateAuthenticatorId(int userId, IInvalidationCallback callback)
            throws RemoteException {
        mFingerprintService.invalidateAuthenticatorId(mSensorId, userId, callback);
    }

    @Override
    public long getAuthenticatorId(int callingUserId) throws RemoteException {
        return mFingerprintService.getAuthenticatorId(mSensorId, callingUserId);
    }

    @Override
    public void resetLockout(IBinder token, String opPackageName, int userId,
            byte[] hardwareAuthToken) throws RemoteException {
        mFingerprintService.resetLockout(token, mSensorId, userId, hardwareAuthToken,
                opPackageName);
    }
}
