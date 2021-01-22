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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.Error;
import android.hardware.biometrics.face.ISession;
import android.hardware.common.NativeHandle;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.Binder;
import android.os.IBinder;
import android.util.Slog;

/**
 * Test session that provides mostly no-ops.
 */
public class TestSession extends ISession.Stub {
    private static final String TAG = "FaceTestSession";

    @NonNull
    private final Sensor.HalSessionCallback mHalSessionCallback;

    TestSession(@NonNull Sensor.HalSessionCallback halSessionCallback) {
        mHalSessionCallback = halSessionCallback;
    }

    @Override
    public void generateChallenge(int cookie, int timeoutSec) {
        mHalSessionCallback.onChallengeGenerated(0 /* challenge */);
    }

    @Override
    public void revokeChallenge(int cookie, long challenge) {
        mHalSessionCallback.onChallengeRevoked(challenge);
    }

    @Override
    public ICancellationSignal enroll(int cookie, HardwareAuthToken hat, byte enrollmentType,
            byte[] features, NativeHandle previewSurface) {
        return null;
    }

    @Override
    public ICancellationSignal authenticate(int cookie, long operationId) {
        return new ICancellationSignal() {
            @Override
            public void cancel() {
                mHalSessionCallback.onError(Error.CANCELED, 0 /* vendorCode */);
            }

            @Override
            public IBinder asBinder() {
                return new Binder();
            }
        };
    }

    @Override
    public ICancellationSignal detectInteraction(int cookie) {
        return null;
    }

    @Override
    public void enumerateEnrollments(int cookie) {

    }

    @Override
    public void removeEnrollments(int cookie, int[] enrollmentIds) {

    }

    @Override
    public void getFeatures(int cookie, int enrollmentId) {

    }

    @Override
    public void setFeature(int cookie, HardwareAuthToken hat, int enrollmentId, byte feature,
            boolean enabled) {

    }

    @Override
    public void getAuthenticatorId(int cookie) {
        Slog.d(TAG, "getAuthenticatorId");
        // Immediately return a value so the framework can continue with subsequent requests.
        mHalSessionCallback.onAuthenticatorIdRetrieved(0);
    }

    @Override
    public void invalidateAuthenticatorId(int cookie) {
        Slog.d(TAG, "invalidateAuthenticatorId");
        // Immediately return a value so the framework can continue with subsequent requests.
        mHalSessionCallback.onAuthenticatorIdInvalidated(0);
    }

    @Override
    public void resetLockout(int cookie, HardwareAuthToken hat) {
        mHalSessionCallback.onLockoutCleared();
    }
}
