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

import android.security.authenticationpolicy.AuthenticationPolicyManager;
import android.security.authenticationpolicy.AuthenticationPolicyManager.DisableSecureLockDeviceRequestStatus;
import android.security.authenticationpolicy.AuthenticationPolicyManager.EnableSecureLockDeviceRequestStatus;
import android.security.authenticationpolicy.DisableSecureLockDeviceParams;
import android.security.authenticationpolicy.EnableSecureLockDeviceParams;

/**
 * Local system service interface for {@link SecureLockDeviceService}.
 *
 * <p>No permission / argument checks will be performed inside.
 * Callers must check the calling app permission and the calling package name.
 *
 * @hide
 */
public abstract class SecureLockDeviceServiceInternal {
    private static final String TAG = "SecureLockDeviceServiceInternal";

    /**
     * @see AuthenticationPolicyManager#enableSecureLockDevice(EnableSecureLockDeviceParams)
     * @param params EnableSecureLockDeviceParams for caller to supply params related
     *               to the secure lock request
     * @return @EnableSecureLockDeviceRequestStatus int indicating the result of the
     * secure lock request
     */
    @EnableSecureLockDeviceRequestStatus
    public abstract int enableSecureLockDevice(EnableSecureLockDeviceParams params);

    /**
     * @see AuthenticationPolicyManager#disableSecureLockDevice(DisableSecureLockDeviceParams)
     * @param params @DisableSecureLockDeviceParams for caller to supply params related
     *               to the secure lock device request
     * @return @DisableSecureLockDeviceRequestStatus int indicating the result of the
     * secure lock device request
     */
    @DisableSecureLockDeviceRequestStatus
    public abstract int disableSecureLockDevice(DisableSecureLockDeviceParams params);
}
