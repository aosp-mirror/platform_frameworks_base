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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IDeviceIdentifiersPolicyService;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
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
            if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID
                    && mContext.checkCallingOrSelfPermission(
                            Manifest.permission.READ_PHONE_STATE)
                                    != PackageManager.PERMISSION_GRANTED
                    && mContext.checkCallingOrSelfPermission(
                            Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
                                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("getSerial requires READ_PHONE_STATE"
                        + " or READ_PRIVILEGED_PHONE_STATE permission");
            }
            return SystemProperties.get("ro.serialno", Build.UNKNOWN);
        }
    }
}
