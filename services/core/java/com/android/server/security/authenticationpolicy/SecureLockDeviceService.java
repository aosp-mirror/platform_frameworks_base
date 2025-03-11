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

package com.android.server.security.authenticationpolicy;

import android.annotation.NonNull;
import android.content.Context;
import android.security.authenticationpolicy.AuthenticationPolicyManager;
import android.security.authenticationpolicy.AuthenticationPolicyManager.DisableSecureLockDeviceRequestStatus;
import android.security.authenticationpolicy.AuthenticationPolicyManager.EnableSecureLockDeviceRequestStatus;
import android.security.authenticationpolicy.DisableSecureLockDeviceParams;
import android.security.authenticationpolicy.EnableSecureLockDeviceParams;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.SystemService;

/**
 * System service for remotely calling secure lock on the device.
 *
 * Callers will access this class via
 * {@link com.android.server.security.authenticationpolicy.AuthenticationPolicyService}.
 *
 * @see AuthenticationPolicyService
 * @see AuthenticationPolicyManager#enableSecureLockDevice
 * @see AuthenticationPolicyManager#disableSecureLockDevice
 * @hide
 */
public class SecureLockDeviceService extends SecureLockDeviceServiceInternal {
    private static final String TAG = "SecureLockDeviceService";
    private final Context mContext;

    public SecureLockDeviceService(@NonNull Context context) {
        mContext = context;
    }

    private void start() {
        // Expose private service for system components to use.
        LocalServices.addService(SecureLockDeviceServiceInternal.class, this);
    }

    /**
     * @see AuthenticationPolicyManager#enableSecureLockDevice
     * @param params EnableSecureLockDeviceParams for caller to supply params related
     *               to the secure lock device request
     * @return @EnableSecureLockDeviceRequestStatus int indicating the result of the
     * secure lock device request
     *
     * @hide
     */
    @Override
    @EnableSecureLockDeviceRequestStatus
    public int enableSecureLockDevice(EnableSecureLockDeviceParams params) {
        // (1) Call into system_server to lock device, configure allowed auth types
        // for secure lock
        // TODO: lock device, configure allowed authentication types for device entry
        // (2) Call into framework to configure secure lock 2FA lockscreen
        // update, UI & string updates
        // TODO: implement 2FA lockscreen when SceneContainerFlag.isEnabled()
        // TODO: implement 2FA lockscreen when !SceneContainerFlag.isEnabled()
        // (3) Call into framework to configure keyguard security updates
        // TODO: implement security updates
        return AuthenticationPolicyManager.ERROR_UNSUPPORTED;
    }

    /**
     * @see AuthenticationPolicyManager#disableSecureLockDevice
     * @param params @DisableSecureLockDeviceParams for caller to supply params related
     *               to the secure lock device request
     * @return @DisableSecureLockDeviceRequestStatus int indicating the result of the
     * secure lock device request
     *
     * @hide
     */
    @Override
    @DisableSecureLockDeviceRequestStatus
    public int disableSecureLockDevice(DisableSecureLockDeviceParams params) {
        // (1) Call into system_server to reset allowed auth types
        // TODO: reset allowed authentication types for device entry;
        // (2) Call into framework to disable secure lock 2FA lockscreen, reset UI
        // & string updates
        // TODO: implement reverting to normal lockscreen when SceneContainerFlag.isEnabled()
        // TODO: implement reverting to normal lockscreen when !SceneContainerFlag.isEnabled()
        // (3) Call into framework to revert keyguard security updates
        // TODO: implement reverting security updates
        return AuthenticationPolicyManager.ERROR_UNSUPPORTED;
    }

    /**
     * System service lifecycle.
     */
    public static final class Lifecycle extends SystemService {
        private final SecureLockDeviceService mService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mService = new SecureLockDeviceService(context);
        }

        @Override
        public void onStart() {
            Slog.i(TAG, "Starting SecureLockDeviceService");
            mService.start();
            Slog.i(TAG, "Started SecureLockDeviceService");
        }
    }
}
