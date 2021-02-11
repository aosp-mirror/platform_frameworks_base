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

import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.ISessionCallback;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.common.NativeHandle;
import android.hardware.keymaster.HardwareAuthToken;
import android.util.Slog;

/**
 * Test HAL that provides only no-ops.
 */
public class TestHal extends IFace.Stub {
    private static final String TAG = "face.aidl.TestHal";
    @Override
    public SensorProps[] getSensorProps() {
        Slog.w(TAG, "getSensorProps");
        return new SensorProps[0];
    }

    @Override
    public ISession createSession(int sensorId, int userId, ISessionCallback cb) {
        return new ISession.Stub() {
            @Override
            public void generateChallenge(int cookie, int timeoutSec) {
                Slog.w(TAG, "generateChallenge, cookie: " + cookie);
            }

            @Override
            public void revokeChallenge(int cookie, long challenge) {
                Slog.w(TAG, "revokeChallenge: " + challenge + ", cookie: " + cookie);
            }

            @Override
            public ICancellationSignal enroll(int cookie, HardwareAuthToken hat,
                    byte enrollmentType, byte[] features, NativeHandle previewSurface) {
                Slog.w(TAG, "enroll, cookie: " + cookie);
                return null;
            }

            @Override
            public ICancellationSignal authenticate(int cookie, long operationId) {
                Slog.w(TAG, "authenticate, cookie: " + cookie);
                return null;
            }

            @Override
            public ICancellationSignal detectInteraction(int cookie) {
                Slog.w(TAG, "detectInteraction, cookie: " + cookie);
                return null;
            }

            @Override
            public void enumerateEnrollments(int cookie) {
                Slog.w(TAG, "enumerateEnrollments, cookie: " + cookie);
            }

            @Override
            public void removeEnrollments(int cookie, int[] enrollmentIds) {
                Slog.w(TAG, "removeEnrollments, cookie: " + cookie);
            }

            @Override
            public void getFeatures(int cookie, int enrollmentId) {
                Slog.w(TAG, "getFeatures, cookie: " + cookie);
            }

            @Override
            public void setFeature(int cookie, HardwareAuthToken hat, int enrollmentId,
                    byte feature, boolean enabled) {
                Slog.w(TAG, "setFeature, cookie: " + cookie);
            }

            @Override
            public void getAuthenticatorId(int cookie) {
                Slog.w(TAG, "getAuthenticatorId, cookie: " + cookie);
            }

            @Override
            public void invalidateAuthenticatorId(int cookie) {
                Slog.w(TAG, "invalidateAuthenticatorId, cookie: " + cookie);
            }

            @Override
            public void resetLockout(int cookie, HardwareAuthToken hat) {
                Slog.w(TAG, "resetLockout, cookie: " + cookie);
            }
        };
    }
}
