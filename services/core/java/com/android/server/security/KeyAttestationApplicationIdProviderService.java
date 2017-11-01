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


package com.android.server.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.security.keymaster.KeyAttestationPackageInfo;
import android.security.keymaster.KeyAttestationApplicationId;
import android.security.keymaster.IKeyAttestationApplicationIdProvider;

/**
 * @hide
 * The KeyAttestationApplicationIdProviderService provides information describing the possible
 * applications identified by a UID. Due to UID sharing, this KeyAttestationApplicationId can
 * comprise information about multiple packages. The Information is used by keystore to describe
 * the initiating application of a key attestation procedure.
 */
public class KeyAttestationApplicationIdProviderService
        extends IKeyAttestationApplicationIdProvider.Stub {

    public KeyAttestationApplicationIdProviderService(Context context) {
        mPackageManager = context.getPackageManager();
    }

    private PackageManager mPackageManager;

    public KeyAttestationApplicationId getKeyAttestationApplicationId(int uid)
            throws RemoteException {
        if (Binder.getCallingUid() != android.os.Process.KEYSTORE_UID) {
            throw new SecurityException("This service can only be used by Keystore");
        }
        KeyAttestationPackageInfo[] keyAttestationPackageInfos = null;
        final long token = Binder.clearCallingIdentity();
        try {
            String[] packageNames = mPackageManager.getPackagesForUid(uid);
            if (packageNames == null) {
                throw new RemoteException("No packages for uid");
            }
            int userId = UserHandle.getUserId(uid);
            keyAttestationPackageInfos = new KeyAttestationPackageInfo[packageNames.length];

            for (int i = 0; i < packageNames.length; ++i) {
                PackageInfo packageInfo = mPackageManager.getPackageInfoAsUser(packageNames[i],
                        PackageManager.GET_SIGNATURES, userId);
                keyAttestationPackageInfos[i] = new KeyAttestationPackageInfo(packageNames[i],
                        packageInfo.versionCode, packageInfo.signatures);
            }
        } catch (NameNotFoundException nnfe) {
            throw new RemoteException(nnfe.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return new KeyAttestationApplicationId(keyAttestationPackageInfos);
    }
}
