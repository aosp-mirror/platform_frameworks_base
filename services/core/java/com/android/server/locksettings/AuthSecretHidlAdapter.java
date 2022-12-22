/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.locksettings;

import android.hardware.authsecret.IAuthSecret;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;

/**
 * Adapt the legacy HIDL interface to present the AIDL interface.
 */
class AuthSecretHidlAdapter implements IAuthSecret {
    // private final String TAG = "AuthSecretHidlAdapter";
    private final android.hardware.authsecret.V1_0.IAuthSecret mImpl;

    AuthSecretHidlAdapter(android.hardware.authsecret.V1_0.IAuthSecret impl) {
        mImpl = impl;
    }

    @Override
    public void setPrimaryUserCredential(byte[] secret) throws RemoteException {
        final ArrayList<Byte> secretAsArrayList = new ArrayList<>(secret.length);
        for (int i = 0; i < secret.length; ++i) {
            secretAsArrayList.add(secret[i]);
        }
        mImpl.primaryUserCredential(secretAsArrayList);
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        // Supports only V1
        return 1;
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException("AuthSecretHidlAdapter does not support asBinder");
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
        throw new UnsupportedOperationException(
                "AuthSecretHidlAdapter does not support getInterfaceHash");
    }
}
