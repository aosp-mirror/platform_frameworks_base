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

package com.android.server.biometrics.sensors.face.hidl;

import android.annotation.Nullable;
import android.hardware.biometrics.face.V1_0.FaceError;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.OptionalUint64;
import android.hardware.biometrics.face.V1_0.Status;
import android.os.NativeHandle;
import android.os.RemoteException;

import java.util.ArrayList;

public class TestHal extends IBiometricsFace.Stub {
    @Nullable
    private IBiometricsFaceClientCallback mCallback;

    @Override
    public OptionalUint64 setCallback(IBiometricsFaceClientCallback clientCallback) {
        mCallback = clientCallback;
        final OptionalUint64 result = new OptionalUint64();
        result.status = Status.OK;
        return new OptionalUint64();
    }

    @Override
    public int setActiveUser(int userId, String storePath) {
        return 0;
    }

    @Override
    public OptionalUint64 generateChallenge(int challengeTimeoutSec) {
        final OptionalUint64 result = new OptionalUint64();
        result.status = Status.OK;
        result.value = 0;
        return result;
    }

    @Override
    public int enroll(ArrayList<Byte> hat, int timeoutSec, ArrayList<Integer> disabledFeatures) {
        return 0;
    }

    @Override
    public int revokeChallenge() {
        return 0;
    }

    @Override
    public int setFeature(int feature, boolean enabled, ArrayList<Byte> hat, int faceId) {
        return 0;
    }

    @Override
    public OptionalBool getFeature(int feature, int faceId) {
        final OptionalBool result = new OptionalBool();
        result.status = Status.OK;
        result.value = true;
        return result;
    }

    @Override
    public OptionalUint64 getAuthenticatorId() {
        final OptionalUint64 result = new OptionalUint64();
        result.status = Status.OK;
        result.value = 0;
        return result;
    }

    @Override
    public int cancel() throws RemoteException {
        if (mCallback != null) {
            mCallback.onError(0 /* deviceId */, 0 /* userId */, FaceError.CANCELED,
                    0 /* vendorCode */);
        }
        return 0;
    }

    @Override
    public int enumerate() {
        return 0;
    }

    @Override
    public int remove(int faceId) {
        return 0;
    }

    @Override
    public int authenticate(long operationId) {
        return 0;
    }

    @Override
    public int userActivity() {
        return 0;
    }

    @Override
    public int resetLockout(ArrayList<Byte> hat) {
        return 0;
    }

}
