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

package com.android.server.biometrics;

import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.util.Slog;

/**
 * Wraps IBiometricAuthenticator implementation and stores information about the authenticator.
 * TODO(b/141025588): Consider refactoring the tests to not rely on this implementation detail.
 */
public class BiometricSensor {
    private static final String TAG = "BiometricService/Sensor";

    public final int id;
    public final int oemStrength; // strength as configured by the OEM
    public final int modality;
    public final IBiometricAuthenticator impl;

    private int mUpdatedStrength; // strength updated by BiometricStrengthController

    BiometricSensor(int id, int modality, int strength,
            IBiometricAuthenticator impl) {
        this.id = id;
        this.modality = modality;
        this.oemStrength = strength;
        this.impl = impl;

        mUpdatedStrength = strength;
    }

    /**
     * Returns the actual strength, taking any updated strengths into effect. Since more bits
     * means lower strength, the resulting strength is never stronger than the OEM's configured
     * strength.
     * @return a bitfield, see {@link BiometricManager.Authenticators}
     */
    int getActualStrength() {
        return oemStrength | mUpdatedStrength;
    }

    /**
     * Stores the updated strength, which takes effect whenever {@link #getActualStrength()}
     * is checked.
     * @param newStrength
     */
    void updateStrength(int newStrength) {
        String log = "updateStrength: Before(" + toString() + ")";
        mUpdatedStrength = newStrength;
        log += " After(" + toString() + ")";
        Slog.d(TAG, log);
    }

    @Override
    public String toString() {
        return "ID(" + id + ")"
                + " oemStrength: " + oemStrength
                + " updatedStrength: " + mUpdatedStrength
                + " modality " + modality
                + " authenticator: " + impl;
    }
}
