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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback;
import android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint;
import android.os.RemoteException;

/**
 * Test HAL that provides only provides no-ops.
 */
public class TestHal extends IBiometricsFingerprint.Stub {
    @Override
    public boolean isUdfps(int sensorId) {
        return false;
    }

    @Override
    public void onFingerDown(int x, int y, float minor, float major) {

    }

    @Override
    public void onFingerUp() {

    }

    @Override
    public long setNotify(IBiometricsFingerprintClientCallback clientCallback) {
        return 0;
    }

    @Override
    public long preEnroll() {
        return 0;
    }

    @Override
    public int enroll(byte[] hat, int gid, int timeoutSec) {
        return 0;
    }

    @Override
    public int postEnroll() {
        return 0;
    }

    @Override
    public long getAuthenticatorId() {
        return 0;
    }

    @Override
    public int cancel() {
        return 0;
    }

    @Override
    public int enumerate() {
        return 0;
    }

    @Override
    public int remove(int gid, int fid) {
        return 0;
    }

    @Override
    public int setActiveGroup(int gid, String storePath) {
        return 0;
    }

    @Override
    public int authenticate(long operationId, int gid) {
        return 0;
    }
}