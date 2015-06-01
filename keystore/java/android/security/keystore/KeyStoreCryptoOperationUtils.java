/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keystore;

import android.security.KeyStore;
import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;

/**
 * Assorted utility methods for implementing crypto operations on top of KeyStore.
 *
 * @hide
 */
abstract class KeyStoreCryptoOperationUtils {

    private static volatile SecureRandom sRng;

    private KeyStoreCryptoOperationUtils() {}

    /**
     * Returns the {@link InvalidKeyException} to be thrown by the {@code init} method of
     * the crypto operation in response to {@code KeyStore.begin} operation or {@code null} if
     * the {@code init} method should succeed.
     */
    static InvalidKeyException getInvalidKeyExceptionForInit(
            KeyStore keyStore, AndroidKeyStoreKey key, int beginOpResultCode) {
        if (beginOpResultCode == KeyStore.NO_ERROR) {
            return null;
        }

        // An error occured. However, some errors should not lead to init throwing an exception.
        // See below.
        InvalidKeyException e =
                keyStore.getInvalidKeyException(key.getAlias(), beginOpResultCode);
        switch (beginOpResultCode) {
            case KeyStore.OP_AUTH_NEEDED:
                // Operation needs to be authorized by authenticating the user. Don't throw an
                // exception is such authentication is possible for this key
                // (UserNotAuthenticatedException). An example of when it's not possible is where
                // the key is permanently invalidated (KeyPermanentlyInvalidatedException).
                if (e instanceof UserNotAuthenticatedException) {
                    return null;
                }
                break;
        }
        return e;
    }

    /**
     * Returns the exception to be thrown by the {@code Cipher.init} method of the crypto operation
     * in response to {@code KeyStore.begin} operation or {@code null} if the {@code init} method
     * should succeed.
     */
    public static GeneralSecurityException getExceptionForCipherInit(
            KeyStore keyStore, AndroidKeyStoreKey key, int beginOpResultCode) {
        if (beginOpResultCode == KeyStore.NO_ERROR) {
            return null;
        }

        // Cipher-specific cases
        switch (beginOpResultCode) {
            case KeymasterDefs.KM_ERROR_INVALID_NONCE:
                return new InvalidAlgorithmParameterException("Invalid IV");
            case KeymasterDefs.KM_ERROR_CALLER_NONCE_PROHIBITED:
                return new InvalidAlgorithmParameterException("Caller-provided IV not permitted");
        }

        // General cases
        return getInvalidKeyExceptionForInit(keyStore, key, beginOpResultCode);
    }

    /**
     * Returns the requested number of random bytes to mix into keystore/keymaster RNG.
     *
     * @param rng RNG from which to obtain the random bytes or {@code null} for the platform-default
     *        RNG.
     */
    static byte[] getRandomBytesToMixIntoKeystoreRng(SecureRandom rng, int sizeBytes) {
        if (sizeBytes <= 0) {
            return EmptyArray.BYTE;
        }
        if (rng == null) {
            rng = getRng();
        }
        byte[] result = new byte[sizeBytes];
        rng.nextBytes(result);
        return result;
    }

    private static SecureRandom getRng() {
        // IMPLEMENTATION NOTE: It's OK to share a SecureRandom instance because SecureRandom is
        // required to be thread-safe.
        if (sRng == null) {
            sRng = new SecureRandom();
        }
        return sRng;
    }
}
