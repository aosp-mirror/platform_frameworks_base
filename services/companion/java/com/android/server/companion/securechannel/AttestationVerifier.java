/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.securechannel;

import static android.security.attestationverification.AttestationVerificationManager.PARAM_CHALLENGE;
import static android.security.attestationverification.AttestationVerificationManager.PROFILE_PEER_DEVICE;
import static android.security.attestationverification.AttestationVerificationManager.TYPE_CHALLENGE;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Bundle;
import android.security.attestationverification.AttestationProfile;
import android.security.attestationverification.AttestationVerificationManager;
import android.security.attestationverification.VerificationToken;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Helper class to perform attestation verification synchronously.
 */
public class AttestationVerifier {
    private static final long ATTESTATION_VERIFICATION_TIMEOUT_SECONDS = 10; // 10 seconds
    private static final String PARAM_OWNED_BY_SYSTEM = "android.key_owned_by_system";

    private final Context mContext;

    AttestationVerifier(Context context) {
        this.mContext = context;
    }

    /**
     * Synchronously verify remote attestation as a suitable peer device on current thread.
     *
     * The peer device must be owned by the Android system and be protected with appropriate
     * public key that this device can verify as attestation challenge.
     *
     * @param remoteAttestation the full certificate chain containing attestation extension.
     * @param attestationChallenge attestation challenge for authentication.
     * @return 1 if attestation is successfully verified; 0 otherwise.
     */
    public int verifyAttestation(
            @NonNull byte[] remoteAttestation,
            @NonNull byte[] attestationChallenge
    ) throws SecureChannelException {
        Bundle requirements = new Bundle();
        requirements.putByteArray(PARAM_CHALLENGE, attestationChallenge);
        requirements.putBoolean(PARAM_OWNED_BY_SYSTEM, true); // Custom parameter for CDM

        // Synchronously execute attestation verification.
        AtomicInteger verificationResult = new AtomicInteger(0);
        CountDownLatch verificationFinished = new CountDownLatch(1);
        BiConsumer<Integer, VerificationToken> onVerificationResult = (result, token) -> {
            verificationResult.set(result);
            verificationFinished.countDown();
        };

        mContext.getSystemService(AttestationVerificationManager.class).verifyAttestation(
                new AttestationProfile(PROFILE_PEER_DEVICE),
                /* localBindingType */ TYPE_CHALLENGE,
                requirements,
                remoteAttestation,
                Runnable::run,
                onVerificationResult
        );

        boolean finished;
        try {
            finished = verificationFinished.await(
                    ATTESTATION_VERIFICATION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException e) {
            throw new SecureChannelException("Attestation verification was interrupted", e);
        }

        if (!finished) {
            throw new SecureChannelException("Attestation verification timed out.");
        }

        return verificationResult.get();
    }
}
