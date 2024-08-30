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

package com.android.server.biometrics.sensors.iris;

import android.annotation.NonNull;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.iris.IIrisService;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.biometrics.sensors.LockoutTracker;

/**
 * TODO(b/141025588): Add JavaDoc.
 */
public final class IrisAuthenticator extends IBiometricAuthenticator.Stub {
    private final IIrisService mIrisService;

    public IrisAuthenticator(IIrisService irisService, int sensorId) {
        mIrisService = irisService;
    }

    @Override
    public ITestSession createTestSession(@NonNull ITestSessionCallback callback,
            @NonNull String opPackageName) throws RemoteException {
        return null;
    }

    @Override
    public SensorPropertiesInternal getSensorProperties(@NonNull String opPackageName)
            throws RemoteException {
        return null;
    }

    @Override
    public byte[] dumpSensorServiceStateProto(boolean clearSchedulerBuffer) throws RemoteException {
        return null;
    }

    @Override
    public void prepareForAuthentication(boolean requireConfirmation, IBinder token,
            long sessionId, int userId, IBiometricSensorReceiver sensorReceiver,
            String opPackageName, long requestId, int cookie, boolean allowBackgroundAuthentication,
            boolean isForLegacyFingerprintManager, boolean isMandatoryBiometrics)
            throws RemoteException {
    }

    @Override
    public void startPreparedClient(int cookie) throws RemoteException {
    }

    @Override
    public void cancelAuthenticationFromService(IBinder token, String opPackageName, long requestId)
            throws RemoteException {
    }

    @Override
    public boolean isHardwareDetected(String opPackageName) throws RemoteException {
        return false;
    }

    @Override
    public boolean hasEnrolledTemplates(int userId, String opPackageName) throws RemoteException {
        return false;
    }

    @Override
    public @LockoutTracker.LockoutMode int getLockoutModeForUser(int userId)
            throws RemoteException {
        return LockoutTracker.LOCKOUT_NONE;
    }

    @Override
    public void invalidateAuthenticatorId(int userId, IInvalidationCallback callback) {
    }

    @Override
    public long getAuthenticatorId(int callingUserId) throws RemoteException {
        return 0;
    }

    @Override
    public void resetLockout(IBinder token, String opPackageName, int userId,
            byte[] hardwareAuthToken) throws RemoteException {
    }
}