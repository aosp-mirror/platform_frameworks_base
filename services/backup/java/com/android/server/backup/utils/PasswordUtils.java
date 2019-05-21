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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static com.android.server.backup.BackupManagerService.TAG;

import android.util.Slog;

import libcore.util.HexEncoding;

import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Passwords related utility methods.
 */
public class PasswordUtils {
    // Configuration of PBKDF2 that we use for generating pw hashes and intermediate keys
    public static final int PBKDF2_HASH_ROUNDS = 10000;
    private static final int PBKDF2_KEY_SIZE = 256;     // bits
    public static final int PBKDF2_SALT_SIZE = 512;    // bits
    public static final String ENCRYPTION_ALGORITHM_NAME = "AES-256";

    /**
     * Creates {@link SecretKey} instance from given parameters.
     *
     * @param algorithm - key generation algorithm.
     * @param pw - password.
     * @param salt - salt.
     * @param rounds - number of rounds to run in key generation.
     * @return {@link SecretKey} instance or null in case of an error.
     */
    public static SecretKey buildPasswordKey(String algorithm, String pw, byte[] salt, int rounds) {
        return buildCharArrayKey(algorithm, pw.toCharArray(), salt, rounds);
    }

    /**
     * Generates {@link SecretKey} instance from given parameters and returns it's hex
     * representation.
     *
     * @param algorithm - key generation algorithm.
     * @param pw - password.
     * @param salt - salt.
     * @param rounds - number of rounds to run in key generation.
     * @return Hex representation of the generated key, or null if generation failed.
     */
    public static String buildPasswordHash(String algorithm, String pw, byte[] salt, int rounds) {
        SecretKey key = buildPasswordKey(algorithm, pw, salt, rounds);
        if (key != null) {
            return byteArrayToHex(key.getEncoded());
        }
        return null;
    }

    /**
     * Creates hex string representation of the byte array.
     */
    public static String byteArrayToHex(byte[] data) {
        return HexEncoding.encodeToString(data, true);
    }

    /**
     * Creates byte array from it's hex string representation.
     */
    public static byte[] hexToByteArray(String digits) {
        final int bytes = digits.length() / 2;
        if (2 * bytes != digits.length()) {
            throw new IllegalArgumentException("Hex string must have an even number of digits");
        }

        byte[] result = new byte[bytes];
        for (int i = 0; i < digits.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(digits.substring(i, i + 2), 16);
        }
        return result;
    }

    /**
     * Generates {@link SecretKey} instance from given parameters and returns it's checksum.
     *
     * Current implementation returns the key in its primary encoding format.
     *
     * @param algorithm - key generation algorithm.
     * @param pwBytes - password.
     * @param salt - salt.
     * @param rounds - number of rounds to run in key generation.
     * @return Hex representation of the generated key, or null if generation failed.
     */
    public static byte[] makeKeyChecksum(String algorithm, byte[] pwBytes, byte[] salt,
            int rounds) {
        char[] mkAsChar = new char[pwBytes.length];
        for (int i = 0; i < pwBytes.length; i++) {
            mkAsChar[i] = (char) pwBytes[i];
        }

        Key checksum = buildCharArrayKey(algorithm, mkAsChar, salt, rounds);
        return checksum.getEncoded();
    }

    private static SecretKey buildCharArrayKey(String algorithm, char[] pwArray, byte[] salt,
            int rounds) {
        try {
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(algorithm);
            KeySpec ks = new PBEKeySpec(pwArray, salt, rounds, PBKDF2_KEY_SIZE);
            return keyFactory.generateSecret(ks);
        } catch (InvalidKeySpecException e) {
            Slog.e(TAG, "Invalid key spec for PBKDF2!");
        } catch (NoSuchAlgorithmException e) {
            Slog.e(TAG, "PBKDF2 unavailable!");
        }
        return null;
    }
}
