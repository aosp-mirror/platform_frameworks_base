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

package android.security;

import android.security.keymaster.KeymasterDefs;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;

/**
 * Assorted utility methods for implementing crypto operations on top of KeyStore.
 *
 * @hide
 */
abstract class KeyStoreCryptoOperationUtils {
    private KeyStoreCryptoOperationUtils() {}

    /**
     * Returns the {@link InvalidKeyException} to be thrown by the {@code init} method of
     * the crypto operation in response to {@code KeyStore.begin} operation or {@code null} if
     * the {@code init} method should succeed.
     */
    static InvalidKeyException getInvalidKeyExceptionForInit(
            KeyStore keyStore, KeyStoreKey key, int beginOpResultCode) {
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
    static GeneralSecurityException getExceptionForCipherInit(
            KeyStore keyStore, KeyStoreKey key, int beginOpResultCode) {
        if (beginOpResultCode == KeyStore.NO_ERROR) {
            return null;
        }

        // Cipher-specific cases
        switch (beginOpResultCode) {
            case KeymasterDefs.KM_ERROR_INVALID_NONCE:
                return new InvalidAlgorithmParameterException("Invalid IV");
        }

        // General cases
        return getInvalidKeyExceptionForInit(keyStore, key, beginOpResultCode);
    }
}
