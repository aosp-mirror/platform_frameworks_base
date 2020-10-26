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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.keymaster.HardwareAuthToken;
import android.util.Slog;

/**
 * Test HAL that provides only provides mostly no-ops.
 */
class TestSession extends ISession.Stub {

    private static final String TAG = "TestSession";

    @NonNull private final Sensor.HalSessionCallback mHalSessionCallback;

    TestSession(@NonNull Sensor.HalSessionCallback halSessionCallback) {
        mHalSessionCallback = halSessionCallback;
    }

    @Override
    public void generateChallenge(int cookie, int timeoutSec) {

    }

    @Override
    public void revokeChallenge(int cookie, long challenge) {

    }

    @Override
    public ICancellationSignal enroll(int cookie, HardwareAuthToken hat) {
        Slog.d(TAG, "enroll");
        return null;
    }

    @Override
    public ICancellationSignal authenticate(int cookie, long operationId) {
        Slog.d(TAG, "authenticate");
        return null;
    }

    @Override
    public ICancellationSignal detectInteraction(int cookie) {
        return null;
    }

    @Override
    public void enumerateEnrollments(int cookie) {
        Slog.d(TAG, "enumerate");
    }

    @Override
    public void removeEnrollments(int cookie, int[] enrollmentIds) {
        Slog.d(TAG, "remove");
    }

    @Override
    public void getAuthenticatorId(int cookie) {
        Slog.d(TAG, "getAuthenticatorId");
        // Immediately return a value so the framework can continue with subsequent requests.
        mHalSessionCallback.onAuthenticatorIdRetrieved(0);
    }

    @Override
    public void invalidateAuthenticatorId(int cookie, HardwareAuthToken hat) {

    }

    @Override
    public void resetLockout(int cookie, HardwareAuthToken hat) {

    }

    @Override
    public void onPointerDown(int pointerId, int x, int y, float minor, float major) {

    }

    @Override
    public void onPointerUp(int pointerId) {

    }

    @Override
    public void onUiReady() {

    }
}
