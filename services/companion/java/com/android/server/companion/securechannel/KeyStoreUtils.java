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

package com.android.server.companion.securechannel;

import static android.security.keystore.KeyProperties.DIGEST_SHA256;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_EC;
import static android.security.keystore.KeyProperties.PURPOSE_SIGN;
import static android.security.keystore.KeyProperties.PURPOSE_VERIFY;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore2.AndroidKeyStoreSpi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;

/**
 * Utility class to help generate, store, and access key-pair for the secure channel. Uses
 * Android Keystore.
 */
final class KeyStoreUtils {
    private static final String TAG = "CDM_SecureChannelKeyStore";
    private static final String ANDROID_KEYSTORE = AndroidKeyStoreSpi.NAME;

    private KeyStoreUtils() {}

    /**
     * Load Android keystore to be used by the secure channel.
     *
     * @return loaded keystore instance
     */
    static KeyStore loadKeyStore() throws GeneralSecurityException {
        KeyStore androidKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE);

        try {
            androidKeyStore.load(null);
        } catch (IOException e) {
            // Should not happen
            throw new KeyStoreException("Failed to load Android Keystore.", e);
        }

        return androidKeyStore;
    }

    /**
     * Fetch the certificate chain encoded as byte array in the form of concatenated
     * X509 certificates.
     *
     * @param alias unique alias for the key-pair entry
     * @return a single byte-array containing the entire certificate chain
     */
    static byte[] getEncodedCertificateChain(String alias) throws GeneralSecurityException {
        KeyStore ks = loadKeyStore();

        Certificate[] certificateChain = ks.getCertificateChain(alias);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (Certificate certificate : certificateChain) {
            buffer.writeBytes(certificate.getEncoded());
        }
        return buffer.toByteArray();
    }

    /**
     * Generate a new attestation key-pair.
     *
     * @param alias unique alias for the key-pair entry
     * @param attestationChallenge challenge value to check against for authentication
     */
    static void generateAttestationKeyPair(String alias, byte[] attestationChallenge)
            throws GeneralSecurityException {
        KeyGenParameterSpec parameterSpec =
                new KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN | PURPOSE_VERIFY)
                        .setAttestationChallenge(attestationChallenge)
                        .setDigests(DIGEST_SHA256)
                        .build();

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
                /* algorithm */ KEY_ALGORITHM_EC,
                /* provider */ ANDROID_KEYSTORE);
        keyPairGenerator.initialize(parameterSpec);
        keyPairGenerator.generateKeyPair();
    }

    /**
     * Check if alias exists.
     *
     * @param alias unique alias for the key-pair entry
     * @return true if given alias already exists in the keystore
     */
    static boolean aliasExists(String alias) {
        try {
            KeyStore ks = loadKeyStore();
            return ks.containsAlias(alias);
        } catch (GeneralSecurityException e) {
            return false;
        }

    }

    static void cleanUp(String alias) {
        try {
            KeyStore ks = loadKeyStore();

            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias);
            }
        } catch (Exception ignored) {
            // Do nothing;
        }
    }
}
