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

package com.android.server.biometrics.sensors;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;

import java.util.List;

/**
 * Interface for utilities managing biometrics and their relevant settings.
 * @hide
 */
public interface BiometricUtils<T extends BiometricAuthenticator.Identifier> {
    List<T> getBiometricsForUser(Context context, int userId);
    void addBiometricForUser(Context context, int userId, T identifier);
    void removeBiometricForUser(Context context, int userId, int biometricId);
    void renameBiometricForUser(Context context, int userId, int biometricId, CharSequence name);
    CharSequence getUniqueName(Context context, int userId);
    void setInvalidationInProgress(Context context, int userId, boolean inProgress);
    boolean isInvalidationInProgress(Context context, int userId);

    /**
     * Return true if the biometric file is correctly read. Otherwise return false.
     */
    boolean hasValidBiometricUserState(Context context, int userId);

    /**
     * Delete the file of the biometric state.
     */
    void deleteStateForUser(int userId);
}