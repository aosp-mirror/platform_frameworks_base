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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.fingerprint.V2_1.FingerprintError;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback;
import android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint;
import android.hardware.fingerprint.Fingerprint;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;

import java.util.List;

/**
 * Test HAL that provides only provides no-ops.
 */
public class TestHal extends IBiometricsFingerprint.Stub {
    private static final String TAG = "fingerprint.hidl.TestHal";

    @NonNull
    private final Context mContext;
    private final int mSensorId;

    @Nullable
    private IBiometricsFingerprintClientCallback mCallback;

    TestHal(@NonNull Context context, int sensorId) {
        mContext = context;
        mSensorId = sensorId;
    }

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
        mCallback = clientCallback;
        return 0;
    }

    @Override
    public long preEnroll() {
        return 0;
    }

    @Override
    public int enroll(byte[] hat, int gid, int timeoutSec) {
        Slog.w(TAG, "enroll");
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
    public int cancel() throws RemoteException {
        if (mCallback != null) {
            mCallback.onError(0, FingerprintError.ERROR_CANCELED, 0 /* vendorCode */);
        }
        return 0;
    }

    @Override
    public int enumerate() throws RemoteException {
        Slog.w(TAG, "Enumerate");
        if (mCallback != null) {
            mCallback.onEnumerate(0 /* deviceId */, 0 /* fingerId */, 0 /* groupId */,
                    0 /* remaining */);
        }
        return 0;
    }

    @Override
    public int remove(int gid, int fid) throws RemoteException {
        Slog.w(TAG, "Remove");
        if (mCallback != null) {
            if (fid == 0) {
                // For this HAL interface, remove(0) means to remove all enrollments.
                final List<Fingerprint> fingerprints = FingerprintUtils.getInstance(mSensorId)
                        .getBiometricsForUser(mContext, gid);
                for (int i = 0; i < fingerprints.size(); i++) {
                    final Fingerprint fp = fingerprints.get(i);
                    mCallback.onRemoved(0 /* deviceId */, fp.getBiometricId(), gid,
                            fingerprints.size() - i - 1);
                }
            } else {
                mCallback.onRemoved(0 /* deviceId */, fid, gid, 0 /* remaining */);
            }
        }
        return 0;
    }

    @Override
    public int setActiveGroup(int gid, String storePath) {
        return 0;
    }

    @Override
    public int authenticate(long operationId, int gid) {
        Slog.w(TAG, "Authenticate");
        return 0;
    }
}