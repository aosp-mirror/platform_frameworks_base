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

package com.android.server.biometrics.sensors;

import android.hardware.biometrics.BiometricManager;

class AuthResult {
    static final int FAILED = 0;
    static final int LOCKED_OUT = 1;
    static final int AUTHENTICATED = 2;
    private final int mStatus;
    private final int mBiometricStrength;

    AuthResult(int status, @BiometricManager.Authenticators.Types int strength) {
        mStatus = status;
        mBiometricStrength = strength;
    }

    int getStatus() {
        return mStatus;
    }

    int getBiometricStrength() {
        return mBiometricStrength;
    }
}
