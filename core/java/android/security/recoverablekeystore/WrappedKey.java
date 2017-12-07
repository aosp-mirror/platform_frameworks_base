/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.security.recoverablekeystore;

import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * A {@link javax.crypto.SecretKey} wrapped with AES/GCM/NoPadding.
 *
 * @hide
 */
public class WrappedKey {
    private static final String KEY_WRAP_CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    private final byte[] mNonce;
    private final byte[] mKeyMaterial;

    /**
     * Returns a wrapped form of {@code key}, using {@code wrappingKey} to encrypt the key material.
     *
     * @throws InvalidKeyException if {@code wrappingKey} cannot be used to encrypt {@code key}, or
     *     if {@code key} does not expose its key material. See
     *     {@link android.security.keystore.AndroidKeyStoreKey} for an example of a key that does
     *     not expose its key material.
     */
    public static WrappedKey fromSecretKey(
            SecretKey wrappingKey, SecretKey key) throws InvalidKeyException, KeyStoreException {
        if (key.getEncoded() == null) {
            throw new InvalidKeyException(
                    "key does not expose encoded material. It cannot be wrapped.");
        }

        Cipher cipher;
        try {
            cipher = Cipher.getInstance(KEY_WRAP_CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new RuntimeException(
                    "Android does not support AES/GCM/NoPadding. This should never happen.");
        }

        cipher.init(Cipher.WRAP_MODE, wrappingKey);
        byte[] encryptedKeyMaterial;
        try {
            encryptedKeyMaterial = cipher.wrap(key);
        } catch (IllegalBlockSizeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof KeyStoreException) {
                // If AndroidKeyStore encounters any error here, it throws IllegalBlockSizeException
                // with KeyStoreException as the cause. This is due to there being no better option
                // here, as the Cipher#wrap only checked throws InvalidKeyException or
                // IllegalBlockSizeException. If this is the case, we want to propagate it to the
                // caller, so rethrow the cause.
                throw (KeyStoreException) cause;
            } else {
                throw new RuntimeException(
                        "IllegalBlockSizeException should not be thrown by AES/GCM/NoPadding mode.",
                        e);
            }
        }

        return new WrappedKey(/*mNonce=*/ cipher.getIV(), /*mKeyMaterial=*/ encryptedKeyMaterial);
    }

    /**
     * A new instance.
     *
     * @param nonce The nonce with which the key material was encrypted.
     * @param keyMaterial The encrypted bytes of the key material.
     *
     * @hide
     */
    public WrappedKey(byte[] nonce, byte[] keyMaterial) {
        mNonce = nonce;
        mKeyMaterial = keyMaterial;
    }

    /**
     * Returns the nonce with which the key material was encrypted.
     *
     * @hide
     */
    public byte[] getNonce() {
        return mNonce;
    }

    /**
     * Returns the encrypted key material.
     *
     * @hide
     */
    public byte[] getKeyMaterial() {
        return mKeyMaterial;
    }
}
