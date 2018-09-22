/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.biometrics;

import static android.Manifest.permission.USE_BIOMETRIC;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.RemoteException;

/**
 * A class that contains biometric utilities. For authentication, see {@link BiometricPrompt}.
 */
public class BiometricManager {

    private final Context mContext;
    private final IBiometricService mService;

    /**
     * @hide
     * @param context
     * @param service
     */
    public BiometricManager(Context context, IBiometricService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Determine if there is at least one biometric enrolled.
     *
     * @return true if at least one biometric is enrolled, false otherwise
     */
    @RequiresPermission(USE_BIOMETRIC)
    public boolean hasEnrolledBiometrics() {
        try {
            return mService.hasEnrolledBiometrics();
        } catch (RemoteException e) {
            return false;
        }
    }
}
