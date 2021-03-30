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

import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.fingerprint.Error;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.ISessionCallback;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Test HAL that provides only provides no-ops.
 */
public class TestHal extends IFingerprint.Stub {
    private static final String TAG = "fingerprint.aidl.TestHal";

    @Override
    public SensorProps[] getSensorProps() {
        Slog.w(TAG, "getSensorProps");
        return new SensorProps[0];
    }

    @Override
    public ISession createSession(int sensorId, int userId, ISessionCallback cb) {
        Slog.w(TAG, "createSession, sensorId: " + sensorId + " userId: " + userId);

        return new ISession.Stub() {
            @Override
            public void generateChallenge() throws RemoteException {
                Slog.w(TAG, "generateChallenge");
                cb.onChallengeGenerated(0L);
            }

            @Override
            public void revokeChallenge(long challenge) throws RemoteException {
                Slog.w(TAG, "revokeChallenge: " + challenge);
                cb.onChallengeRevoked(challenge);
            }

            @Override
            public ICancellationSignal enroll(HardwareAuthToken hat) {
                Slog.w(TAG, "enroll");
                return new ICancellationSignal.Stub() {
                    @Override
                    public void cancel() throws RemoteException {
                        cb.onError(Error.CANCELED, 0 /* vendorCode */);
                    }
                };
            }

            @Override
            public ICancellationSignal authenticate(long operationId) {
                Slog.w(TAG, "authenticate");
                return new ICancellationSignal.Stub() {
                    @Override
                    public void cancel() throws RemoteException {
                        cb.onError(Error.CANCELED, 0 /* vendorCode */);
                    }
                };
            }

            @Override
            public ICancellationSignal detectInteraction() {
                Slog.w(TAG, "detectInteraction");
                return new ICancellationSignal.Stub() {
                    @Override
                    public void cancel() throws RemoteException {
                        cb.onError(Error.CANCELED, 0 /* vendorCode */);
                    }
                };
            }

            @Override
            public void enumerateEnrollments() throws RemoteException {
                Slog.w(TAG, "enumerateEnrollments");
                cb.onEnrollmentsEnumerated(new int[0]);
            }

            @Override
            public void removeEnrollments(int[] enrollmentIds) throws RemoteException {
                Slog.w(TAG, "removeEnrollments");
                cb.onEnrollmentsRemoved(enrollmentIds);
            }

            @Override
            public void getAuthenticatorId() throws RemoteException {
                Slog.w(TAG, "getAuthenticatorId");
                cb.onAuthenticatorIdRetrieved(0L);
            }

            @Override
            public void invalidateAuthenticatorId() throws RemoteException {
                Slog.w(TAG, "invalidateAuthenticatorId");
                cb.onAuthenticatorIdInvalidated(0L);
            }

            @Override
            public void resetLockout(HardwareAuthToken hat) throws RemoteException {
                Slog.w(TAG, "resetLockout");
                cb.onLockoutCleared();
            }

            @Override
            public void close() throws RemoteException {
                Slog.w(TAG, "close");
                cb.onSessionClosed();
            }

            @Override
            public void onPointerDown(int pointerId, int x, int y, float minor, float major) {
                Slog.w(TAG, "onPointerDown");
            }

            @Override
            public void onPointerUp(int pointerId) {
                Slog.w(TAG, "onPointerUp");
            }

            @Override
            public void onUiReady() {
                Slog.w(TAG, "onUiReady");
            }
        };
    }
}

