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

package com.android.server.biometrics.sensors.face.hidl;

import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.face.aidl.AidlResponseHandler;

import java.util.ArrayList;

/**
 * Convert HIDL-specific callback interface {@link IBiometricsFaceClientCallback} to AIDL
 * response handler.
 */
public class HidlToAidlCallbackConverter extends IBiometricsFaceClientCallback.Stub {

    private final AidlResponseHandler mAidlResponseHandler;

    public HidlToAidlCallbackConverter(AidlResponseHandler aidlResponseHandler) {
        mAidlResponseHandler = aidlResponseHandler;
    }

    @Override
    public void onEnrollResult(
            long deviceId, int faceId, int userId, int remaining) {
        mAidlResponseHandler.onEnrollmentProgress(faceId, remaining);
    }

    @Override
    public void onAuthenticated(long deviceId, int faceId, int userId,
            ArrayList<Byte> token) {
        final boolean authenticated = faceId != 0;
        byte[] hardwareAuthToken = new byte[token.size()];

        for (int i = 0; i < token.size(); i++) {
            hardwareAuthToken[i] = token.get(i);
        }

        if (authenticated) {
            mAidlResponseHandler.onAuthenticationSucceeded(faceId,
                    HardwareAuthTokenUtils.toHardwareAuthToken(hardwareAuthToken));
        } else {
            mAidlResponseHandler.onAuthenticationFailed();
        }
    }

    @Override
    public void onAcquired(long deviceId, int userId, int acquiredInfo,
            int vendorCode) {
        mAidlResponseHandler.onAcquired(acquiredInfo, vendorCode);
    }

    @Override
    public void onError(long deviceId, int userId, int error, int vendorCode) {
        mAidlResponseHandler.onError(error, vendorCode);
    }

    @Override
    public void onRemoved(long deviceId, ArrayList<Integer> removed, int userId) {
        int[] enrollmentIds = new int[removed.size()];
        for (int i = 0; i < removed.size(); i++) {
            enrollmentIds[i] = removed.get(i);
        }
        mAidlResponseHandler.onEnrollmentsRemoved(enrollmentIds);
    }

    @Override
    public void onEnumerate(long deviceId, ArrayList<Integer> faceIds, int userId) {
        int[] enrollmentIds = new int[faceIds.size()];
        for (int i = 0; i < faceIds.size(); i++) {
            enrollmentIds[i] = faceIds.get(i);
        }
        mAidlResponseHandler.onEnrollmentsEnumerated(enrollmentIds);
    }

    @Override
    public void onLockoutChanged(long duration) {
        mAidlResponseHandler.onLockoutChanged(duration);
    }

    void onChallengeGenerated(long challenge) {
        mAidlResponseHandler.onChallengeGenerated(challenge);
    }

    void onChallengeRevoked(long challenge) {
        mAidlResponseHandler.onChallengeRevoked(challenge);
    }

    void onFeatureGet(byte[] features) {
        mAidlResponseHandler.onFeaturesRetrieved(features);
    }

    void onFeatureSet(byte feature) {
        mAidlResponseHandler.onFeatureSet(feature);
    }

    void onAuthenticatorIdRetrieved(long authenticatorId) {
        mAidlResponseHandler.onAuthenticatorIdRetrieved(authenticatorId);
    }

    void onUnsupportedClientScheduled() {
        mAidlResponseHandler.onUnsupportedClientScheduled();
    }
}
