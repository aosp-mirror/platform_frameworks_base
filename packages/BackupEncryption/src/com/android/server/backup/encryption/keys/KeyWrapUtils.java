/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.keys;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Utility functions for wrapping and unwrapping tertiary keys. */
public class KeyWrapUtils {
    private static final String AES_GCM_MODE = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int BITS_PER_BYTE = 8;
    private static final int GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE;
    private static final String KEY_ALGORITHM = "AES";

    /**
     * Uses the secondary key to unwrap the wrapped tertiary key.
     *
     * @param secondaryKey The secondary key used to wrap the tertiary key.
     * @param wrappedKey The wrapped tertiary key.
     * @return The unwrapped tertiary key.
     * @throws InvalidKeyException if the provided secondary key cannot unwrap the tertiary key.
     */
    public static SecretKey unwrap(SecretKey secondaryKey, WrappedKeyProto.WrappedKey wrappedKey)
            throws InvalidKeyException, NoSuchAlgorithmException,
                    InvalidAlgorithmParameterException, NoSuchPaddingException {
        if (wrappedKey.wrapAlgorithm != WrappedKeyProto.WrappedKey.AES_256_GCM) {
            throw new InvalidKeyException(
                    String.format(
                            Locale.US,
                            "Could not unwrap key wrapped with %s algorithm",
                            wrappedKey.wrapAlgorithm));
        }

        if (wrappedKey.metadata == null) {
            throw new InvalidKeyException("Metadata missing from wrapped tertiary key.");
        }

        if (wrappedKey.metadata.type != WrappedKeyProto.KeyMetadata.AES_256_GCM) {
            throw new InvalidKeyException(
                    String.format(
                            Locale.US,
                            "Wrapped key was unexpected %s algorithm. Only support"
                                + " AES/GCM/NoPadding.",
                            wrappedKey.metadata.type));
        }

        Cipher cipher = getCipher();

        cipher.init(
                Cipher.UNWRAP_MODE,
                secondaryKey,
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.nonce));

        return (SecretKey) cipher.unwrap(wrappedKey.key, KEY_ALGORITHM, Cipher.SECRET_KEY);
    }

    /**
     * Wraps the tertiary key with the secondary key.
     *
     * @param secondaryKey The secondary key to use for wrapping.
     * @param tertiaryKey The key to wrap.
     * @return The wrapped key.
     * @throws InvalidKeyException if the key is not good for wrapping.
     * @throws IllegalBlockSizeException if there is an issue wrapping.
     */
    public static WrappedKeyProto.WrappedKey wrap(SecretKey secondaryKey, SecretKey tertiaryKey)
            throws InvalidKeyException, IllegalBlockSizeException, NoSuchAlgorithmException,
                    NoSuchPaddingException {
        Cipher cipher = getCipher();
        cipher.init(Cipher.WRAP_MODE, secondaryKey);

        WrappedKeyProto.WrappedKey wrappedKey = new WrappedKeyProto.WrappedKey();
        wrappedKey.key = cipher.wrap(tertiaryKey);
        wrappedKey.nonce = cipher.getIV();
        wrappedKey.wrapAlgorithm = WrappedKeyProto.WrappedKey.AES_256_GCM;
        wrappedKey.metadata = new WrappedKeyProto.KeyMetadata();
        wrappedKey.metadata.type = WrappedKeyProto.KeyMetadata.AES_256_GCM;
        return wrappedKey;
    }

    /**
     * Rewraps a tertiary key with a new secondary key.
     *
     * @param oldSecondaryKey The old secondary key, used to unwrap the tertiary key.
     * @param newSecondaryKey The new secondary key, used to rewrap the tertiary key.
     * @param tertiaryKey The tertiary key, wrapped by {@code oldSecondaryKey}.
     * @return The tertiary key, wrapped by {@code newSecondaryKey}.
     * @throws InvalidKeyException if the key is not good for wrapping or unwrapping.
     * @throws IllegalBlockSizeException if there is an issue wrapping.
     */
    public static WrappedKeyProto.WrappedKey rewrap(
            SecretKey oldSecondaryKey,
            SecretKey newSecondaryKey,
            WrappedKeyProto.WrappedKey tertiaryKey)
            throws InvalidKeyException, IllegalBlockSizeException,
                    InvalidAlgorithmParameterException, NoSuchAlgorithmException,
                    NoSuchPaddingException {
        return wrap(newSecondaryKey, unwrap(oldSecondaryKey, tertiaryKey));
    }

    private static Cipher getCipher() throws NoSuchPaddingException, NoSuchAlgorithmException {
        return Cipher.getInstance(AES_GCM_MODE);
    }

    // Statics only
    private KeyWrapUtils() {}
}
