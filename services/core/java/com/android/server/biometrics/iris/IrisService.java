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
 * limitations under the License
 */

package com.android.server.biometrics.iris;

import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;

import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
import com.android.server.biometrics.Metrics;

/**
 * A service to manage multiple clients that want to access the Iris HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * iris-related events.
 *
 * TODO: The vendor is expected to fill in the service. See
 * {@link com.android.server.biometrics.fingerprint.FingerprintService}
 *
 * @hide
 */
public class IrisService extends BiometricServiceBase {

    private static final String TAG = "IrisService";

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public IrisService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected BiometricUtils getBiometricUtils() {
        return null;
    }

    @Override
    protected int getFailedAttemptsLockoutTimed() {
        return 0;
    }

    @Override
    protected int getFailedAttemptsLockoutPermanent() {
        return 0;
    }

    @Override
    protected Metrics getMetrics() {
        return null;
    }

    @Override
    protected boolean hasReachedEnrollmentLimit(int userId) {
        return false;
    }

    @Override
    protected void updateActiveGroup(int userId, String clientPackage) {

    }

    @Override
    protected String getLockoutResetIntent() {
        return null;
    }

    @Override
    protected String getLockoutBroadcastPermission() {
        return null;
    }

    @Override
    protected long getHalDeviceId() {
        return 0;
    }

    @Override
    protected void handleUserSwitching(int userId) {

    }

    @Override
    protected boolean hasEnrolledBiometrics(int userId) {
        return false;
    }

    @Override
    protected String getManageBiometricPermission() {
        return null;
    }

    @Override
    protected void checkUseBiometricPermission() {

    }

    @Override
    protected boolean checkAppOps(int uid, String opPackageName) {
        return false;
    }

    @Override
    protected int statsModality() {
        return BiometricsProtoEnums.MODALITY_IRIS;
    }
}
