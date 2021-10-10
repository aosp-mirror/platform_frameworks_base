/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IInvalidationCallback;

import com.android.server.biometrics.BiometricsProto;

/**
 * ClientMonitor subclass responsible for coordination of authenticatorId invalidation of other
 * sensors. See {@link InvalidationClient} for the ClientMonitor subclass responsible for initiating
 * the invalidation with individual HALs. AuthenticatorId invalidation is required on devices with
 * multiple strong biometric sensors.
 *
 * The public Keystore and Biometric APIs are biometric-tied, not modality-tied, meaning that keys
 * are unlockable by "any/all strong biometrics on the device", and not "only a specific strong
 * sensor". The Keystore API allows for creation of biometric-tied keys that are invalidated upon
 * new biometric enrollment. See
 * {@link android.security.keystore.KeyGenParameterSpec.Builder#setInvalidatedByBiometricEnrollment}
 *
 * This has been supported on single-sensor devices by the various getAuthenticatorId APIs on the
 * HIDL and AIDL biometric HAL interfaces, where:
 * 1) authenticatorId is requested and stored during key generation
 * 2) authenticatorId is contained within the HAT when biometric authentication succeeds
 * 3) authenticatorId is automatically changed (below the framework) whenever a new biometric
 *    enrollment occurs.
 *
 * For multi-biometric devices, this will be done the following way:
 * 1) New enrollment added for Sensor1. Sensor1's HAL/TEE updates its authenticatorId automatically
 *    when enrollment completes
 * 2) Framework marks Sensor1 as "invalidationInProgress". See
 *    {@link BiometricUtils#setInvalidationInProgress(Context, int, boolean)}
 * 3) After all other sensors have finished invalidation, the framework will clear the invalidation
 *    flag for Sensor1.
 * 4) New keys that are generated will include all new authenticatorIds
 *
 * The above is robust to incomplete invalidation. For example, when system boots or after user
 * switches, the framework can check if any sensor has the "invalidationInProgress" flag set. If so,
 * the framework should re-start the invalidation process described above.
 */
public class InvalidationRequesterClient<S extends BiometricAuthenticator.Identifier>
        extends BaseClientMonitor {

    private final BiometricManager mBiometricManager;
    @NonNull private final BiometricUtils<S> mUtils;

    @NonNull private final IInvalidationCallback mInvalidationCallback =
            new IInvalidationCallback.Stub() {
        @Override
        public void onCompleted() {
            mUtils.setInvalidationInProgress(getContext(), getTargetUserId(),
                    false /* inProgress */);
            mCallback.onClientFinished(InvalidationRequesterClient.this, true /* success */);
        }
    };

    public InvalidationRequesterClient(@NonNull Context context, int userId, int sensorId,
            @NonNull BiometricUtils<S> utils) {
        super(context, null /* token */, null /* listener */, userId,
                context.getOpPackageName(), 0 /* cookie */, sensorId,
                BiometricsProtoEnums.MODALITY_UNKNOWN, BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mBiometricManager = context.getSystemService(BiometricManager.class);
        mUtils = utils;
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);

        mUtils.setInvalidationInProgress(getContext(), getTargetUserId(), true /* inProgress */);
        mBiometricManager.invalidateAuthenticatorIds(getTargetUserId(), getSensorId(),
                mInvalidationCallback);
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_INVALIDATION_REQUESTER;
    }
}
