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

package com.android.server.biometrics.iris;

import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.iris.IIrisService;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * TODO(b/141025588): Add JavaDoc.
 */
public final class IrisAuthenticator extends IBiometricAuthenticator.Stub {
    private final IIrisService mIrisService;

    public IrisAuthenticator(IIrisService irisService) {
        mIrisService = irisService;
    }

    @Override
    public void prepareForAuthentication(boolean requireConfirmation, IBinder token,
            long sessionId, int userId, IBiometricServiceReceiverInternal wrapperReceiver,
            String opPackageName, int cookie, int callingUid, int callingPid, int callingUserId)
            throws RemoteException {
    }

    @Override
    public void startPreparedClient(int cookie) throws RemoteException {
    }

    @Override
    public void cancelAuthenticationFromService(IBinder token, String opPackageName, int callingUid,
            int callingPid, int callingUserId, boolean fromClient) throws RemoteException {
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
    public void resetLockout(byte[] token) throws RemoteException {
    }

    @Override
    public void setActiveUser(int uid) throws RemoteException {
    }

    @Override
    public long getAuthenticatorId(int callingUserId) throws RemoteException {
        return 0;
    }
}
