/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.security;

import static android.Manifest.permission.USE_ATTESTATION_VERIFICATION_SERVICE;
import static android.security.attestationverification.AttestationVerificationManager.PROFILE_PEER_DEVICE;
import static android.security.attestationverification.AttestationVerificationManager.PROFILE_SELF_TRUSTED;
import static android.security.attestationverification.AttestationVerificationManager.RESULT_FAILURE;
import static android.security.attestationverification.AttestationVerificationManager.RESULT_UNKNOWN;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelDuration;
import android.os.RemoteException;
import android.security.attestationverification.AttestationProfile;
import android.security.attestationverification.IAttestationVerificationManagerService;
import android.security.attestationverification.IVerificationResult;
import android.security.attestationverification.VerificationToken;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;
import com.android.server.SystemService;

/**
 * A {@link SystemService} which provides functionality related to verifying attestations of
 * (usually) remote computing environments.
 *
 * @hide
 */
public class AttestationVerificationManagerService extends SystemService {

    private static final String TAG = "AVF";
    private final AttestationVerificationPeerDeviceVerifier mPeerDeviceVerifier;

    public AttestationVerificationManagerService(final Context context) throws Exception {
        super(context);
        mPeerDeviceVerifier = new AttestationVerificationPeerDeviceVerifier(context);
    }

    private final IBinder mService = new IAttestationVerificationManagerService.Stub() {
        @Override
        public void verifyAttestation(
                AttestationProfile profile,
                int localBindingType,
                Bundle requirements,
                byte[] attestation,
                AndroidFuture resultCallback) throws RemoteException {
            enforceUsePermission();
            try {
                Slog.d(TAG, "verifyAttestation");
                verifyAttestationForAllVerifiers(profile, localBindingType, requirements,
                        attestation, resultCallback);
            } catch (Throwable t) {
                Slog.e(TAG, "failed to verify attestation", t);
                throw ExceptionUtils.propagate(t, RemoteException.class);
            }
        }

        @Override
        public void verifyToken(VerificationToken token, ParcelDuration parcelDuration,
                AndroidFuture resultCallback) throws RemoteException {
            enforceUsePermission();
            // TODO(b/201696614): Implement
            resultCallback.complete(RESULT_UNKNOWN);
        }

        private void enforceUsePermission() {
            getContext().enforceCallingOrSelfPermission(USE_ATTESTATION_VERIFICATION_SERVICE, null);
        }
    };

    private void verifyAttestationForAllVerifiers(
            AttestationProfile profile, int localBindingType, Bundle requirements,
            byte[] attestation, AndroidFuture<IVerificationResult> resultCallback) {
        IVerificationResult result = new IVerificationResult();
        // TODO(b/201696614): Implement
        result.token = null;
        switch (profile.getAttestationProfileId()) {
            case PROFILE_SELF_TRUSTED:
                Slog.d(TAG, "Verifying Self Trusted profile.");
                try {
                    result.resultCode =
                            AttestationVerificationSelfTrustedVerifierForTesting.getInstance()
                                    .verifyAttestation(localBindingType, requirements, attestation);
                } catch (Throwable t) {
                    result.resultCode = RESULT_FAILURE;
                }
                break;
            case PROFILE_PEER_DEVICE:
                Slog.d(TAG, "Verifying Peer Device profile.");
                result.resultCode = mPeerDeviceVerifier.verifyAttestation(
                        localBindingType, requirements, attestation);
                break;
            default:
                Slog.d(TAG, "No profile found, defaulting.");
                result.resultCode = RESULT_UNKNOWN;
        }
        resultCallback.complete(result);
    }

    @Override
    public void onStart() {
        Slog.d(TAG, "Started");
        publishBinderService(Context.ATTESTATION_VERIFICATION_SERVICE, mService);
    }
}
