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
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaAn2XVifsLTHg616nTsOMVmlhBoECGbTEBTKKvdd2hO60"
            + "pj1pnU8SMkhYfaNxZuKgw9LNvOwlFwStboIYeZ3lQ==";

    private final SignedConfigEvent mEvent;
    private final PublicKey mDebugKey;

    public SignatureVerifier(SignedConfigEvent event) {
        mEvent = event;
        mDebugKey = createKey(DEBUG_KEY);
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
                Signature verifier = Signature.getInstance("SHA256withECDSA");
                verifier.initVerify(mDebugKey);
                verifier.update(data);
                if (verifier.verify(signature)) {
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
        // TODO verify production key.
        Slog.w(TAG, "NO PRODUCTION KEY YET, FAILING VERIFICATION");
        mEvent.status = StatsLog.SIGNED_CONFIG_REPORTED__STATUS__SIGNATURE_CHECK_FAILED;
        return false;
    }
}
