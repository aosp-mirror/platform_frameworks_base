/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.hardware.biometrics.fingerprint.V2_2.IBiometricsFingerprintClientCallback;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlResponseHandler;

import java.util.ArrayList;

/**
 * Convert HIDL-specific callback interface {@link IBiometricsFingerprintClientCallback} to AIDL
 * response handler.
 */
public class HidlToAidlCallbackConverter extends IBiometricsFingerprintClientCallback.Stub {

    final AidlResponseHandler mAidlResponseHandler;

    public HidlToAidlCallbackConverter(@NonNull AidlResponseHandler aidlResponseHandler) {
        mAidlResponseHandler = aidlResponseHandler;
    }

    @Override
    public void onEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
        mAidlResponseHandler.onEnrollmentProgress(fingerId, remaining);
    }

    @Override
    public void onAcquired(long deviceId, int acquiredInfo, int vendorCode) {
        onAcquired_2_2(deviceId, acquiredInfo, vendorCode);
    }

    @Override
    public void onAcquired_2_2(long deviceId, int acquiredInfo, int vendorCode) {
        mAidlResponseHandler.onAcquired(acquiredInfo, vendorCode);
    }

    @Override
    public void onAuthenticated(long deviceId, int fingerId, int groupId,
            ArrayList<Byte> token) {
        if (fingerId != 0) {
            byte[] hardwareAuthToken = new byte[token.size()];
            for (int i = 0; i < token.size(); i++) {
                hardwareAuthToken[i] = token.get(i);
            }
            mAidlResponseHandler.onAuthenticationSucceeded(fingerId,
                    HardwareAuthTokenUtils.toHardwareAuthToken(hardwareAuthToken));
        } else {
            mAidlResponseHandler.onAuthenticationFailed();
        }
    }

    @Override
    public void onError(long deviceId, int error, int vendorCode) {
        mAidlResponseHandler.onError(error, vendorCode);
    }

    @Override
    public void onRemoved(long deviceId, int fingerId, int groupId, int remaining) {
        mAidlResponseHandler.onEnrollmentsRemoved(new int[]{fingerId});
    }

    @Override
    public void onEnumerate(long deviceId, int fingerId, int groupId, int remaining) {
        mAidlResponseHandler.onEnrollmentsEnumerated(new int[]{fingerId});
    }

    void onChallengeGenerated(long challenge) {
        mAidlResponseHandler.onChallengeGenerated(challenge);
    }

    void onChallengeRevoked(long challenge) {
        mAidlResponseHandler.onChallengeRevoked(challenge);
    }

    void onResetLockout() {
        mAidlResponseHandler.onLockoutCleared();
    }
}
