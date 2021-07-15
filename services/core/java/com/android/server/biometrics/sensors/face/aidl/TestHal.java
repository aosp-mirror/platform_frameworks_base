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
import android.hardware.biometrics.face.EnrollmentStageConfig;
import android.hardware.biometrics.face.Error;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.ISessionCallback;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.common.NativeHandle;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Test HAL that provides only no-ops.
 */
public class TestHal extends IFace.Stub {
    private static final String TAG = "face.aidl.TestHal";

    @Override
    public int getInterfaceVersion() {
        return this.VERSION;
    }

    @Override
    public String getInterfaceHash() {
        return this.HASH;
    }

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
            public int getInterfaceVersion() {
                return this.VERSION;
            }

            @Override
            public String getInterfaceHash() {
                return this.HASH;
            }

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
            public EnrollmentStageConfig[] getEnrollmentConfig(byte enrollmentType) {
                return new EnrollmentStageConfig[0];
            }

            @Override
            public ICancellationSignal enroll(HardwareAuthToken hat,
                    byte enrollmentType, byte[] features, NativeHandle previewSurface) {
                Slog.w(TAG, "enroll");
                return new ICancellationSignal.Stub() {
                    @Override
                    public void cancel() throws RemoteException {
                        cb.onError(Error.CANCELED, 0 /* vendorCode */);
                    }
                    @Override
                    public int getInterfaceVersion() {
                        return this.VERSION;
                    }
                    @Override
                    public String getInterfaceHash() {
                        return this.HASH;
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
                    @Override
                    public int getInterfaceVersion() {
                        return this.VERSION;
                    }
                    @Override
                    public String getInterfaceHash() {
                        return this.HASH;
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
                    @Override
                    public int getInterfaceVersion() {
                        return this.VERSION;
                    }
                    @Override
                    public String getInterfaceHash() {
                        return this.HASH;
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
            public void getFeatures() throws RemoteException {
                Slog.w(TAG, "getFeatures");
                cb.onFeaturesRetrieved(new byte[0]);
            }

            @Override
            public void setFeature(HardwareAuthToken hat, byte feature, boolean enabled)
                    throws RemoteException {
                Slog.w(TAG, "setFeature");
                cb.onFeatureSet(feature);
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
        };
    }
}
