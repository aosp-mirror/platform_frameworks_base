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
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.ISessionCallback;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.Binder;
import android.os.IBinder;

/**
 * Test HAL that provides only provides no-ops.
 */
public class TestHal extends IFingerprint.Stub {
    @Override
    public SensorProps[] getSensorProps() {
        return new SensorProps[0];
    }

    @Override
    public ISession createSession(int sensorId, int userId, ISessionCallback cb) {
        return new ISession() {
            @Override
            public void generateChallenge(int cookie, int timeoutSec) {

            }

            @Override
            public void revokeChallenge(int cookie, long challenge) {

            }

            @Override
            public ICancellationSignal enroll(int cookie, HardwareAuthToken hat) {
                return null;
            }

            @Override
            public ICancellationSignal authenticate(int cookie, long operationId) {
                return null;
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
            public void getAuthenticatorId(int cookie) {

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

            @Override
            public IBinder asBinder() {
                return new Binder();
            }
        };
    }
}
