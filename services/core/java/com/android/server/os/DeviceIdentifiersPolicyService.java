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

package com.android.server.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IDeviceIdentifiersPolicyService;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;

import com.android.internal.telephony.TelephonyPermissions;
import com.android.server.SystemService;

/**
 * This service defines the policy for accessing device identifiers.
 */
public final class DeviceIdentifiersPolicyService extends SystemService {
    public DeviceIdentifiersPolicyService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DEVICE_IDENTIFIERS_SERVICE,
                new DeviceIdentifiersPolicy(getContext()));
    }

    private static final class DeviceIdentifiersPolicy
            extends IDeviceIdentifiersPolicyService.Stub {
        private final @NonNull Context mContext;

        public DeviceIdentifiersPolicy(Context context) {
            mContext = context;
        }

        @Override
        public @Nullable String getSerial() throws RemoteException {
            // Since this invocation is on the server side a null value is used for the
            // callingPackage as the server's package name (typically android) should not be used
            // for any device / profile owner checks. The majority of requests for the serial number
            // should use the getSerialForPackage method with the calling package specified.
            if (!TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers(mContext,
                    /* callingPackage */ null, null, "getSerial")) {
                return Build.UNKNOWN;
            }
            return SystemProperties.get("ro.serialno", Build.UNKNOWN);
        }

        @Override
        public @Nullable String getSerialForPackage(String callingPackage,
                String callingFeatureId) throws RemoteException {
            if (!checkPackageBelongsToCaller(callingPackage)) {
                throw new IllegalArgumentException(
                        "Invalid callingPackage or callingPackage does not belong to caller's uid:"
                                + Binder.getCallingUid());
            }

            if (!TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers(mContext,
                    callingPackage, callingFeatureId, "getSerial")) {
                return Build.UNKNOWN;
            }
            return SystemProperties.get("ro.serialno", Build.UNKNOWN);
        }

        private boolean checkPackageBelongsToCaller(String callingPackage) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = UserHandle.getUserId(callingUid);
            int callingPackageUid;
            try {
                callingPackageUid = mContext.getPackageManager().getPackageUidAsUser(
                        callingPackage, callingUserId);
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }

            return callingPackageUid == callingUid;
        }
    }
}
