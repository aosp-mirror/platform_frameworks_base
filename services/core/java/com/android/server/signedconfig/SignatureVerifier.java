/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.signedconfig;

import android.os.Build;
import android.util.Slog;
import android.util.StatsLog;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Helper class for verifying config signatures.
 */
public class SignatureVerifier {

    private static final String TAG = "SignedConfig";
    private static final boolean DBG = false;

    private static final String DEBUG_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmJKs4lSn+XRhMQmMid+Zbhbu13YrU1haIhVC5296InRu1"
            + "x7A8PV1ejQyisBODGgRY6pqkAHRncBCYcgg5wIIJg==";
    private static final String PROD_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+lky6wKyGL6lE1VrD0YTMHwb0Xwc+tzC8MvnrzVxodvTp"
            + "VY/jV7V+Zktcx+pry43XPABFRXtbhTo+qykhyBA1g==";

    private final SignedConfigEvent mEvent;
    private final PublicKey mDebugKey;
    private final PublicKey mProdKey;

    public SignatureVerifier(SignedConfigEvent event) {
        mEvent = event;
        mDebugKey = Build.IS_DEBUGGABLE ? createKey(DEBUG_KEY) : null;
        mProdKey = createKey(PROD_KEY);
    }

    private static PublicKey createKey(String base64) {
        EncodedKeySpec keySpec;
        try {
            byte[] key = Base64.getDecoder().decode(base64);
            keySpec = new X509EncodedKeySpec(key);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Failed to base64 decode public key", e);
            return null;
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            return factory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            Slog.e(TAG, "Failed to construct public key", e);
            return null;
        }
    }

    private boolean verifyWithPublicKey(PublicKey key, byte[] data, byte[] signature)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(data);
        return verifier.verify(signature);
    }

    /**
     * Verify a signature for signed config.
     *
     * @param config Config as read from APK meta-data.
     * @param base64Signature Signature as read from APK meta-data.
     * @return {@code true} iff the signature was successfully verified.
     */
    public boolean verifySignature(String config, String base64Signature)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(base64Signature);
        } catch (IllegalArgumentException e) {
            mEvent.status = StatsLog.SIGNED_CONFIG_REPORTED__STATUS__BASE64_FAILURE_SIGNATURE;
            Slog.e(TAG, "Failed to base64 decode signature");
            return false;
        }
        byte[] data = config.getBytes(StandardCharsets.UTF_8);
        if (DBG) Slog.i(TAG, "Data: " + Base64.getEncoder().encodeToString(data));

        if (Build.IS_DEBUGGABLE) {
            if (mDebugKey != null) {
                if (DBG) Slog.w(TAG, "Trying to verify signature using debug key");
                if (verifyWithPublicKey(mDebugKey, data, signature)) {
                    Slog.i(TAG, "Verified config using debug key");
                    mEvent.verifiedWith = StatsLog.SIGNED_CONFIG_REPORTED__VERIFIED_WITH__DEBUG;
                    return true;
                } else {
                    if (DBG) Slog.i(TAG, "Config verification failed using debug key");
                }
            } else {
                Slog.w(TAG, "Debuggable build, but have no debug key");
            }
        }
        if (mProdKey ==  null) {
            Slog.e(TAG, "No prod key; construction failed?");
            mEvent.status =
                    StatsLog.SIGNED_CONFIG_REPORTED__STATUS__SIGNATURE_CHECK_FAILED_PROD_KEY_ABSENT;
            return false;
        }
        if (verifyWithPublicKey(mProdKey, data, signature)) {
            Slog.i(TAG, "Verified config using production key");
            mEvent.verifiedWith = StatsLog.SIGNED_CONFIG_REPORTED__VERIFIED_WITH__PRODUCTION;
            return true;
        } else {
            if (DBG) Slog.i(TAG, "Verification failed using production key");
            mEvent.status = StatsLog.SIGNED_CONFIG_REPORTED__STATUS__SIGNATURE_CHECK_FAILED;
            return false;
        }
    }
}
